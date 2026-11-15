package dev.moriya.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/**
 * Layer 1 — tracks the device's default non-VPN network.
 *
 * Reduced form of upstream NetworkChangeCallback.kt (DESIGN.md §3.7):
 * watches all INTERNET + NOT_VPN networks, picks the preferred one
 * (validated + has DNS first, non-metered preferred within a group),
 * and reports a [Snapshot] whenever the pick or its link properties
 * change. The service uses the snapshot for three things, none of
 * which involve backend types:
 * - `VpnService.setUnderlyingNetworks(...)` and builder underlying
 *   networks (the physical network the tunnel rides on),
 * - forwarding interface name + gateway to the backend
 *   (`TunnelBackend.onNetworkChanged`),
 * - forwarding the platform DNS config string
 *   (`TunnelBackend.onPlatformDnsConfigChanged`; format follows
 *   upstream DnsConfig: "dns1 dns2 …" plus "\nsearch-domains").
 *
 * One deliberate deviation from a pure callback port: [start]
 * seeds [current] synchronously from the system's current default
 * network before registering the callback. Upstream registers its
 * callback in the Application, long before any connect, so its
 * cached default network is populated by the time the first TUN is
 * built. This tracker is service-scoped — it starts in
 * MokaVpnService.onCreate, moments before the backend may build
 * the first TUN — and registerNetworkCallback delivers its initial
 * events asynchronously, so without the seed the first builder
 * could read a null network and bake an empty underlying-networks
 * array into the TUN's LinkProperties. A VPN network with no
 * underlying networks gives the platform resolver no underlying
 * path to inherit DNS (and Private DNS validation) from, which is
 * the leading host-side suspect for the strict-Private-DNS failure
 * seen on device with CorpDNS off (v0.1.2, 2026-11-15).
 */
class DefaultNetworkTracker(
    context: Context,
    private val onChange: (Snapshot) -> Unit,
) {
    data class Snapshot(
        val network: Network?,
        val interfaceName: String?,
        val gateway: String?,
        val dnsConfig: String,
    )

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private data class Info(
        var caps: NetworkCapabilities? = null,
        var linkProps: LinkProperties? = null,
    )

    private val lock = Any()
    private val activeNetworks = mutableMapOf<Network, Info>()

    @Volatile
    var current: Snapshot = Snapshot(null, null, null, "")
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        // Synchronous seed (see class doc): populate the pick from
        // the system's current default network right away. Only a
        // non-VPN network qualifies — when the service is restarted
        // while our own VPN is already up, activeNetwork IS the VPN,
        // and seeding it would feed the tunnel back into itself.
        try {
            connectivityManager.activeNetwork?.let { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                    synchronized(lock) {
                        activeNetworks[network] = Info(
                            caps = caps,
                            linkProps = connectivityManager.getLinkProperties(network),
                        )
                        recomputeLocked()
                    }
                }
            }
        } catch (_: Exception) {
            // Seeding is best-effort; the callback below delivers
            // the same state asynchronously.
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) {
                    activeNetworks.getOrPut(network) { Info() }
                    recomputeLocked()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                synchronized(lock) {
                    activeNetworks.getOrPut(network) { Info() }.caps = capabilities
                    recomputeLocked()
                }
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                synchronized(lock) {
                    activeNetworks.getOrPut(network) { Info() }.linkProps = linkProperties
                    recomputeLocked()
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    activeNetworks.remove(network)
                    recomputeLocked()
                }
            }
        }
        callback = cb
        connectivityManager.registerNetworkCallback(request, cb)
    }

    fun stop() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
                // Already unregistered.
            }
        }
        callback = null
    }

    /** Must be called under [lock]. */
    private fun recomputeLocked() {
        val picked = pickPreferredLocked()
        val snapshot = if (picked == null) {
            Snapshot(null, null, null, "")
        } else {
            val lp = activeNetworks[picked]?.linkProps
            Snapshot(
                network = picked,
                interfaceName = lp?.interfaceName,
                gateway = lp?.routes
                    ?.filter { it.isDefaultRoute && it.gateway != null }
                    ?.sortedBy { if (it.gateway is Inet4Address) 0 else 1 }
                    ?.firstOrNull()?.gateway?.hostAddress,
                dnsConfig = buildDnsConfig(lp),
            )
        }
        if (snapshot != current) {
            current = snapshot
            onChange(snapshot)
        }
    }

    private fun buildDnsConfig(lp: LinkProperties?): String {
        if (lp == null) return ""
        val sb = StringBuilder()
        for (ip in lp.dnsServers) {
            sb.append(ip.hostAddress).append(' ')
        }
        val domains = lp.domains
        if (domains != null) {
            sb.append('\n').append(domains)
        }
        return sb.toString().trim()
    }

    /** Upstream preference order, reduced: see class doc. */
    private fun pickPreferredLocked(): Network? {
        data class Candidate(
            val network: Network,
            val internet: Boolean,
            val validated: Boolean,
            val hasDns: Boolean,
            val nonMetered: Boolean,
        )

        val candidates = activeNetworks.map { (network, info) ->
            Candidate(
                network = network,
                internet = info.caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                ) == true,
                validated = info.caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                ) == true,
                hasDns = info.linkProps?.dnsServers?.isNotEmpty() == true,
                nonMetered = info.caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                ) == true,
            )
        }.filter { it.internet }

        fun pick(requireValidated: Boolean, requireDns: Boolean): Network? {
            val matching = candidates.filter {
                (!requireValidated || it.validated) && (!requireDns || it.hasDns)
            }
            return (matching.firstOrNull { it.nonMetered } ?: matching.firstOrNull())?.network
        }

        return pick(requireValidated = true, requireDns = true)
            ?: pick(requireValidated = true, requireDns = false)
            ?: pick(requireValidated = false, requireDns = true)
            ?: pick(requireValidated = false, requireDns = false)
    }
}

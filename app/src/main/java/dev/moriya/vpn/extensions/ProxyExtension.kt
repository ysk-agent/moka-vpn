package dev.moriya.vpn.extensions

import android.content.Context
import android.net.Network
import dev.moriya.vpn.tunnel.TunnelState
import dev.moriya.vpn.tunnel.TunnelStatus
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Egress for the proxy, bound to the device's underlying network
 * (v1.1.0, DESIGN.md §9.6).
 *
 * The proxy runs in the VPN app's own process, so an unbound
 * outbound socket would be at the mercy of the system's routing for
 * our own UID — the tunnel's routes today, something else tomorrow.
 * Instead every outbound socket is pinned to the current underlying
 * (non-VPN) network — resolved on that network, bound to it with
 * [Network.bindSocket], then connected — exactly the treatment
 * Layer 3 gives the Go backend's dials (same DefaultNetworkTracker
 * pick, fed in here as [networkProvider]). Egress therefore leaves
 * through the phone's physical network by construction, never
 * through our own TUN. When no underlying network is known the
 * connection is refused rather than dialled unbound.
 */
class NetworkEgress(
    private val networkProvider: () -> Network?,
) : EgressConnector {

    override fun connect(host: String, port: Int): Socket {
        val network = networkProvider()
            ?: throw IOException("no underlying network available")
        val addresses = try {
            network.getAllByName(host)
        } catch (e: Exception) {
            throw IOException("name resolution failed on the underlying network", e)
        }
        if (addresses.isEmpty()) {
            throw IOException("name resolution returned no addresses")
        }
        var lastError: IOException? = null
        for (address in addresses) {
            val socket = Socket()
            try {
                network.bindSocket(socket)
                socket.connect(
                    InetSocketAddress(address, port),
                    CONNECT_TIMEOUT_MS,
                )
                return socket
            } catch (e: IOException) {
                lastError = e
                runCatching { socket.close() }
            }
        }
        throw lastError ?: IOException("cannot connect on the underlying network")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}

/**
 * Proxy extension — a forward proxy hosted by the VPN app
 * (v1.1.0, DESIGN.md §9.6).
 *
 * The same role as phone-node's native proxy (clients on the
 * tailnet connect here; their traffic leaves through this phone's
 * network, so the egress IP is this phone's location), moved into
 * the process Android revives: the proxy now survives independently
 * of the proot/opencode stack, and it is available even while that
 * stack is down. Opt-in (default off): it opens a listening socket.
 *
 * Listening is tied to the tunnel: the listener binds the node's
 * tailnet IPv4 (from Layer 2 [TunnelStatus.selfAddresses]) on
 * [PORT] while the extension is enabled AND the backend is Running,
 * and goes away when either does; an address change rebinds.
 * Binding a specific address — rather than the reference proxy's
 * wildcard — keeps the listener off the LAN entirely.
 *
 * Coexistence with phone-node's proxy: that one binds the wildcard
 * address on the same port, so on a device where it is already
 * listening, this bind fails with address-in-use. The failure is
 * recorded in the status line (never thrown into the host) and the
 * bind is retried periodically and on every tunnel status change,
 * so the listener appears if the port later frees up.
 */
class ProxyExtension(
    override val info: ExtensionInfo,
    networkProvider: () -> Network?,
) : VpnExtension {

    private val egress = NetworkEgress(networkProvider)

    private val lock = Any()
    private var core: ProxyServerCore? = null
    private var lastStatus: TunnelStatus? = null
    private var lastBindError: String? = null

    @Volatile private var started = false
    @Volatile private var retryScheduler: ScheduledExecutorService? = null

    override fun start(context: Context) {
        started = true
        if (retryScheduler == null) {
            val executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "mokavpn-proxy-retry").apply { isDaemon = true }
            }
            retryScheduler = executor
            // Bind retry: covers the port freeing up (phone-node's
            // proxy stopping) without any event reaching us.
            executor.scheduleWithFixedDelay(
                { evaluate() },
                RETRY_INTERVAL_SECONDS, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS,
            )
        }
        evaluate()
    }

    override fun stop() {
        started = false
        retryScheduler?.shutdownNow()
        retryScheduler = null
        synchronized(lock) {
            core?.stop()
            core = null
            lastBindError = null
        }
    }

    override fun onTunnelStatus(status: TunnelStatus) {
        synchronized(lock) { lastStatus = status }
        evaluate()
    }

    override fun statusText(): String {
        synchronized(lock) {
            val live = core
            if (live != null && live.isListening) {
                return "listening on ${live.listenDescription} · " +
                    "served ${live.servedCount} · active ${live.activeCount} · " +
                    "rejected ${live.rejectedCount}"
            }
            val status = lastStatus
            if (status?.state != TunnelState.Running) return "waiting for the tunnel"
            val ipv4 = tailnetIpv4(status)
            if (ipv4 == null) return "no tailnet IPv4 address yet"
            val error = lastBindError
            if (error != null) {
                return "cannot listen on ${ipv4.hostAddress}:$PORT — $error"
            }
            return "starting listener on ${ipv4.hostAddress}:$PORT"
        }
    }

    /**
     * Reconcile the listener with the desired state: started AND
     * tunnel Running AND a tailnet IPv4 present. Safe to call from
     * any thread, any number of times.
     */
    private fun evaluate() {
        synchronized(lock) {
            val status = lastStatus
            val ipv4 = if (started && status?.state == TunnelState.Running) {
                tailnetIpv4(status)
            } else {
                null
            }
            if (ipv4 == null) {
                core?.stop()
                core = null
                lastBindError = null
                return
            }
            val live = core
            if (live != null && live.isListening &&
                live.listenDescription == "${ipv4.hostAddress}:$PORT"
            ) {
                return // already listening on the right address
            }
            live?.stop()
            core = null
            val fresh = ProxyServerCore(ipv4, PORT, ALLOWED_SOURCES, egress)
            try {
                fresh.start()
                core = fresh
                lastBindError = null
            } catch (e: Exception) {
                // Most likely the port is held (phone-node's proxy).
                // Fail soft: status line carries it, retry follows.
                lastBindError = "${e.javaClass.simpleName}: ${e.message}"
                runCatching { fresh.stop() }
            }
        }
    }

    private fun tailnetIpv4(status: TunnelStatus): InetAddress? =
        status.selfAddresses.firstNotNullOfOrNull { text ->
            runCatching { InetAddress.getByName(text) }.getOrNull()
                ?.takeIf { it is Inet4Address }
        }

    companion object {
        const val PORT = 8080
        private const val RETRY_INTERVAL_SECONDS = 15L

        /**
         * Source addresses allowed to use the proxy (and, via this
         * same set, the ADB control endpoint) — Moriya's own devices
         * (muse-2, muse-1, m1, moto, moto-g05) plus loopback for
         * on-device clients.
         *
         * Privacy rule (Moriya, 2026-11-16): no tailnet address of a
         * real device may appear in this public source tree, not
         * even as a comment. The device addresses are therefore
         * injected at CI build time from the ALLOWED_SOURCES GitHub
         * secret (comma-separated IPv4), converted by the Gradle
         * build to 32-bit integers, and generated as
         * [GeneratedAllowlist] — so neither the repository nor a
         * plain strings dump of the APK contains a dotted-quad
         * literal for a real device. They are decoded back to text
         * only in memory at runtime. Builds without the secret
         * (local / emulator) get loopback only.
         */
        val ALLOWED_SOURCES: Set<String> = buildSet {
            add("127.0.0.1") // loopback
            add("::1") // loopback (IPv6)
            GeneratedAllowlist.IPV4_INTEGERS.forEach { value ->
                add(ProxyProtocol.ipv4String(value))
            }
        }
    }
}

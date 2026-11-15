package dev.moriya.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import dev.moriya.vpn.MainActivity
import dev.moriya.vpn.MokaVpnApp
import dev.moriya.vpn.R
import dev.moriya.vpn.extensions.ExtensionManager
import dev.moriya.vpn.extensions.ExtensionSettings
import dev.moriya.vpn.tunnel.TunBuilder
import dev.moriya.vpn.tunnel.TunHost
import dev.moriya.vpn.tunnel.TunSession
import java.net.InetAddress
import java.util.UUID

/**
 * Layer 1 — our Android VpnService shell.
 *
 * Deliberately implements ONLY our own [TunHost] (Layer 2), never a
 * backend interface. Compare upstream IPNService.kt, where the service
 * itself implements libtailscale.IPNService: here that contract is
 * bridged inside Layer 3 (backend/libtailscale), so swapping backends
 * does not touch this class.
 *
 * Start-command branches mirror upstream IPNService.kt:
 *  - ACTION_START_VPN          user asked to connect
 *  - ACTION_STOP_VPN           user asked to disconnect
 *  - "android.net.VpnService"  system start for always-on VPN
 *  - null/unknown action       restart after kill (e.g. OOM): reconnect
 *                              only if the backend says we are enrolled
 */
class MokaVpnService : VpnService(), TunHost {

    override val hostId: String = UUID.randomUUID().toString()

    @Volatile private var closed = false

    private val backend get() = (application as MokaVpnApp).backend

    private lateinit var networkTracker: DefaultNetworkTracker

    private lateinit var extensionManager: ExtensionManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Extension host (v1.0.0, DESIGN.md §9): extensions live and
        // die with this service — the process Android revives for
        // the always-on VPN — and are published for the UI through
        // the companion reference below.
        extensionManager = ExtensionManager(this, backend, ExtensionSettings(this))
        extensions = extensionManager
        extensionManager.start()
        // Default-network tracking (reduced NetworkChangeCallback,
        // DESIGN.md §3.7): the snapshot drives this service's
        // underlying networks and is forwarded to the backend as
        // interface/gateway + platform DNS config changes.
        networkTracker = DefaultNetworkTracker(this) { snapshot ->
            val networks = snapshot.network?.let { arrayOf(it) } ?: emptyArray()
            try {
                setUnderlyingNetworks(networks)
            } catch (_: Exception) {
                // Service not in a state to take networks yet; the
                // next builder creation reads the tracker directly.
            }
            backend.onNetworkChanged(snapshot.interfaceName, snapshot.gateway)
            backend.onPlatformDnsConfigChanged(snapshot.dnsConfig)
        }
        networkTracker.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                backend.disconnect()
                close()
                START_NOT_STICKY
            }
            ACTION_START_VPN -> {
                showForegroundNotification()
                backend.connect()
                backend.onVpnServiceRequested(this)
                START_STICKY
            }
            "android.net.VpnService" -> {
                // Started by Android because we are the always-on VPN.
                // Not a foreground-service start, so a plain status
                // notification (handled by MainActivity/App state) is
                // used instead of startForeground here; upstream does
                // the same split.
                backend.connect()
                backend.onVpnServiceRequested(this)
                START_STICKY
            }
            else -> {
                // Restart after the process was killed. Reconnect only
                // when enrolment exists; asking the backend is the
                // Layer 2 way to know (status NeedsLogin/Stopped means
                // do not resurrect a tunnel the user never started).
                val status = backend.currentStatus()
                if (status.state != dev.moriya.vpn.tunnel.TunnelState.NeedsLogin &&
                    status.state != dev.moriya.vpn.tunnel.TunnelState.Stopped
                ) {
                    showForegroundNotification()
                    backend.onVpnServiceRequested(this)
                    START_STICKY
                } else {
                    START_NOT_STICKY
                }
            }
        }

    override fun onRevoke() {
        // VPN permission moved to another app. Stop the backend
        // session too (state-divergence fix, v1.0.0): the app's
        // reported state must follow a system-side teardown instead
        // of sitting in Running with no TUN behind it.
        backend.disconnect()
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (::extensionManager.isInitialized) {
            extensionManager.stop()
            if (extensions === extensionManager) extensions = null
        }
        if (::networkTracker.isInitialized) networkTracker.stop()
        // Same divergence fix as onRevoke: an orderly service
        // teardown ends the backend session, so the UI's state line
        // never claims Running for a tunnel the system has taken
        // down. (A process kill skips onDestroy entirely, so the
        // restart-after-kill reconnect path is unaffected.)
        backend.disconnect()
        close()
        onVpnStatusChanged(false)
        super.onDestroy()
    }

    // -- TunHost (Layer 2) --

    override fun protect(fd: Int): Boolean = super.protect(fd)

    override fun bindSocketToNetwork(fd: Int): Boolean {
        // Upstream App.bindSocketToNetwork: bind via the cached
        // default network, wrapping the raw fd the same way.
        val network = if (::networkTracker.isInitialized) {
            networkTracker.current.network
        } else {
            null
        } ?: return false
        return try {
            ParcelFileDescriptor.fromFd(fd).use { pfd ->
                network.bindSocket(pfd.fileDescriptor)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun newTunBuilder(): TunBuilder {
        val builder = Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false) // inherit underlying metered status
        }
        if (::networkTracker.isInitialized) {
            val networks = networkTracker.current.network
                ?.let { arrayOf(it) } ?: emptyArray()
            builder.setUnderlyingNetworks(networks)
        }
        // v0.1: no per-app allow/disallow lists (DESIGN.md §5).
        return AndroidTunBuilder(builder)
    }

    override fun onVpnDisconnected() {
        stopSelf()
    }

    override fun onVpnStatusChanged(active: Boolean) {
        // The backend's account of the OS TUN (established / torn
        // down), published to the process-wide holder the UI reads
        // for its separate "Tunnel:" line (state-divergence fix,
        // v1.0.0). Deliberately NOT merged into the backend session
        // state: the two can legitimately differ.
        VpnTunnelState.set(active)
    }

    fun close() {
        if (closed) return
        closed = true
        VpnTunnelState.set(false)
        backend.onVpnServiceDisconnected(hostId)
        stopSelf()
    }

    // -- notification --

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = getString(R.string.vpn_channel_description) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun showForegroundNotification() {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload) // TODO(asset): own icon
            .setContentTitle(getString(R.string.app_name))
            .setContentText("VPN service running")
            .setContentIntent(configIntent())
            .setOngoing(true)
            .build()
        startForeground(STATUS_NOTIFICATION_ID, notification)
    }

    private fun configIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** [TunBuilder] over a real VpnService.Builder. Ours, not a backend type. */
    private class AndroidTunBuilder(private val builder: Builder) : TunBuilder {
        override fun setMtu(mtu: Int) { builder.setMtu(mtu) }
        override fun addAddress(address: String, prefixLength: Int) {
            builder.addAddress(address, prefixLength)
        }
        override fun addDnsServer(address: String) { builder.addDnsServer(address) }
        override fun addSearchDomain(domain: String) { builder.addSearchDomain(domain) }
        override fun addRoute(address: String, prefixLength: Int) {
            builder.addRoute(address, prefixLength)
        }
        override fun excludeRoute(address: String, prefixLength: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefixLength))
            } // else: no-op by contract (matches upstream VPNServiceBuilder.kt)
        }
        override fun establish(): TunSession? =
            builder.establish()?.let { AndroidTunSession(it) }
    }

    private class AndroidTunSession(
        private val pfd: ParcelFileDescriptor,
    ) : TunSession {
        override fun detachFd(): Int = pfd.detachFd()
    }

    companion object {
        const val ACTION_START_VPN = "dev.moriya.vpn.START_VPN"
        const val ACTION_STOP_VPN = "dev.moriya.vpn.STOP_VPN"
        private const val CHANNEL_ID = "moka-vpn-status"
        private const val STATUS_NOTIFICATION_ID = 1

        /**
         * The live extension host, published by onCreate and cleared
         * by onDestroy so MainActivity (same process) can render and
         * toggle extensions. Null while the VPN service is stopped;
         * the UI then falls back to the persisted flags alone.
         */
        @Volatile
        var extensions: ExtensionManager? = null
            private set
    }
}

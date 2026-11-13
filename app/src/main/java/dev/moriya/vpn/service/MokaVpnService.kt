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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // TODO(network): register the ConnectivityManager default-network
        // callback here and forward changes as
        // backend.onNetworkChanged(ifname, gateway) plus
        // setUnderlyingNetworks(...) on this service. Reduced form of
        // upstream NetworkChangeCallback.kt — DESIGN.md §3.7. Required
        // before the moto trial: without it Wi-Fi<->mobile switches
        // strand the tunnel.
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
        // VPN permission moved to another app.
        backend.disconnect()
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        close()
        onVpnStatusChanged(false)
        super.onDestroy()
    }

    // -- TunHost (Layer 2) --

    override fun protect(fd: Int): Boolean = super.protect(fd)

    override fun newTunBuilder(): TunBuilder {
        val builder = Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false) // inherit underlying metered status
        }
        // TODO(network): builder.setUnderlyingNetworks(arrayOf(default))
        // once the network callback above is wired.
        // v0.1: no per-app allow/disallow lists (DESIGN.md §5).
        return AndroidTunBuilder(builder)
    }

    override fun onVpnDisconnected() {
        stopSelf()
    }

    override fun onVpnStatusChanged(active: Boolean) {
        // TODO(ui-state): forward to a process-wide status holder the
        // MainActivity observes (the TunnelBackend listener already
        // carries full status; this hook exists for the service-local
        // notification state).
    }

    fun close() {
        if (closed) return
        closed = true
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
    }
}

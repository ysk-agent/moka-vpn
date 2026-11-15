package dev.moriya.vpn.extensions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB keepalive extension — keeps wireless debugging on (v1.2.0,
 * DESIGN.md §9.7).
 *
 * Why this exists (measured on moto g05, Android 15, 2026-11-16):
 * adb on the node phones is fixed with `adb tcpip 5555`, and the
 * 5555 listener revives on its own once adbd runs again — but adbd
 * runs only while at least one debug toggle is on, and wireless
 * debugging sometimes turns itself off, killing adbd and with it
 * the only remote way back in. The steady state Moriya chose is
 * wireless ON / USB OFF (many apps refuse to start while USB
 * debugging is on), so the toggle worth guarding is the wireless
 * one. This extension is that guard: hosted in the process Android
 * revives, it watches `adb_wifi_enabled` and turns it back on when
 * it drops.
 *
 * The decision of whether to write lives in [AdbKeepaliveLogic]
 * (pure, unit-tested); this class is the Android-facing shell —
 * scheduler, Settings.Global I/O, the grant check, the Wi-Fi check.
 *
 * HARD RULE: this extension reads and writes
 * [AdbKeepaliveLogic.SETTING_KEY] (`adb_wifi_enabled`) and nothing
 * else. `adb_enabled` (USB debugging) is never touched, under any
 * circumstance — turning it on would reintroduce the app-refusal
 * problem the wireless-only setup exists to avoid.
 *
 * Grant model: the manifest declares WRITE_SECURE_SETTINGS, but
 * the system never grants it automatically; it is granted once by
 * hand with `pm grant` over adb (see [GRANT_COMMAND]). Without the
 * grant the extension only observes and reports — a write is never
 * attempted (the decision logic returns NO_GRANT), and the status
 * line carries the grant command so the fix is discoverable from
 * the UI.
 */
class AdbKeepaliveExtension(
    override val info: ExtensionInfo,
    private val underlyingNetwork: () -> Network?,
) : VpnExtension {

    @Volatile private var appContext: Context? = null
    @Volatile private var scheduler: ScheduledExecutorService? = null

    // Last observation, for the status line. Written by the single
    // poll thread, read from the UI thread.
    @Volatile private var hasPolled = false
    @Volatile private var lastToggleValue: Int? = null
    @Volatile private var lastGrantHeld = false
    @Volatile private var lastWifiConnected = false
    @Volatile private var lastWriteError: String? = null

    private val restoreCount = AtomicInteger(0)
    @Volatile private var lastRestoreAtMs = 0L

    override fun start(context: Context) {
        appContext = context.applicationContext
        if (scheduler != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mokavpn-adb-keepalive").apply { isDaemon = true }
        }
        scheduler = executor
        // First poll immediately, then on the Health extension's
        // cadence. A dropped toggle is not a seconds-critical event:
        // adb being down for under a minute is acceptable.
        executor.scheduleWithFixedDelay(
            { poll() }, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS,
        )
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        appContext = null
    }

    override fun statusText(): String {
        if (!hasPolled) return "no checks yet"
        val toggle = lastToggleValue?.toString() ?: "unknown"
        val grant = if (lastGrantHeld) "held" else "NOT held"
        val wifi = if (lastWifiConnected) "connected" else "not connected"
        val lastRestore = if (lastRestoreAtMs > 0) {
            formatTime(lastRestoreAtMs)
        } else {
            "never"
        }
        val sb = StringBuilder()
            .append("adb_wifi_enabled: ").append(toggle)
            .append(" · grant: ").append(grant)
            .append(" · Wi-Fi: ").append(wifi)
            .append(" · restores: ").append(restoreCount.get())
            .append(" · last restore ").append(lastRestore)
        if (!lastGrantHeld) {
            sb.append(" · grant with: ").append(GRANT_COMMAND)
        }
        lastWriteError?.let { sb.append(" · last write failed: ").append(it) }
        return sb.toString()
    }

    private fun poll() {
        val context = appContext ?: return
        val resolver = context.contentResolver

        val toggleValue = try {
            Settings.Global.getInt(resolver, AdbKeepaliveLogic.SETTING_KEY)
        } catch (_: Exception) {
            // SettingNotFoundException (never written on this
            // device) or a provider hiccup: unreadable, which the
            // decision logic treats as "never write".
            null
        }
        val grantHeld = try {
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        val wifiConnected = isUnderlyingWifi(context)

        lastToggleValue = toggleValue
        lastGrantHeld = grantHeld
        lastWifiConnected = wifiConnected
        hasPolled = true

        if (AdbKeepaliveLogic.decide(toggleValue, grantHeld, wifiConnected) !=
            AdbKeepaliveLogic.Action.RESTORE
        ) {
            return
        }
        try {
            val written = Settings.Global.putInt(
                resolver, AdbKeepaliveLogic.SETTING_KEY, 1,
            )
            if (written) {
                restoreCount.incrementAndGet()
                lastRestoreAtMs = System.currentTimeMillis()
                lastWriteError = null
            } else {
                lastWriteError = "putInt returned false"
            }
        } catch (e: SecurityException) {
            // The grant check said held but the write was refused
            // (grant revoked between check and write, or an OEM
            // quirk). Record it for the status line; never crash
            // the host service over a settings write.
            lastWriteError = "SecurityException: ${e.message}"
        } catch (e: Exception) {
            lastWriteError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Whether the device's underlying physical network is Wi-Fi.
     *
     * This app is itself the VPN, so the system's default network
     * may be our own TUN; asking about "the current network" would
     * answer about the tunnel, not the hardware. Instead this uses
     * the host's underlying-network pick — the DefaultNetworkTracker
     * selection, which is NOT_VPN by construction — passed in
     * through ExtensionManager (the same provider the Proxy's
     * egress binds to), and reads that network's transports. On
     * mobile data the pick is the cellular network and this returns
     * false, which is exactly the gate the decision logic wants:
     * with Wi-Fi down, the system will not keep a restored toggle.
     */
    private fun isUnderlyingWifi(context: Context): Boolean {
        val network = underlyingNetwork() ?: return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val caps = try {
            cm.getNetworkCapabilities(network)
        } catch (_: Exception) {
            null
        } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    companion object {
        const val POLL_INTERVAL_SECONDS = 60L

        /** The one-time manual grant, shown in the UI when missing. */
        const val GRANT_COMMAND =
            "adb shell pm grant dev.moriya.vpn " +
                "android.permission.WRITE_SECURE_SETTINGS"
    }
}

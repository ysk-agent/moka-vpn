package dev.moriya.vpn.extensions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import dev.moriya.vpn.tunnel.TunnelState
import dev.moriya.vpn.tunnel.TunnelStatus
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB keepalive extension — keeps wireless debugging on (v1.2.0,
 * DESIGN.md §9.7) and, since v1.3.0, serves the remote debug
 * control endpoint described below.
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
 * HARD SEPARATION (v1.3.0): the autonomous watcher in this class
 * reads and writes [AdbKeepaliveLogic.SETTING_KEY]
 * (`adb_wifi_enabled`) and nothing else. `adb_enabled` (USB
 * debugging) is never written by any timer or automatic logic —
 * turning it on autonomously would reintroduce the app-refusal
 * problem the wireless-only setup exists to avoid. The ONE path
 * that writes `adb_enabled` is [setUsbDebugging], reached only
 * from the control endpoint after [AdbControlProtocol.authorize]
 * has passed BOTH its factors (source allowlist + token): an
 * explicit command from Moriya's own devices, which is his
 * on-demand workaround for mobile data — wireless debugging
 * cannot keep adbd alive there, while the tunnel (and so this
 * endpoint) stays up (§9.7).
 *
 * The control listener mirrors the Proxy extension's listening
 * discipline: bound to the node's tailnet IPv4 from
 * [TunnelStatus.selfAddresses] — never the wildcard, so it is not
 * exposed on the LAN — only while the extension is enabled AND
 * the backend is Running, rebound on address changes, a failed
 * bind recorded in the status line and retried every 15 s.
 *
 * Grant model: the manifest declares WRITE_SECURE_SETTINGS, but
 * the system never grants it automatically; it is granted once by
 * hand with `pm grant` over adb (see [GRANT_COMMAND]). Without the
 * grant the extension only observes and reports — a watcher write
 * is never attempted (the decision logic returns NO_GRANT), an
 * endpoint write answers 500, and the status line carries the
 * grant command so the fix is discoverable from the UI.
 */
class AdbKeepaliveExtension(
    override val info: ExtensionInfo,
    private val underlyingNetwork: () -> Network?,
) : VpnExtension, AdbControlActions {

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

    // -- control endpoint state (v1.3.0) --
    private val controlLock = Any()
    private var controlCore: AdbControlServerCore? = null
    private var lastTunnelStatus: TunnelStatus? = null
    private var lastControlBindError: String? = null

    @Volatile private var controlStarted = false
    @Volatile private var retryScheduler: ScheduledExecutorService? = null

    /** The endpoint token; generated once, persisted, shown in the status. */
    @Volatile private var controlToken: String? = null

    /** Last endpoint write failure, for the status line. */
    @Volatile private var lastControlError: String? = null

    override fun start(context: Context) {
        appContext = context.applicationContext
        if (scheduler == null) {
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
        // Control endpoint: the token must exist before the listener
        // can authorize anything, and the bind retry covers the
        // tunnel arriving after the extension starts.
        ensureControlToken(context)
        controlStarted = true
        if (retryScheduler == null) {
            val retry = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "mokavpn-adb-control-retry").apply { isDaemon = true }
            }
            retryScheduler = retry
            retry.scheduleWithFixedDelay(
                { evaluateControl() },
                CONTROL_RETRY_INTERVAL_SECONDS,
                CONTROL_RETRY_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
        evaluateControl()
    }

    override fun stop() {
        controlStarted = false
        retryScheduler?.shutdownNow()
        retryScheduler = null
        synchronized(controlLock) {
            controlCore?.stop()
            controlCore = null
            lastControlBindError = null
        }
        scheduler?.shutdownNow()
        scheduler = null
        appContext = null
    }

    override fun onTunnelStatus(status: TunnelStatus) {
        synchronized(controlLock) { lastTunnelStatus = status }
        evaluateControl()
    }

    override fun statusText(): String {
        val sb = StringBuilder()
        if (!hasPolled) {
            sb.append("no checks yet")
        } else {
            val toggle = lastToggleValue?.toString() ?: "unknown"
            val grant = if (lastGrantHeld) "held" else "NOT held"
            val wifi = if (lastWifiConnected) "connected" else "not connected"
            val lastRestore = if (lastRestoreAtMs > 0) {
                formatTime(lastRestoreAtMs)
            } else {
                "never"
            }
            sb.append("adb_wifi_enabled: ").append(toggle)
                .append(" · grant: ").append(grant)
                .append(" · Wi-Fi: ").append(wifi)
                .append(" · restores: ").append(restoreCount.get())
                .append(" · last restore ").append(lastRestore)
            if (!lastGrantHeld) {
                sb.append(" · grant with: ").append(GRANT_COMMAND)
            }
            lastWriteError?.let { sb.append(" · last write failed: ").append(it) }
        }
        sb.append(" · control: ").append(controlStatusText())
        controlToken?.let { sb.append(" · token: ").append(it) }
        synchronized(controlLock) {
            controlCore?.let { core ->
                core.lastAccepted?.let { last ->
                    sb.append(" · last command: ").append(last.action)
                        .append(" from ").append(last.sourceAddress)
                        .append(" at ").append(formatTime(last.atMs))
                }
                if (core.deniedCount > 0) {
                    sb.append(" · denied: ").append(core.deniedCount)
                }
            }
        }
        lastControlError?.let {
            sb.append(" · last control write failed: ").append(it)
        }
        return sb.toString()
    }

    private fun controlStatusText(): String {
        synchronized(controlLock) {
            val live = controlCore
            if (live != null && live.isListening) {
                return "listening on ${live.listenDescription}"
            }
            val status = lastTunnelStatus
            if (status?.state != TunnelState.Running) return "waiting for the tunnel"
            val ipv4 = tailnetIpv4(status)
            if (ipv4 == null) return "no tailnet IPv4 address yet"
            val error = lastControlBindError
            if (error != null) {
                return "cannot listen on ${ipv4.hostAddress}:$CONTROL_PORT — $error"
            }
            return "starting listener on ${ipv4.hostAddress}:$CONTROL_PORT"
        }
    }

    private fun poll() {
        val context = appContext ?: return
        val resolver = context.contentResolver

        val toggleValue = readSetting(context, AdbKeepaliveLogic.SETTING_KEY)
        val grantHeld = isGrantHeld(context)
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

    // -- AdbControlActions: the endpoint's settings writes. These
    // are the ONLY call sites that write adb_enabled anywhere in
    // the app, and they run only after AdbControlProtocol has
    // authorized the request (allowlist + token) in
    // AdbControlServerCore. --

    override fun setUsbDebugging(on: Boolean): Pair<Int, String> {
        val context = appContext
            ?: return 500 to "error: extension not running\n"
        if (!isGrantHeld(context)) {
            val error = "WRITE_SECURE_SETTINGS grant not held"
            lastControlError = error
            return 500 to "error: $error; grant with: $GRANT_COMMAND\n"
        }
        return try {
            val written = Settings.Global.putInt(
                context.contentResolver,
                AdbControlProtocol.USB_SETTING_KEY,
                if (on) 1 else 0,
            )
            if (written) {
                lastControlError = null
                val now = readSetting(context, AdbControlProtocol.USB_SETTING_KEY)
                200 to "adb_enabled: ${now?.toString() ?: "unknown"}\n"
            } else {
                lastControlError = "putInt returned false"
                500 to "error: putInt returned false\n"
            }
        } catch (e: SecurityException) {
            lastControlError = "SecurityException: ${e.message}"
            500 to "error: SecurityException: ${e.message}\n"
        } catch (e: Exception) {
            lastControlError = "${e.javaClass.simpleName}: ${e.message}"
            500 to "error: ${e.javaClass.simpleName}: ${e.message}\n"
        }
    }

    override fun statusBody(): String {
        val context = appContext ?: return "error: extension not running\n"
        return AdbControlProtocol.formatStatus(
            adbWifiEnabled = readSetting(context, AdbKeepaliveLogic.SETTING_KEY),
            adbEnabled = readSetting(context, AdbControlProtocol.USB_SETTING_KEY),
            grantHeld = isGrantHeld(context),
        )
    }

    // -- control listener lifecycle (Proxy pattern) --

    /**
     * Reconcile the listener with the desired state: started AND
     * tunnel Running AND a tailnet IPv4 present. Safe to call from
     * any thread, any number of times.
     */
    private fun evaluateControl() {
        synchronized(controlLock) {
            val status = lastTunnelStatus
            val ipv4 = if (controlStarted && status?.state == TunnelState.Running) {
                tailnetIpv4(status)
            } else {
                null
            }
            if (ipv4 == null) {
                controlCore?.stop()
                controlCore = null
                lastControlBindError = null
                return
            }
            val live = controlCore
            if (live != null && live.isListening &&
                live.listenDescription == "${ipv4.hostAddress}:$CONTROL_PORT"
            ) {
                return // already listening on the right address
            }
            live?.stop()
            controlCore = null
            val fresh = AdbControlServerCore(
                bindAddress = ipv4,
                port = CONTROL_PORT,
                allowedSources = ProxyExtension.ALLOWED_SOURCES,
                tokenProvider = { controlToken },
                actions = this,
            )
            try {
                fresh.start()
                controlCore = fresh
                lastControlBindError = null
            } catch (e: Exception) {
                lastControlBindError = "${e.javaClass.simpleName}: ${e.message}"
                runCatching { fresh.stop() }
            }
        }
    }

    /**
     * Load the endpoint token from the plain settings prefs, or
     * generate and persist one on first use: 32 secure-random
     * bytes as 64 hex characters. The token is a per-device
     * secret; it is shown in the status line because the owner's
     * handover channel is reading it off the phone screen once and
     * saving it on the VM (tools/moka-adb-debug). There is no
     * regenerate UI in this version — clearing the app's data (or
     * the pref) is the rotation path.
     */
    private fun ensureControlToken(context: Context) {
        if (controlToken != null) return
        val prefs = context.getSharedPreferences(
            ExtensionSettings.PREFS_FILE, Context.MODE_PRIVATE,
        )
        val stored = prefs.getString(TOKEN_PREF_KEY, null)
        if (!stored.isNullOrBlank()) {
            controlToken = stored
            return
        }
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(TOKEN_PREF_KEY, generated).apply()
        controlToken = generated
    }

    private fun tailnetIpv4(status: TunnelStatus): InetAddress? =
        status.selfAddresses.firstNotNullOfOrNull { text ->
            runCatching { InetAddress.getByName(text) }.getOrNull()
                ?.takeIf { it is Inet4Address }
        }

    // -- shared observation helpers --

    /** Read a Settings.Global int; null when unreadable. */
    private fun readSetting(context: Context, key: String): Int? = try {
        Settings.Global.getInt(context.contentResolver, key)
    } catch (_: Exception) {
        // SettingNotFoundException (never written on this device)
        // or a provider hiccup: unreadable.
        null
    }

    private fun isGrantHeld(context: Context): Boolean = try {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
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

        /** Control endpoint port (the Proxy's is 8080; no collision). */
        const val CONTROL_PORT = 8091
        private const val CONTROL_RETRY_INTERVAL_SECONDS = 15L

        /** 32 random bytes → a 64-hex-character token. */
        private const val TOKEN_BYTES = 32
        private const val TOKEN_PREF_KEY = "adb-keepalive-control-token"

        /** The one-time manual grant, shown in the UI when missing. */
        const val GRANT_COMMAND =
            "adb shell pm grant dev.moriya.vpn " +
                "android.permission.WRITE_SECURE_SETTINGS"
    }
}

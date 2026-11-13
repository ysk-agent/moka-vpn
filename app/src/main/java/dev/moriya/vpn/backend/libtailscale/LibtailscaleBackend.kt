package dev.moriya.vpn.backend.libtailscale

import dev.moriya.vpn.tunnel.TunHost
import dev.moriya.vpn.tunnel.TunnelBackend
import dev.moriya.vpn.tunnel.TunnelConfig
import dev.moriya.vpn.tunnel.TunnelState
import dev.moriya.vpn.tunnel.TunnelStatus
import dev.moriya.vpn.tunnel.TunnelStatusListener
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Layer 3 — the libtailscale adapter, wired for real (2026-11-14).
 *
 * This package is the ONLY place in the app allowed to import
 * `libtailscale.*` (DESIGN.md §2; enforced by a grep check in CI).
 *
 * What is wired:
 * - `Libtailscale.start(dataDir, dataDir, hwAttestationPref=false,
 *   appContext)` exactly once per process, on a background thread at
 *   init (process start, before any VPN request, so backend sockets
 *   exist before RequestVPN; upstream relies on a magicsock rebind at
 *   request time — backend.go). Any failure to load or start the
 *   native backend (missing AAR, wrong ABI) is caught and honestly
 *   reported as [TunnelState.BackendUnavailable].
 * - `Application.watchNotifications` with upstream's mask
 *   (Prefs|InitialState|PeerChanges|NoNetmap|InitialStatus|
 *   InitialHealthState = 4|2|4096|8192|16384|128); ipn.Notify JSON is
 *   parsed with Layer-3-local minimal org.json code (DESIGN.md §8:
 *   do NOT copy upstream's ui/model package) and mapped onto
 *   [TunnelStatus].
 * - LocalAPI control flow (upstream ui/localapi/Client.kt shapes):
 *   POST `start` with {"AuthKey": …, "UpdatePrefs": {…}} for
 *   enrolment, PATCH `prefs` with MaskedPrefs WantRunning for
 *   connect/disconnect, POST `logout` + `reset-auth` for
 *   resetEnrolment, GET `status` on demand via [currentStatus]
 *   refreshes from Notify state only (status endpoint is used after
 *   start to seed addresses if no Notify has arrived yet).
 * - VPN handshake: [onVpnServiceRequested] wraps the host in
 *   [LibtailscaleIPNService] and calls `Libtailscale.requestVPN`;
 *   [onVpnServiceDisconnected] calls `Libtailscale.serviceDisconnect`.
 * - Network changes: [onNetworkChanged] forwards to
 *   `Libtailscale.onDNSConfigChanged` / `onGatewayChanged` when the
 *   values actually change (upstream NetworkChangeCallback semantics);
 *   [onPlatformDnsConfigChanged] stores the DNS config string that
 *   `AppContext.getPlatformDNSConfig` serves.
 * - The auth key, when supplied once, is persisted through
 *   [EncryptedPrefStore] (Keystore AES-GCM) and reused on later starts
 *   so a restarted process can re-enrol without user input. It is
 *   never logged. resetEnrolment clears it.
 *
 * All LocalAPI calls run on a single background executor: they block,
 * and Layer 1 calls from arbitrary threads.
 */
class LibtailscaleBackend : TunnelBackend {

    override val backendName: String = "libtailscale"

    private val listeners = CopyOnWriteArraySet<TunnelStatusListener>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "libtailscale-backend").apply { isDaemon = true }
    }

    @Volatile
    private var status = TunnelStatus(
        state = TunnelState.Stopped,
        backendName = backendName,
    )

    @Volatile private var services: LibtailscaleAppServices? = null
    @Volatile private var app: libtailscale.Application? = null
    @Volatile private var startFailed: String? = null
    @Volatile private var notifications: libtailscale.NotificationManager? = null

    @Volatile private var currentHost: TunHost? = null
    private val ipnServices = HashMap<String, LibtailscaleIPNService>()

    @Volatile private var platformDnsConfig: String = ""
    @Volatile private var lastInterfaceName: String? = null
    @Volatile private var lastGateway: String? = null

    override fun init(appServices: Any) {
        services = appServices as? LibtailscaleAppServices
            ?: error("LibtailscaleBackend requires LibtailscaleAppServices")
        val svc = services!!
        val appContext = LibtailscaleAppContext(
            data = svc.appContextData,
            platformDnsConfig = { platformDnsConfig },
            bindSocket = { fd -> currentHost?.bindSocketToNetwork(fd) ?: false },
        )
        executor.execute {
            try {
                val application = libtailscale.Libtailscale.start(
                    svc.dataDir, svc.dataDir, false, appContext,
                )
                app = application
                notifications = application.watchNotifications(NOTIFY_MASK) { bytes ->
                    onNotify(bytes)
                }
            } catch (t: Throwable) {
                // Missing AAR / wrong ABI / Go-side start failure: the
                // app must still launch and say so (DESIGN.md §2.1).
                startFailed = t.message ?: t.javaClass.simpleName
                updateStatus(
                    status.copy(
                        state = TunnelState.BackendUnavailable,
                        lastError = "libtailscale failed to start: $startFailed",
                    ),
                )
            }
        }
    }

    override fun start(config: TunnelConfig) {
        executor.execute {
            val application = app ?: return@execute reportUnavailable()
            try {
                val svc = services ?: return@execute
                val authKey = config.authKey
                    ?: storedAuthKey()
                if (config.authKey != null) {
                    // Persist for unattended re-enrolment after a
                    // process restart; never logged.
                    svc.encryptedPrefs.encrypt(AUTH_KEY_PREF, config.authKey)
                }
                val options = JSONObject()
                if (authKey != null) options.put("AuthKey", authKey)
                val updatePrefs = JSONObject()
                config.hostname?.let { updatePrefs.put("HostName", it) }
                config.loginServer?.let { updatePrefs.put("ControlURL", it) }
                if (updatePrefs.length() > 0) options.put("UpdatePrefs", updatePrefs)
                callApi(application, "POST", "start", options.toString().toByteArray(Charsets.UTF_8))
                setWantRunning(application, true)
            } catch (e: Exception) {
                updateStatus(status.copy(state = TunnelState.Error, lastError = e.message))
            }
        }
    }

    override fun connect() {
        executor.execute {
            val application = app ?: return@execute reportUnavailable()
            try {
                setWantRunning(application, true)
            } catch (e: Exception) {
                updateStatus(status.copy(state = TunnelState.Error, lastError = e.message))
            }
        }
    }

    override fun disconnect() {
        executor.execute {
            val application = app ?: return@execute reportUnavailable()
            try {
                setWantRunning(application, false)
            } catch (e: Exception) {
                updateStatus(status.copy(lastError = e.message))
            }
        }
    }

    override fun resetEnrolment() {
        executor.execute {
            val application = app ?: return@execute reportUnavailable()
            try {
                callApi(application, "POST", "logout", null)
            } catch (_: Exception) {
                // logout may legitimately fail when never enrolled.
            }
            try {
                callApi(application, "POST", "reset-auth", null)
            } catch (e: Exception) {
                updateStatus(status.copy(lastError = e.message))
            }
            services?.encryptedPrefs?.encrypt(AUTH_KEY_PREF, "")
        }
    }

    override fun currentStatus(): TunnelStatus = status

    override fun addListener(listener: TunnelStatusListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TunnelStatusListener) {
        listeners.remove(listener)
    }

    override fun onVpnServiceRequested(host: TunHost) {
        currentHost = host
        if (app == null) {
            // Backend not started (yet, or failed): there is nothing to
            // hand the service to. Report honestly via the host.
            host.onVpnStatusChanged(false)
            return
        }
        val bridge = LibtailscaleIPNService(host)
        synchronized(ipnServices) { ipnServices[host.hostId] = bridge }
        try {
            libtailscale.Libtailscale.requestVPN(bridge)
        } catch (t: Throwable) {
            updateStatus(status.copy(lastError = "requestVPN failed: ${t.message}"))
            host.onVpnStatusChanged(false)
        }
    }

    override fun onVpnServiceDisconnected(hostId: String) {
        val bridge = synchronized(ipnServices) { ipnServices.remove(hostId) }
        if (currentHost?.hostId == hostId) currentHost = null
        if (bridge != null && app != null) {
            try {
                libtailscale.Libtailscale.serviceDisconnect(bridge)
            } catch (_: Throwable) {
                // Backend already gone; nothing to disconnect.
            }
        }
    }

    override fun onNetworkChanged(interfaceName: String?, gatewayAddress: String?) {
        if (app == null) return
        try {
            if (gatewayAddress != null && gatewayAddress != lastGateway) {
                lastGateway = gatewayAddress
                libtailscale.Libtailscale.onGatewayChanged(gatewayAddress)
            }
            if (interfaceName != null && interfaceName != lastInterfaceName) {
                lastInterfaceName = interfaceName
                libtailscale.Libtailscale.onDNSConfigChanged(interfaceName)
            }
        } catch (_: Throwable) {
            // Backend not running; the next start re-reads the network.
        }
    }

    override fun onPlatformDnsConfigChanged(config: String) {
        platformDnsConfig = config
    }

    // -- internals --

    private fun reportUnavailable() {
        if (status.state != TunnelState.BackendUnavailable) {
            updateStatus(
                status.copy(
                    state = TunnelState.BackendUnavailable,
                    lastError = startFailed?.let { "libtailscale failed to start: $it" }
                        ?: "libtailscale is still starting",
                ),
            )
        }
    }

    private fun storedAuthKey(): String? {
        val stored = try {
            services?.encryptedPrefs?.decrypt(AUTH_KEY_PREF)
        } catch (_: Exception) {
            null
        }
        return stored?.ifEmpty { null }
    }

    private fun setWantRunning(application: libtailscale.Application, wantRunning: Boolean) {
        val masked = JSONObject()
            .put("WantRunning", wantRunning)
            .put("WantRunningSet", true)
        callApi(application, "PATCH", "prefs", masked.toString().toByteArray(Charsets.UTF_8))
    }

    /** One LocalAPI round trip; throws on transport error or HTTP >= 400. */
    private fun callApi(
        application: libtailscale.Application,
        method: String,
        path: String,
        body: ByteArray?,
    ): String {
        val response = application.callLocalAPI(
            LOCAL_API_TIMEOUT_MS, method, "/localapi/v0/$path",
            body?.let { ByteArrayInputStreamAdapter(it) },
        )
        val data = response.bodyBytes() ?: ByteArray(0)
        val text = data.toString(Charsets.UTF_8)
        if (response.statusCode() >= 400) {
            throw IllegalStateException("LocalAPI $method $path -> ${response.statusCode()}: $text")
        }
        return text
    }

    /** ipn.Notify handler — runs on a Go callback thread. */
    private fun onNotify(bytes: ByteArray) {
        val json = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        var next = status
        json.optJSONObject("Prefs")?.let { prefs ->
            if (prefs.optBoolean("LoggedOut", false) && next.state == TunnelState.Running) {
                next = next.copy(state = TunnelState.NeedsLogin)
            }
        }
        if (json.has("State") && !json.isNull("State")) {
            next = next.copy(state = mapState(json.optInt("State", 0)))
        }
        val addresses = extractSelfAddresses(json)
        if (addresses.isNotEmpty()) {
            next = next.copy(selfAddresses = addresses)
        }
        json.optJSONObject("InitialStatus")?.let { initial ->
            val ips = initial.optJSONArray("TailscaleIPs")
            if (ips != null && ips.length() > 0 && next.selfAddresses.isEmpty()) {
                next = next.copy(
                    selfAddresses = (0 until ips.length()).map { ips.optString(it) },
                )
            }
            initial.optJSONObject("Peer")?.let { peers ->
                next = next.copy(peerCount = peers.length())
            }
            if (initial.has("State") && !json.has("State")) {
                next = next.copy(state = mapState(initial.optInt("State", 0)))
            }
        }
        if (json.has("ErrMessage") && !json.isNull("ErrMessage")) {
            val err = json.optString("ErrMessage")
            if (err.isNotEmpty()) next = next.copy(lastError = err)
        }
        if (next != status) updateStatus(next)
    }

    private fun extractSelfAddresses(notify: JSONObject): List<String> {
        val netMap = notify.optJSONObject("NetMap") ?: return emptyList()
        val selfNode = netMap.optJSONObject("SelfNode") ?: return emptyList()
        val addresses = selfNode.optJSONArray("Addresses") ?: return emptyList()
        return (0 until addresses.length()).map { i ->
            // Addresses are CIDRs ("100.x.y.z/32"); strip the prefix.
            addresses.optString(i).substringBefore('/')
        }.filter { it.isNotEmpty() }
    }

    /** ipn.State values (upstream ui/model/Ipn.kt) -> our TunnelState. */
    private fun mapState(value: Int): TunnelState = when (value) {
        2, 3 -> TunnelState.NeedsLogin // NeedsLogin, NeedsMachineAuth
        4 -> TunnelState.Stopped
        5 -> TunnelState.Starting
        6 -> TunnelState.Running
        7 -> TunnelState.Stopping
        else -> TunnelState.Stopped // NoState, InUseOtherUser, unknown
    }

    private fun updateStatus(newStatus: TunnelStatus) {
        status = newStatus
        listeners.forEach { it.onTunnelStatus(newStatus) }
    }

    /** libtailscale.InputStream over a byte array (upstream InputStreamAdapter shape). */
    private class ByteArrayInputStreamAdapter(bytes: ByteArray) : libtailscale.InputStream {
        private val stream = ByteArrayInputStream(bytes)
        override fun read(): ByteArray? {
            val buffer = ByteArray(4096)
            val n = stream.read(buffer)
            return if (n == -1) null else buffer.copyOf(n)
        }

        override fun close() {
            stream.close()
        }
    }

    companion object {
        // Prefs(4) | InitialState(2) | PeerChanges(4096) | NoNetmap(8192)
        // | InitialStatus(16384) | InitialHealthState(128) = 28806
        // — upstream Notifier.kt.
        private const val NOTIFY_MASK: Long = 28806L
        private const val LOCAL_API_TIMEOUT_MS: Long = 30_000
        private const val AUTH_KEY_PREF = "mokavpn-auth-key"
    }
}

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
 * - LocalAPI enrolment follows upstream's canonical login chain
 *   (ui/viewModel/IpnViewModel.kt `login()`, in order):
 *   1. PATCH `prefs` with a MaskedPrefs that clears LoggedOut when an
 *      auth key is used and applies HostName/ControlURL overrides.
 *      Go's `startLocked` refuses to log in at all while
 *      prefs.LoggedOut is true, so this step is not optional.
 *   2. POST `start` with {"AuthKey": …, "UpdatePrefs": <the full
 *      Prefs object returned by step 1, with WantRunning set true>}.
 *      ipn.Options.UpdatePrefs is a complete *ipn.Prefs replacement,
 *      never a sparse overlay.
 *   3. POST `login-interactive`. Upstream documents this as
 *      "currently required for both interactive and non-interactive
 *      (using auth key) login": it is the only unconditional trigger
 *      of controlclient.Login on the control client that step 2
 *      created. (v0.1.0 skipped steps 1 and 3 and sent a sparse
 *      UpdatePrefs; the backend then sat in NeedsLogin with no error
 *      anywhere — Go only forwards vizerrors as Notify.ErrMessage,
 *      ordinary login failures are merely logged.)
 *   Connect/disconnect for an already-enrolled node stays a PATCH
 *   `prefs` MaskedPrefs WantRunning (a false->true transition makes
 *   Go call controlclient.Login itself); connect from NeedsLogin
 *   additionally posts `login-interactive`. resetEnrolment is POST
 *   `logout` + `reset-auth`. After the start chain, GET `status` is
 *   read once and any Health entries become a sticky diagnostic
 *   (see [stickyError]) so a silent Go-side stall stays visible.
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

    /**
     * An error/diagnostic that must survive Notify updates. Go only
     * forwards vizerrors as Notify.ErrMessage, so without this the UI
     * falls back to a bare, unexplained state (v0.1.0 showed a silent
     * NeedsLogin). Set by login-chain failures and by Health entries
     * in the post-chain status snapshot; cleared when the backend
     * reaches Running or a new start attempt begins.
     */
    @Volatile private var stickyError: String? = null

    /** Human-readable record of the last login chain round trip. */
    @Volatile private var lastStartDiagnostic: String? = null

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
                runLoginChain(application, config, authKey)
            } catch (e: Exception) {
                // Sticky: a later Notify must not wash this out (the
                // v0.1.0 failure mode was an error-free NeedsLogin).
                setStickyError(e.message ?: e.javaClass.simpleName)
                updateStatus(status.copy(state = TunnelState.Error, lastError = stickyError))
            }
        }
    }

    override fun connect() {
        executor.execute {
            val application = app ?: return@execute reportUnavailable()
            try {
                setWantRunning(application, true)
                if (status.state == TunnelState.NeedsLogin) {
                    // Same kick as login-chain step 3: from NeedsLogin
                    // a WantRunning transition alone may not restart
                    // the login attempt on the current control client.
                    callApiChecked(application, "POST", "login-interactive", null)
                }
            } catch (e: Exception) {
                setStickyError(e.message ?: e.javaClass.simpleName)
                updateStatus(status.copy(state = TunnelState.Error, lastError = stickyError))
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
                callApiChecked(application, "POST", "logout", null)
            } catch (_: Exception) {
                // logout may legitimately fail when never enrolled.
            }
            try {
                callApiChecked(application, "POST", "reset-auth", null)
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
        callApiChecked(application, "PATCH", "prefs", masked.toString().toByteArray(Charsets.UTF_8))
    }

    private data class ApiResult(val statusCode: Int, val body: String)

    /**
     * Upstream's login chain (IpnViewModel.login), mirrored step for
     * step; see the class doc. Any HTTP >= 400 or malformed response
     * aborts the chain with an exception naming the step, the status
     * code and the response body, and the caller makes it sticky.
     * After a successful chain, GET status is read once: ipnstate
     * Health entries (Go's own account of what is wrong, e.g. DNS or
     * network problems that never become a Notify ErrMessage) are
     * promoted to the sticky error so the stall is diagnosable from
     * the UI instead of only from logcat.
     */
    private fun runLoginChain(
        application: libtailscale.Application,
        config: TunnelConfig,
        authKey: String?,
    ) {
        stickyError = null
        val steps = StringBuilder()

        fun step(name: String, result: ApiResult) {
            if (steps.isNotEmpty()) steps.append(", ")
            steps.append(name).append(" -> HTTP ").append(result.statusCode)
            if (result.statusCode >= 400) {
                throw IllegalStateException(
                    "login chain failed at $name: HTTP ${result.statusCode}: " +
                        "${result.body.take(300)} (chain so far: $steps)",
                )
            }
        }

        // Step 1: editPrefs. Clears LoggedOut for auth-key logins
        // (Go's startLocked will not log in while LoggedOut is set)
        // and applies the HostName/ControlURL overrides. Deliberately
        // does NOT set WantRunning here: upstream warns that doing so
        // starts a login on the outgoing control client, which step 2
        // then tears down mid-flight.
        val mask = JSONObject()
        if (authKey != null) {
            mask.put("LoggedOut", false)
            mask.put("LoggedOutSet", true)
        }
        config.hostname?.let {
            mask.put("HostName", it)
            mask.put("HostNameSet", true)
        }
        config.loginServer?.let {
            mask.put("ControlURL", it)
            mask.put("ControlURLSet", true)
        }
        val editResult = callApi(
            application, "PATCH", "prefs",
            mask.toString().toByteArray(Charsets.UTF_8),
        )
        step("editPrefs", editResult)

        // Step 2: start, with UpdatePrefs = the full Prefs returned
        // by step 1 (ipn.Options.UpdatePrefs replaces the stored
        // Prefs wholesale), WantRunning set on it, plus the auth key.
        val newPrefs = try {
            JSONObject(editResult.body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "login chain failed at start: editPrefs returned no Prefs JSON " +
                    "(${e.message}); chain so far: $steps",
            )
        }
        newPrefs.put("WantRunning", true)
        val options = JSONObject().put("UpdatePrefs", newPrefs)
        if (authKey != null) options.put("AuthKey", authKey)
        step(
            "start",
            callApi(
                application, "POST", "start",
                options.toString().toByteArray(Charsets.UTF_8),
            ),
        )

        // Step 3: login-interactive — the unconditional
        // controlclient.Login trigger on the new control client;
        // upstream requires it for auth-key logins too.
        step("login-interactive", callApi(application, "POST", "login-interactive", null))

        // Post-chain diagnostic snapshot (not part of the chain: its
        // failure does not fail the login, it is only recorded).
        lastStartDiagnostic = try {
            val statusResult = callApi(application, "GET", "status", null)
            val statusJson = JSONObject(statusResult.body)
            val backendState = statusJson.optString("BackendState", "?")
            val health = statusJson.optJSONArray("Health")
            val healthTexts = if (health == null) {
                emptyList()
            } else {
                (0 until health.length()).map { health.optString(it) }.filter { it.isNotEmpty() }
            }
            val summary = "login chain ok ($steps); backend state: $backendState"
            if (healthTexts.isNotEmpty()) {
                stickyError = "$summary; health: ${healthTexts.joinToString("; ")}"
            }
            summary
        } catch (e: Exception) {
            "login chain ok ($steps); status snapshot failed: ${e.message}"
        }
    }

    /** One LocalAPI round trip; throws only on transport error. */
    private fun callApi(
        application: libtailscale.Application,
        method: String,
        path: String,
        body: ByteArray?,
    ): ApiResult {
        val response = application.callLocalAPI(
            LOCAL_API_TIMEOUT_MS, method, "/localapi/v0/$path",
            body?.let { ByteArrayInputStreamAdapter(it) },
        )
        val data = response.bodyBytes() ?: ByteArray(0)
        return ApiResult(response.statusCode().toInt(), data.toString(Charsets.UTF_8))
    }

    /** [callApi] for callers that treat HTTP >= 400 as a failure. */
    private fun callApiChecked(
        application: libtailscale.Application,
        method: String,
        path: String,
        body: ByteArray?,
    ): String {
        val result = callApi(application, method, path, body)
        if (result.statusCode >= 400) {
            throw IllegalStateException(
                "LocalAPI $method $path -> ${result.statusCode}: ${result.body}",
            )
        }
        return result.body
    }

    private fun setStickyError(message: String) {
        stickyError = message
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
        var merged = newStatus
        if (merged.state == TunnelState.Running) {
            stickyError = null
        } else if (merged.lastError == null && stickyError != null) {
            merged = merged.copy(lastError = stickyError)
        }
        status = merged
        listeners.forEach { it.onTunnelStatus(merged) }
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

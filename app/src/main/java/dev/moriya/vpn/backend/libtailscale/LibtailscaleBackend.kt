package dev.moriya.vpn.backend.libtailscale

import dev.moriya.vpn.tunnel.TunHost
import dev.moriya.vpn.tunnel.TunnelBackend
import dev.moriya.vpn.tunnel.TunnelConfig
import dev.moriya.vpn.tunnel.TunnelState
import dev.moriya.vpn.tunnel.TunnelStatus
import dev.moriya.vpn.tunnel.TunnelStatusListener
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Layer 3 — the libtailscale adapter. STUB.
 *
 * This package is the ONLY place in the app allowed to import
 * `libtailscale.*` (DESIGN.md §2; enforced by a grep check). The stub
 * below therefore contains NO libtailscale imports yet: until the CI
 * AAR step (DESIGN.md §6) produces app/libs/libtailscale.aar and the
 * TODOs here are filled in, every operation honestly reports
 * [TunnelState.BackendUnavailable]. It does not fake a working VPN:
 * no TUN is established, no addresses are invented, and connect()
 * changes nothing except the reported status.
 *
 * Integration TODOs, in the order they must be done (references are to
 * upstream ~/workspace/tailscale-android-src; full contract in
 * DESIGN.md §3):
 *
 *  1. `init`: call `Libtailscale.start(dataDir, directFileRoot,
 *     hwAttestationPref=false, appContext)` exactly once and keep the
 *     returned `libtailscale.Application`. appContext is
 *     [LibtailscaleAppContext] built from the [LibtailscaleAppServices]
 *     passed in. Start must happen at process start, before any VPN
 *     request, so backend sockets exist before RequestVPN (upstream
 *     relies on a magicsock rebind at request time — backend.go).
 *  2. Subscribe: `application.watchNotifications(MASK) { bytes -> … }`
 *     with MASK = 4|2|4096|8192|16384|128 (Prefs|InitialState|
 *     PeerChanges|NoNetmap|InitialStatus|InitialHealthState — upstream
 *     Notifier.kt), parse the ipn.Notify JSON with Layer-3-local
 *     minimal models (do NOT copy upstream's ui/model package), map
 *     State/Prefs/NetMap onto TunnelStatus, notify listeners.
 *  3. `start(config)`: LocalAPI POST `start` with body
 *     {"AuthKey": …, "UpdatePrefs": {…}} (upstream Client.start /
 *     Ipn.Options) via application.callLocalAPI(timeout, "POST",
 *     "/localapi/v0/start", InputStreamAdapter(body)), then connect().
 *  4. `connect`/`disconnect`: LocalAPI PATCH `prefs` with
 *     MaskedPrefs WantRunning true/false (upstream App.setWantRunning).
 *  5. `onVpnServiceRequested(host)`: wrap host in
 *     [LibtailscaleIPNService] and call `Libtailscale.requestVPN(it)`.
 *     `onVpnServiceDisconnected(id)`: `Libtailscale.serviceDisconnect`.
 *  6. `onNetworkChanged`: `Libtailscale.onDNSConfigChanged(ifname)` /
 *     `Libtailscale.onGatewayChanged(gateway)` when non-null.
 *  7. `resetEnrolment`: LocalAPI POST `logout`, then `reset-auth`
 *     if a fresh enrolment still sees the old state.
 *  8. Guard everything above on the AAR actually being present
 *     (Class.forName("libtailscale.Libtailscale")) so stub builds —
 *     notably the x86_64 emulator launch test — keep reporting
 *     BackendUnavailable instead of crashing with NoClassDefFoundError.
 */
class LibtailscaleBackend : TunnelBackend {

    override val backendName: String = "libtailscale (not integrated)"

    private val listeners = CopyOnWriteArraySet<TunnelStatusListener>()

    @Volatile
    private var status = TunnelStatus(
        state = TunnelState.BackendUnavailable,
        backendName = backendName,
        lastError = "libtailscale AAR not integrated yet (Layer 3 stub)",
    )

    private var services: LibtailscaleAppServices? = null

    override fun init(appServices: Any) {
        services = appServices as? LibtailscaleAppServices
            ?: error("LibtailscaleBackend requires LibtailscaleAppServices")
        // TODO(1): Libtailscale.start(...) — see class doc.
    }

    override fun start(config: TunnelConfig) {
        // TODO(3): LocalAPI start with config.authKey — see class doc.
        // The auth key must never be logged; it is intentionally not
        // even copied into the stub status.
        notifyListeners()
    }

    override fun connect() {
        // TODO(4): MaskedPrefs WantRunning = true.
        notifyListeners()
    }

    override fun disconnect() {
        // TODO(4): MaskedPrefs WantRunning = false.
        notifyListeners()
    }

    override fun resetEnrolment() {
        // TODO(7): LocalAPI logout / reset-auth.
        notifyListeners()
    }

    override fun currentStatus(): TunnelStatus = status

    override fun addListener(listener: TunnelStatusListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TunnelStatusListener) {
        listeners.remove(listener)
    }

    override fun onVpnServiceRequested(host: TunHost) {
        // TODO(5): Libtailscale.requestVPN(LibtailscaleIPNService(host)).
        host.onVpnStatusChanged(false)
    }

    override fun onVpnServiceDisconnected(hostId: String) {
        // TODO(5): Libtailscale.serviceDisconnect(for hostId).
    }

    override fun onNetworkChanged(interfaceName: String?, gatewayAddress: String?) {
        // TODO(6): Libtailscale.onDNSConfigChanged / onGatewayChanged.
    }

    private fun notifyListeners() {
        val snapshot = status
        listeners.forEach { it.onTunnelStatus(snapshot) }
    }
}

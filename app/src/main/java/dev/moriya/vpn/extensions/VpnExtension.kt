package dev.moriya.vpn.extensions

import android.content.Context
import android.net.Network
import dev.moriya.vpn.tunnel.TunnelStatus

/**
 * Extensions — app-side features hosted by the VPN service (v1.0.0,
 * DESIGN.md §9).
 *
 * The founding reason this app exists (Moriya, 2026-11-15): the stock
 * VPN app cannot carry logic of our own, while this app — designated
 * as the always-on VPN, so the system revives it — can host small
 * features next to the tunnel: health watching, startup management,
 * a proxy, and whatever comes next. Extensions are how those features
 * are added without touching the tunnel layers.
 *
 * Layering: this package is app-side (Layers 1–2 territory). It sees
 * the backend only as [dev.moriya.vpn.tunnel.TunnelBackend] status —
 * never a Layer 3 type, never libtailscale.
 */

/** Static description of one built-in extension. */
data class ExtensionInfo(
    /** Stable id; used for the persisted enabled flag. Never rename. */
    val id: String,
    val displayName: String,
    val defaultEnabled: Boolean,
    /** One-line explanation shown under the switch in the UI. */
    val description: String,
)

/**
 * One extension. Implementations must be cheap to construct (the
 * manager builds all of them) and do their work only between
 * [start] and [stop]. All methods may be called from arbitrary
 * threads; implementations keep their own state thread-safe.
 */
interface VpnExtension {
    val info: ExtensionInfo

    /** Begin work. [context] is the host service's application context. */
    fun start(context: Context)

    /** Stop work and release timers/threads. Safe to call when not started. */
    fun stop()

    /**
     * The tunnel backend's status changed (state, addresses, DNS
     * flags — Layer 2 terms only). Delivered only while started.
     */
    fun onTunnelStatus(status: TunnelStatus) {}

    /** One-line status for the UI. Safe to call started or not. */
    fun statusText(): String
}

/** Registry of the built-in extensions, in UI display order. */
object Extensions {

    val HEALTH = ExtensionInfo(
        id = "health",
        displayName = "Health check",
        // Pure observation; on by default so the watcher half of the
        // helper exists even before startup management is opted into.
        defaultEnabled = true,
        description = "Polls the node stack's health endpoint on this " +
            "device every 60 s and records the result.",
    )

    val STARTUP_MANAGEMENT = ExtensionInfo(
        id = "startup-management",
        displayName = "Startup management",
        // Acts on another app (starts phone-node), so it is opt-in.
        defaultEnabled = false,
        description = "When the health check keeps failing, restarts " +
            "phone-node; also starts it once when the tunnel connects.",
    )

    val PROXY = ExtensionInfo(
        id = "proxy",
        displayName = "Proxy",
        // Opens a listening socket, so it is opt-in like startup
        // management.
        defaultEnabled = false,
        description = "Forward proxy on this node's tailnet address " +
            "(port 8080) for Moriya's own devices; traffic leaves " +
            "through this phone's network.",
    )

    val ALL: List<ExtensionInfo> = listOf(HEALTH, STARTUP_MANAGEMENT, PROXY)

    /**
     * Build the extension instances. [healthResults] is the shared
     * channel between them: the Health extension publishes into it,
     * Startup management consumes from it (wired by the host, per
     * the v1.0.0 design). [underlyingNetwork] supplies the host's
     * current non-VPN network (Layer 1's DefaultNetworkTracker pick,
     * passed through by the manager) for extensions whose sockets
     * must egress on the physical network — the Proxy's outbound
     * connections; it is a Layer 1 facility, not a backend type.
     */
    fun createAll(
        healthResults: HealthResults,
        underlyingNetwork: () -> Network?,
    ): List<VpnExtension> = listOf(
        HealthExtension(HEALTH, healthResults),
        StartupManagementExtension(STARTUP_MANAGEMENT, healthResults),
        ProxyExtension(PROXY, underlyingNetwork),
    )
}

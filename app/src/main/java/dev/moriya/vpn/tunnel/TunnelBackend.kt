package dev.moriya.vpn.tunnel

/**
 * Layer 2 — the tunnel contract. See DESIGN.md §2 and §4.
 *
 * Everything in this package is ours: no Android types, no backend
 * types, no backend wire formats. Layer 1 (UI + VpnService shell) talks
 * only to [TunnelBackend]; Layer 3 implements it. Replacing the backend
 * (libtailscale today; WireGuard, tsnet, or a fork tomorrow) means
 * writing a new Layer 3 and changing one line at the composition root
 * (MokaVpnApp). Nothing here changes.
 */

/** Enrolment + start input, in our terms. */
data class TunnelConfig(
    /** Reusable auth key for unattended enrolment. Null = already enrolled, just start. */
    val authKey: String? = null,
    /** Device hostname to register as. Null = backend/device default. */
    val hostname: String? = null,
    /**
     * Control-server base URL. Null = the backend's default
     * (login.tailscale.com for libtailscale). Present from day one so a
     * Headscale or other control server is a config change, not an
     * architecture change.
     */
    val loginServer: String? = null,
)

/** Backend-independent tunnel state. UI never sees native state values. */
enum class TunnelState {
    Stopped,
    NeedsLogin,
    Starting,
    Running,
    Stopping,

    /** No real backend is integrated (Layer 3 stub, or AAR missing). */
    BackendUnavailable,
    Error,
}

data class TunnelStatus(
    val state: TunnelState,
    /** Own tunnel addresses (e.g. the tailnet 100.x address), empty until Running. */
    val selfAddresses: List<String> = emptyList(),
    /** Human-readable backend id, e.g. "libtailscale (not integrated)". */
    val backendName: String,
    /** Null when the backend cannot cheaply provide a peer count. */
    val peerCount: Int? = null,
    val lastError: String? = null,
    /**
     * Whether the backend installs the tailnet's DNS configuration
     * on the OS (libtailscale: prefs CorpDNS, the stock app's
     * "Use Tailscale DNS" setting). Null until the backend reports
     * it. When false the VPN interface gets no DNS servers and the
     * platform resolver — including Android Private DNS (DoT) —
     * keeps working on the underlying network; the cost is that
     * tailnet DNS (MagicDNS names, tailnet resolvers) is not used.
     */
    val useTailscaleDns: Boolean? = null,
)

interface TunnelStatusListener {
    fun onTunnelStatus(status: TunnelStatus)
}

/**
 * The host side of a TUN establishment, implemented by Layer 1's
 * VpnService shell. This mirrors the *shape* of Android's
 * VpnService/VpnService.Builder so an adapter can drive it, without
 * exposing any backend type: [TunSession] is our descriptor, not a
 * backend ParcelFileDescriptor.
 */
interface TunHost {
    /** Unique id per VpnService instance (a new instance = a new id). */
    val hostId: String

    /** Keep [fd] out of the tunnel (VpnService.protect). */
    fun protect(fd: Int): Boolean

    /**
     * Bind [fd] to the current default (underlying) network, for
     * backends whose sockets must follow the physical network rather
     * than the system default route. Default: unsupported.
     */
    fun bindSocketToNetwork(fd: Int): Boolean = false

    /** A fresh, single-use builder for one TUN establishment. */
    fun newTunBuilder(): TunBuilder

    /** The VPN is gone from the system's point of view; stop the service. */
    fun onVpnDisconnected()

    /** Publish VPN-active state for UI/notification purposes. */
    fun onVpnStatusChanged(active: Boolean)
}

interface TunBuilder {
    fun setMtu(mtu: Int)
    fun addAddress(address: String, prefixLength: Int)
    fun addDnsServer(address: String)
    fun addSearchDomain(domain: String)
    fun addRoute(address: String, prefixLength: Int)

    /** Backends must tolerate this being a no-op below API 33. */
    fun excludeRoute(address: String, prefixLength: Int)

    /** Establish the TUN; null if the system refused. */
    fun establish(): TunSession?
}

interface TunSession {
    /** Detach and return the raw TUN file descriptor. */
    fun detachFd(): Int
}

/**
 * One tunnel backend. All methods are called on arbitrary threads by
 * Layer 1; implementations must be thread-safe and must deliver status
 * changes through registered listeners rather than return values
 * (backends are asynchronous by nature).
 */
interface TunnelBackend {
    val backendName: String

    /**
     * One-time backend initialisation for this process (data dir,
     * stores). Called once by the composition root before any other
     * method. [appServices] is the adapter's bridge to Android-provided
     * facilities; it is typed as Any at this layer on purpose — each
     * Layer 3 defines its own expected bridge interface and Layer 1
     * never constructs one directly (the composition root does).
     */
    fun init(appServices: Any)

    /**
     * Enrol (if [TunnelConfig.authKey] is set) and ask the backend to
     * run. The TUN itself only appears after the system starts our
     * VpnService and [onVpnServiceRequested] is called.
     */
    fun start(config: TunnelConfig)

    /** Ask an enrolled backend to run (WantRunning = true). */
    fun connect()

    /** Ask the backend to stop (WantRunning = false) and tear the TUN down. */
    fun disconnect()

    /** Forget enrolment (node key / auth state), for re-enrolment. */
    fun resetEnrolment()

    /**
     * Set whether the tailnet's DNS configuration is installed on
     * the OS (see [TunnelStatus.useTailscaleDns]). Mirrors the stock
     * app's "Use Tailscale DNS" setting; takes effect without a
     * reconnect. Default: ignore (backends without the concept).
     */
    fun setUseTailscaleDns(enabled: Boolean) {}

    fun currentStatus(): TunnelStatus

    fun addListener(listener: TunnelStatusListener)
    fun removeListener(listener: TunnelStatusListener)

    // -- VpnService handshake (called by Layer 1's service shell) --

    /** The system/user started our VpnService; hand the host to the backend. */
    fun onVpnServiceRequested(host: TunHost)

    /** Our VpnService is going away. */
    fun onVpnServiceDisconnected(hostId: String)

    /** Default network changed; backends typically need ifname + gateway. */
    fun onNetworkChanged(interfaceName: String?, gatewayAddress: String?)

    /**
     * The platform DNS configuration string for the default network
     * changed (format is backend-defined; libtailscale expects
     * "dns1 dns2 …\nsearch-domains", upstream DnsConfig). Default:
     * ignore.
     */
    fun onPlatformDnsConfigChanged(config: String) {}
}

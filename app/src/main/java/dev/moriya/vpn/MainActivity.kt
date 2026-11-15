package dev.moriya.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.moriya.vpn.extensions.ExtensionManager
import dev.moriya.vpn.extensions.ExtensionSettings
import dev.moriya.vpn.extensions.Extensions
import dev.moriya.vpn.service.MokaVpnService
import dev.moriya.vpn.service.VpnTunnelState
import dev.moriya.vpn.tunnel.TunnelBackend
import dev.moriya.vpn.tunnel.TunnelConfig
import dev.moriya.vpn.tunnel.TunnelStatus
import dev.moriya.vpn.tunnel.TunnelStatusListener

/**
 * Layer 1 — the one screen: auth-key entry, connect/disconnect,
 * backend session state + own addresses, the OS tunnel state as a
 * separate line (the two can diverge; v1.0.0 state-divergence fix),
 * the DNS switches, and the Extensions section (v1.0.0, DESIGN.md
 * §9). Framework widgets only, mirroring apps-updater's
 * no-dependency style. All backend contact goes through
 * [TunnelBackend]; this file must never import a backend package's
 * types beyond what MokaVpnApp exposes.
 */
class MainActivity : Activity(), TunnelStatusListener {

    private lateinit var statusView: TextView
    private lateinit var authKeyInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var dnsSwitch: Switch
    private lateinit var adguardSwitch: Switch

    /** True while [onTunnelStatus] syncs the switch from backend state. */
    private var syncingDnsSwitch = false

    /** True while [onTunnelStatus] syncs the AdGuard switch from status. */
    private var syncingAdguardSwitch = false

    /** Latest backend status, kept so the tunnel line can re-render. */
    @Volatile private var lastStatus: TunnelStatus? = null

    private val extensionSettings: ExtensionSettings by lazy { ExtensionSettings(this) }
    private val extensionSwitches = HashMap<String, Switch>()
    private val extensionStatusViews = HashMap<String, TextView>()

    /** True while [refreshExtensions] syncs switches from settings/manager. */
    private var syncingExtensionSwitches = false

    /** The manager instance [managerUiListener] is registered on, if any. */
    private var observedManager: ExtensionManager? = null
    private val managerUiListener: () -> Unit = { runOnUiThread { refreshExtensions() } }
    private val tunnelStateListener: (Boolean) -> Unit = { runOnUiThread { renderStatus() } }

    /** Own version as installed, from the package manager (not hardcoded). */
    private val appVersionName: String by lazy {
        try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    private val backend: TunnelBackend get() = (application as MokaVpnApp).backend

    /** Set when a connect tap is waiting for the VPN permission result. */
    private var pendingAuthKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
        })
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 32, 0, 32)
        }
        root.addView(statusView)

        root.addView(TextView(this).apply { text = "Tailscale auth key (reusable)" })
        authKeyInput = EditText(this).apply {
            hint = HINT_NO_SAVED_KEY
            // Password-style field: the key is a credential. It is never
            // logged and never leaves the device except to the control
            // server via the backend. Once a key is saved, the field is
            // kept empty (see updateAuthKeyHint): an empty field on
            // Connect means "use the saved key", and the stored key is
            // never written back into the field.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(authKeyInput)

        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { requestVpnAndStart() }
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, MokaVpnService::class.java)
                        .setAction(MokaVpnService.ACTION_STOP_VPN),
                )
                backend.disconnect()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(connectButton)
            addView(disconnectButton)
        }
        root.addView(buttons)

        // The stock app's "Use Tailscale DNS" setting. Off = the VPN
        // pushes no DNS servers, so Android Private DNS (DoT) keeps
        // working on the underlying network; MagicDNS names and
        // tailnet resolvers are then not used.
        dnsSwitch = Switch(this).apply {
            text = "Use Tailscale DNS"
            isEnabled = false
            setPadding(0, 32, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                if (!syncingDnsSwitch) backend.setUseTailscaleDns(checked)
            }
        }
        root.addView(dnsSwitch)
        root.addView(TextView(this).apply {
            text = "Off: Android Private DNS (DoT) works; MagicDNS / " +
                "tailnet DNS is not used. Same setting as the stock app."
            textSize = 13f
        })

        // Host-side AdGuard DNS mode: the VPN's TUN is established
        // with AdGuard's public DNS servers instead of any
        // backend-pushed ones (it forces "Use Tailscale DNS" off;
        // the two are mutually exclusive). Applies at TUN
        // (re)establishment, so a toggle while connected takes
        // effect on the next reconnect.
        adguardSwitch = Switch(this).apply {
            text = "AdGuard DNS"
            setPadding(0, 32, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                if (!syncingAdguardSwitch) backend.setAdguardDns(checked)
            }
        }
        root.addView(adguardSwitch)
        root.addView(TextView(this).apply {
            text = "On: this VPN uses AdGuard DNS (ads/trackers " +
                "filtered). Applies when the tunnel (re)connects. " +
                "With it on, set Android Private DNS to Automatic."
            textSize = 13f
        })

        // Extensions (v1.0.0): features hosted by the VPN service.
        // Rows are built from the static registry so they render —
        // and their persisted switches work — even while the VPN
        // service is stopped; live status comes from the service's
        // ExtensionManager when it exists.
        root.addView(TextView(this).apply {
            text = "Extensions"
            textSize = 18f
            setPadding(0, 48, 0, 0)
        })
        for (info in Extensions.ALL) {
            val switch = Switch(this).apply {
                text = info.displayName
                setPadding(0, 24, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    if (!syncingExtensionSwitches) {
                        val manager = MokaVpnService.extensions
                        if (manager != null) {
                            manager.setEnabled(info.id, checked)
                        } else {
                            extensionSettings.setEnabled(info.id, checked)
                        }
                        refreshExtensions()
                    }
                }
            }
            extensionSwitches[info.id] = switch
            root.addView(switch)
            root.addView(TextView(this).apply {
                text = info.description
                textSize = 13f
            })
            val statusLine = TextView(this).apply { textSize = 13f }
            extensionStatusViews[info.id] = statusLine
            root.addView(statusLine)
        }

        setContentView(ScrollView(this).apply { addView(root) })

        backend.addListener(this)
        VpnTunnelState.addListener(tunnelStateListener)
        onTunnelStatus(backend.currentStatus())
        refreshExtensions()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        refreshExtensions()
    }

    override fun onDestroy() {
        backend.removeListener(this)
        VpnTunnelState.removeListener(tunnelStateListener)
        observedManager?.removeUiListener(managerUiListener)
        observedManager = null
        super.onDestroy()
    }

    override fun onTunnelStatus(status: TunnelStatus) {
        lastStatus = status
        runOnUiThread {
            renderStatus()
            updateAuthKeyHint(status)
            status.useTailscaleDns?.let { enabled ->
                syncingDnsSwitch = true
                dnsSwitch.isChecked = enabled
                dnsSwitch.isEnabled = true
                syncingDnsSwitch = false
            }
            syncingAdguardSwitch = true
            adguardSwitch.isChecked = status.adguardDns
            syncingAdguardSwitch = false
            refreshExtensions()
        }
    }

    /** Compose the status block from the latest backend status + tunnel holder. */
    private fun renderStatus() {
        val status = lastStatus ?: return
        statusView.text = buildString {
            append("Version: ").append(appVersionName).append('\n')
            append("Backend: ").append(status.backendName).append('\n')
            append("State: ").append(status.state.name).append('\n')
            // The OS tunnel state, shown separately from the backend
            // session state above: the two diverged silently before
            // v1.0.0 (session Running, no TUN), so neither line is
            // derived from the other.
            append("Tunnel: ").append(if (VpnTunnelState.isUp) "up" else "down").append('\n')
            if (status.selfAddresses.isNotEmpty()) {
                append("Addresses: ").append(status.selfAddresses.joinToString(", ")).append('\n')
            }
            status.peerCount?.let { append("Peers: ").append(it).append('\n') }
            status.lastError?.let { append("Error: ").append(it) }
        }
    }

    /**
     * Mask the auth-key field (v1.0.0): when a key is saved on the
     * device, say so in the hint and never put the key itself in the
     * field. An empty field on Connect uses the saved key (the
     * backend reuses it); typing a new key replaces it.
     */
    private fun updateAuthKeyHint(status: TunnelStatus) {
        authKeyInput.hint = if (status.hasSavedAuthKey) HINT_SAVED_KEY else HINT_NO_SAVED_KEY
    }

    /** Rebind to the live manager (if the service restarted) and re-render rows. */
    private fun refreshExtensions() {
        val manager = MokaVpnService.extensions
        if (manager !== observedManager) {
            observedManager?.removeUiListener(managerUiListener)
            observedManager = manager
            manager?.addUiListener(managerUiListener)
        }
        val snapshot = manager?.snapshot()
        syncingExtensionSwitches = true
        for (info in Extensions.ALL) {
            val row = snapshot?.firstOrNull { it.info.id == info.id }
            extensionSwitches[info.id]?.isChecked =
                row?.enabled ?: extensionSettings.isEnabled(info)
            extensionStatusViews[info.id]?.text = when {
                row != null -> row.statusText
                extensionSettings.isEnabled(info) -> "enabled · VPN service not running"
                else -> "off"
            }
        }
        syncingExtensionSwitches = false
    }

    private fun requestVpnAndStart() {
        pendingAuthKey = authKeyInput.text.toString().trim().ifEmpty { null }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(prepare, REQUEST_VPN_PERMISSION)
        } else {
            startVpnService()
        }
    }

    @Deprecated("Activity result for the one-shot VPN permission prompt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun startVpnService() {
        // Enrol/start first (Layer 2), then hand the system our
        // VpnService; the backend receives the service via
        // MokaVpnService.onStartCommand -> onVpnServiceRequested.
        backend.start(TunnelConfig(authKey = pendingAuthKey))
        pendingAuthKey = null
        // The key (if one was typed) is now persisted by the backend;
        // clear the field so the credential does not sit in the UI.
        authKeyInput.text.clear()
        startForegroundService(
            Intent(this, MokaVpnService::class.java).setAction(MokaVpnService.ACTION_START_VPN),
        )
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 1
        // Deliberately free of the auth-key prefix literal: the
        // release security scan greps the APK and the repo for that
        // prefix and must find zero matches, proving no key material
        // ships. A placeholder that merely starts with the prefix
        // would trip it.
        private const val HINT_NO_SAVED_KEY =
            "Auth key (leave empty if already enrolled)"
        private const val HINT_SAVED_KEY =
            "Key saved on device — leave empty to use it, or enter a new key"
    }
}

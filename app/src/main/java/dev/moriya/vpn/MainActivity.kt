package dev.moriya.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import dev.moriya.vpn.extensions.AdbControlProtocol
import dev.moriya.vpn.extensions.AdbKeepaliveLogic
import dev.moriya.vpn.extensions.ExtensionManager
import dev.moriya.vpn.extensions.ExtensionSettings
import dev.moriya.vpn.extensions.Extensions
import dev.moriya.vpn.extensions.SpeedMeterLogic
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
 * the live debug-toggle states (v1.5.0), the DNS switches, and the
 * Extensions section (v1.0.0, DESIGN.md §9) with its per-extension
 * extra controls (the ADB card's USB-debugging switch and the
 * speed meter's interval dropdown, both v1.5.0).
 *
 * Layout (v1.5.0): card-style sections with one hierarchy —
 * section heading, then rows of switch / status / note — instead
 * of the former flat list, which had grown hard to scan. Status
 * texts are selectable, so a line (e.g. the ADB control token)
 * can be copied off the screen; that was impossible before and
 * forced a screenshot handover on 2026-11-16.
 *
 * Framework widgets only, mirroring apps-updater's no-dependency
 * style. All backend contact goes through [TunnelBackend]; this
 * file must never import a backend package's types beyond what
 * MokaVpnApp exposes.
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

    // -- v1.5.0 per-extension extra controls --
    private lateinit var usbDebugSwitch: Switch

    /** True while the USB switch is synced from the actual setting. */
    private var syncingUsbSwitch = false
    private lateinit var speedIntervalSpinner: Spinner

    /** True while the interval spinner is synced from the settings. */
    private var syncingIntervalSpinner = false

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
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
        })

        // -- Status card: version, backend/session state, the OS
        // tunnel line, addresses, and (v1.5.0) the live debug
        // toggles. Selectable, so any line can be copied. --
        val statusCard = addCard(root, "Status")
        statusView = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        statusCard.addView(statusView)

        // -- Connection card: auth key + connect/disconnect. --
        val connectionCard = addCard(root, "Connection")
        connectionCard.addView(TextView(this).apply {
            text = "Tailscale auth key (reusable)"
        })
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
        connectionCard.addView(authKeyInput)

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
        connectionCard.addView(buttons)

        // -- DNS card: the two mutually exclusive DNS modes. --
        val dnsCard = addCard(root, "DNS")

        // The stock app's "Use Tailscale DNS" setting. Off = the VPN
        // pushes no DNS servers, so Android Private DNS (DoT) keeps
        // working on the underlying network; MagicDNS names and
        // tailnet resolvers are then not used.
        dnsSwitch = Switch(this).apply {
            text = "Use Tailscale DNS"
            isEnabled = false
            setOnCheckedChangeListener { _, checked ->
                if (!syncingDnsSwitch) backend.setUseTailscaleDns(checked)
            }
        }
        dnsCard.addView(dnsSwitch)
        dnsCard.addView(noteText(
            "Off: Android Private DNS (DoT) works; MagicDNS / " +
                "tailnet DNS is not used. Same setting as the stock app.",
        ))

        // Host-side AdGuard DNS mode: the VPN's TUN is established
        // with AdGuard's public DNS servers instead of any
        // backend-pushed ones (it forces "Use Tailscale DNS" off;
        // the two are mutually exclusive). Applies at TUN
        // (re)establishment, so a toggle while connected takes
        // effect on the next reconnect.
        adguardSwitch = Switch(this).apply {
            text = "AdGuard DNS"
            setPadding(0, 24, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                if (!syncingAdguardSwitch) backend.setAdguardDns(checked)
            }
        }
        dnsCard.addView(adguardSwitch)
        dnsCard.addView(noteText(
            "On: this VPN uses AdGuard DNS (ads/trackers " +
                "filtered). Applies when the tunnel (re)connects. " +
                "With it on, set Android Private DNS to Automatic.",
        ))

        // -- Extensions: one card per built-in. Rows are built from
        // the static registry so they render — and their persisted
        // switches work — even while the VPN service is stopped;
        // live status comes from the service's ExtensionManager
        // when it exists. --
        root.addView(TextView(this).apply {
            text = "Extensions"
            textSize = 19f
            setPadding(8, 40, 0, 4)
        })
        for (info in Extensions.ALL) {
            val card = addCard(root, null)
            val switch = Switch(this).apply {
                text = info.displayName
                textSize = 16f
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
            card.addView(switch)
            card.addView(noteText(info.description))
            val statusLine = TextView(this).apply {
                textSize = 13f
                setTextIsSelectable(true)
                setPadding(0, 8, 0, 0)
            }
            extensionStatusViews[info.id] = statusLine
            card.addView(statusLine)
            when (info.id) {
                Extensions.ADB_KEEPALIVE.id -> buildAdbExtras(card)
                Extensions.SPEED_METER.id -> buildSpeedMeterExtras(card)
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })

        backend.addListener(this)
        VpnTunnelState.addListener(tunnelStateListener)
        onTunnelStatus(backend.currentStatus())
        refreshExtensions()
    }

    /**
     * The ADB card's extra control (v1.5.0): a switch that reads
     * and writes Settings.Global `adb_enabled` directly. This is
     * the owner's own in-app action, using the same
     * WRITE_SECURE_SETTINGS grant the extension needs — the
     * in-person counterpart of the VM control endpoint's on/off
     * commands (§9.7). The keepalive watcher's separation is
     * untouched: no automatic logic writes this key; the three
     * putInt sites for the adb keys are the watcher (wireless
     * key only), the control endpoint, and this switch.
     *
     * The displayed state is the actual setting, re-read on every
     * UI refresh, so a change made from the VM shows up here.
     */
    private fun buildAdbExtras(card: LinearLayout) {
        usbDebugSwitch = Switch(this).apply {
            text = "USB debugging"
            setPadding(0, 24, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                if (!syncingUsbSwitch) setUsbDebugging(checked)
            }
        }
        card.addView(usbDebugSwitch)
        card.addView(noteText(
            "Turns USB debugging (adb_enabled) on/off directly — " +
                "the same setting the VM control endpoint writes. " +
                "Many apps refuse to start while it is on.",
        ))
    }

    /**
     * The speed meter card's extra control (v1.5.0): the poll
     * interval as a dropdown of presets (1/2/3/5/10 s, default
     * 1 s), persisted with the extension settings. The running
     * extension reads the value fresh on every tick, so a change
     * applies live with no restart.
     */
    private fun buildSpeedMeterExtras(card: LinearLayout) {
        card.addView(TextView(this).apply {
            text = "Update interval"
            setPadding(0, 24, 0, 0)
        })
        val labels = SpeedMeterLogic.INTERVAL_PRESETS_SECONDS.map { "$it s" }
        speedIntervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                labels,
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (!syncingIntervalSpinner) {
                        extensionSettings.setSpeedMeterIntervalSeconds(
                            SpeedMeterLogic.INTERVAL_PRESETS_SECONDS[position],
                        )
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        card.addView(speedIntervalSpinner)
    }

    /** Write `adb_enabled` from the UI switch; a refusal reverts the row. */
    private fun setUsbDebugging(on: Boolean) {
        val written = try {
            Settings.Global.putInt(
                contentResolver,
                AdbControlProtocol.USB_SETTING_KEY,
                if (on) 1 else 0,
            )
        } catch (_: Exception) {
            // SecurityException when the grant is not held (the ADB
            // card's status line shows the grant command), or a
            // provider error. Never crash the screen over it.
            false
        }
        if (!written) {
            // Re-sync from the actual value so the switch never
            // claims a state the device is not in.
            syncUsbSwitch()
        }
        renderStatus()
    }

    /** Sync the USB switch from the actual `adb_enabled` value. */
    private fun syncUsbSwitch() {
        if (!::usbDebugSwitch.isInitialized) return
        syncingUsbSwitch = true
        usbDebugSwitch.isChecked =
            readGlobalSetting(AdbControlProtocol.USB_SETTING_KEY) == 1
        syncingUsbSwitch = false
    }

    /** Sync the interval spinner from the persisted setting. */
    private fun syncIntervalSpinner() {
        if (!::speedIntervalSpinner.isInitialized) return
        val index = SpeedMeterLogic.INTERVAL_PRESETS_SECONDS
            .indexOf(extensionSettings.speedMeterIntervalSeconds())
        if (index >= 0) {
            syncingIntervalSpinner = true
            speedIntervalSpinner.setSelection(index)
            syncingIntervalSpinner = false
        }
    }

    /** Read a Settings.Global int; null when unreadable. */
    private fun readGlobalSetting(key: String): Int? = try {
        Settings.Global.getInt(contentResolver, key)
    } catch (_: Exception) {
        // SettingNotFoundException (never written on this device)
        // or a provider hiccup: unreadable, shown as "unknown".
        null
    }

    private fun debugStateText(value: Int?): String = when (value) {
        1 -> "on"
        0 -> "off"
        null -> "unknown"
        else -> value.toString()
    }

    // -- card scaffolding (v1.5.0) --

    /**
     * Append a card to [root] and return its content container.
     * A card is a vertical layout on a rounded, theme-floating
     * background with an optional heading — the one visual unit
     * every section of this screen is built from.
     */
    private fun addCard(root: LinearLayout, heading: String?): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 24 }
        }
        if (heading != null) {
            card.addView(TextView(this).apply {
                text = heading
                textSize = 17f
                setPadding(0, 0, 0, 12)
            })
        }
        root.addView(card)
        return card
    }

    private fun cardBackground(): GradientDrawable {
        val attrs = theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorBackgroundFloating),
        )
        val color = try {
            attrs.getColor(0, Color.GRAY)
        } finally {
            attrs.recycle()
        }
        return GradientDrawable().apply {
            cornerRadius = 24f
            setColor(color)
        }
    }

    /** A small explanatory note, the bottom element of a card row. */
    private fun noteText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 8, 0, 0)
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
            // The debug toggles (v1.5.0), read live from
            // Settings.Global on every render: whether adbd can be
            // running at all is decided by these two values, and
            // "5555 is not answering" is most often one of them
            // having dropped — not a tunnel problem.
            append("Wireless debug: ")
                .append(debugStateText(readGlobalSetting(AdbKeepaliveLogic.SETTING_KEY)))
                .append('\n')
            append("USB debug: ")
                .append(debugStateText(readGlobalSetting(AdbControlProtocol.USB_SETTING_KEY)))
                .append('\n')
            if (status.selfAddresses.isNotEmpty()) {
                append("Addresses: ").append(status.selfAddresses.joinToString(", ")).append('\n')
            }
            status.peerCount?.let { append("Peers: ").append(it).append('\n') }
            status.lastError?.let { append("Error: ").append(it) }
        }
        syncUsbSwitch()
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
        syncUsbSwitch()
        syncIntervalSpinner()
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

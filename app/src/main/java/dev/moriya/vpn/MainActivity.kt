package dev.moriya.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.moriya.vpn.service.MokaVpnService
import dev.moriya.vpn.tunnel.TunnelBackend
import dev.moriya.vpn.tunnel.TunnelConfig
import dev.moriya.vpn.tunnel.TunnelStatus
import dev.moriya.vpn.tunnel.TunnelStatusListener

/**
 * Layer 1 — the one v0.1 screen (DESIGN.md §5): auth-key entry,
 * connect/disconnect, state + own addresses. Framework widgets only,
 * mirroring apps-updater's no-dependency style. All backend contact
 * goes through [TunnelBackend]; this file must never import a backend
 * package's types beyond what MokaVpnApp exposes.
 */
class MainActivity : Activity(), TunnelStatusListener {

    private lateinit var statusView: TextView
    private lateinit var authKeyInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

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
            hint = "tskey-auth-… (leave empty if already enrolled)"
            // Password-style field: the key is a credential. It is never
            // logged and never leaves the device except to the control
            // server via the backend.
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
        setContentView(root)

        backend.addListener(this)
        onTunnelStatus(backend.currentStatus())
    }

    override fun onDestroy() {
        backend.removeListener(this)
        super.onDestroy()
    }

    override fun onTunnelStatus(status: TunnelStatus) {
        runOnUiThread {
            statusView.text = buildString {
                append("Backend: ").append(status.backendName).append('\n')
                append("State: ").append(status.state.name).append('\n')
                if (status.selfAddresses.isNotEmpty()) {
                    append("Addresses: ").append(status.selfAddresses.joinToString(", ")).append('\n')
                }
                status.peerCount?.let { append("Peers: ").append(it).append('\n') }
                status.lastError?.let { append("Error: ").append(it) }
            }
        }
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
        startForegroundService(
            Intent(this, MokaVpnService::class.java).setAction(MokaVpnService.ACTION_START_VPN),
        )
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 1
    }
}

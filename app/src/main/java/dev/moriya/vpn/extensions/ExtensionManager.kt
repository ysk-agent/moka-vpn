package dev.moriya.vpn.extensions

import android.content.Context
import dev.moriya.vpn.tunnel.TunnelBackend
import dev.moriya.vpn.tunnel.TunnelStatus
import dev.moriya.vpn.tunnel.TunnelStatusListener
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The extension host.
 *
 * Owned by MokaVpnService: the service creates it in onCreate,
 * [start]s it with the service and [stop]s it in onDestroy, so
 * extensions live exactly as long as the VPN service — the process
 * Android revives for the always-on VPN. The manager:
 *
 * - instantiates the built-in extensions ([Extensions.createAll])
 *   around one shared [HealthResults] channel,
 * - starts the extensions whose persisted enabled flag is on, and
 *   stops the rest,
 * - forwards every backend status change (Layer 2 [TunnelStatus]
 *   only) to the running extensions,
 * - is the UI's read/write point while the service is alive: the
 *   activity renders from [snapshot] and toggles through
 *   [setEnabled], which persists the flag and applies it live.
 *
 * Threading: calls arrive from the service's main thread, the UI
 * thread, and backend callback threads; the running-set is guarded
 * by a lock and extension state is internally thread-safe.
 */
class ExtensionManager(
    context: Context,
    private val backend: TunnelBackend,
    private val settings: ExtensionSettings,
) : TunnelStatusListener {

    private val appContext: Context = context.applicationContext

    /** The shared health channel the built-in extensions are wired around. */
    val healthResults = HealthResults()

    private val extensions: List<VpnExtension> = Extensions.createAll(healthResults)
    private val lock = Any()
    private val running = HashMap<String, VpnExtension>()

    @Volatile private var started = false

    private val uiListeners = CopyOnWriteArraySet<() -> Unit>()

    /** Immutable view of one extension for the UI. */
    data class ExtensionUiState(
        val info: ExtensionInfo,
        val enabled: Boolean,
        val running: Boolean,
        val statusText: String,
    )

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        backend.addListener(this)
        extensions.forEach { extension ->
            if (settings.isEnabled(extension.info)) startExtension(extension)
        }
        notifyUiListeners()
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
        }
        backend.removeListener(this)
        val toStop = synchronized(lock) {
            val copy = running.values.toList()
            running.clear()
            copy
        }
        toStop.forEach { runCatching { it.stop() } }
        notifyUiListeners()
    }

    override fun onTunnelStatus(status: TunnelStatus) {
        val targets = synchronized(lock) { running.values.toList() }
        targets.forEach { runCatching { it.onTunnelStatus(status) } }
        notifyUiListeners()
    }

    /** Persist [enabled] for [id] and apply it live when started. */
    fun setEnabled(id: String, enabled: Boolean) {
        settings.setEnabled(id, enabled)
        val extension = extensions.firstOrNull { it.info.id == id } ?: return
        if (!started) return
        if (enabled) startExtension(extension) else stopExtension(extension)
        notifyUiListeners()
    }

    fun snapshot(): List<ExtensionUiState> = extensions.map { extension ->
        val isRunning = synchronized(lock) { running.containsKey(extension.info.id) }
        ExtensionUiState(
            info = extension.info,
            enabled = settings.isEnabled(extension.info),
            running = isRunning,
            statusText = if (isRunning) {
                runCatching { extension.statusText() }.getOrDefault("—")
            } else {
                "not running"
            },
        )
    }

    fun addUiListener(listener: () -> Unit) {
        uiListeners.add(listener)
    }

    fun removeUiListener(listener: () -> Unit) {
        uiListeners.remove(listener)
    }

    private fun startExtension(extension: VpnExtension) {
        val fresh = synchronized(lock) {
            if (running.containsKey(extension.info.id)) {
                false
            } else {
                running[extension.info.id] = extension
                true
            }
        }
        if (fresh) runCatching { extension.start(appContext) }
    }

    private fun stopExtension(extension: VpnExtension) {
        val removed = synchronized(lock) { running.remove(extension.info.id) }
        if (removed != null) runCatching { removed.stop() }
    }

    private fun notifyUiListeners() {
        uiListeners.forEach { runCatching { it() } }
    }
}

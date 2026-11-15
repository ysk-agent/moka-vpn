package dev.moriya.vpn.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.moriya.vpn.tunnel.TunnelState
import dev.moriya.vpn.tunnel.TunnelStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Startup-management extension — the helper, and the founding
 * purpose of this app (Moriya, 2026-11-15).
 *
 * The cascade it completes: Android revives this app because it is
 * the designated always-on VPN → this extension notices the
 * phone-node stack is dead → it restarts phone-node, whose own guest
 * supervisor then restarts opencode serve. The stock VPN app could
 * never host this logic.
 *
 * Behaviour:
 * - Consumes the shared [HealthResults]: when the health check has
 *   failed [FAILURES_BEFORE_ATTEMPT] times in a row, it issues the
 *   resurrection call, and re-issues it every further
 *   [FAILURES_BEFORE_ATTEMPT] consecutive failures (attempts at 2,
 *   4, 6, …) so a stack that stays dead keeps being retried without
 *   spamming every poll.
 * - When the tunnel first reaches Running while this extension is
 *   enabled, it issues the same start call once ("start the stack
 *   when the connection comes up").
 *
 * The resurrection call mirrors the phone-node helper/waker exactly:
 * an explicit-component [Context.startForegroundService] for
 * `dev.moriya.phonenode/.NodeService`. Two platform lessons are baked
 * in:
 * - startForegroundService does NOT throw when the target cannot be
 *   resolved — it returns null (Android 11+ package visibility,
 *   Libero 2026-11-14). A resurrection is therefore counted ONLY
 *   when the returned [ComponentName] is non-null, and this app's
 *   manifest declares `<queries>` for the phone-node package so the
 *   resolution can succeed at all.
 * - When the platform does throw (Android 12+ background
 *   foreground-service-start restrictions), the failure is recorded
 *   in the status line instead of crashing the host service.
 */
class StartupManagementExtension(
    override val info: ExtensionInfo,
    private val healthResults: HealthResults,
) : VpnExtension {

    @Volatile private var appContext: Context? = null
    @Volatile private var wasRunning = false

    /** Failure count at which the most recent health-driven attempt fired. */
    @Volatile private var lastAttemptAtFailures = 0

    @Volatile private var attempts = 0
    @Volatile private var resurrections = 0
    @Volatile private var lastAttemptAtMs = 0L
    @Volatile private var lastAttemptNote: String? = null

    private val healthListener: (HealthResults) -> Unit = { results ->
        onHealthResult(results.consecutiveFailures)
    }

    override fun start(context: Context) {
        appContext = context.applicationContext
        lastAttemptAtFailures = 0
        healthResults.addListener(healthListener)
    }

    override fun stop() {
        healthResults.removeListener(healthListener)
        appContext = null
        wasRunning = false
    }

    override fun onTunnelStatus(status: TunnelStatus) {
        val running = status.state == TunnelState.Running
        if (running && !wasRunning) {
            // First transition into Running while enabled: bring the
            // stack up with the connection.
            attempt("tunnel connected")
        }
        wasRunning = running
    }

    override fun statusText(): String {
        if (attempts == 0) return "no start attempts yet"
        val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastAttemptAtMs))
        val note = lastAttemptNote?.let { " · $it" } ?: ""
        return "attempts: $attempts · resurrections: $resurrections · last $at$note"
    }

    private fun onHealthResult(consecutiveFailures: Int) {
        if (consecutiveFailures < FAILURES_BEFORE_ATTEMPT) return
        if (consecutiveFailures % FAILURES_BEFORE_ATTEMPT != 0) return
        if (consecutiveFailures == lastAttemptAtFailures) return
        lastAttemptAtFailures = consecutiveFailures
        attempt("health failed ${consecutiveFailures}x")
    }

    /**
     * Issue the resurrection start call and record the outcome.
     * Never throws: the host service must survive a misbehaving
     * platform or a missing phone-node install.
     */
    private fun attempt(reason: String) {
        val context = appContext ?: return
        attempts += 1
        lastAttemptAtMs = System.currentTimeMillis()
        val intent = Intent().apply {
            component = ComponentName(PHONE_NODE_PACKAGE, NODE_SERVICE_CLASS)
        }
        lastAttemptNote = try {
            val started = context.startForegroundService(intent)
            if (started != null) {
                resurrections += 1
                "$reason · started"
            } else {
                // Silent resolution failure (package visibility or
                // phone-node not installed) — visible here, unlike
                // the helper's original silent no-op.
                "$reason · start returned null (not found)"
            }
        } catch (e: Exception) {
            "$reason · failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    companion object {
        const val PHONE_NODE_PACKAGE = "dev.moriya.phonenode"
        const val NODE_SERVICE_CLASS = "dev.moriya.phonenode.NodeService"
        const val FAILURES_BEFORE_ATTEMPT = 2
    }
}

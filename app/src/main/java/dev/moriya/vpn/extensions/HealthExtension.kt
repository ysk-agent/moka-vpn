package dev.moriya.vpn.extensions

import android.content.Context
import dev.moriya.vpn.tunnel.TunnelStatus
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Health extension — pure observation.
 *
 * Polls the node stack's health endpoint on this device every
 * [POLL_INTERVAL_SECONDS] seconds and publishes each result into the
 * shared [HealthResults]. The default target is opencode serve's
 * health endpoint (phone-node runs it on the same device); loopback
 * is never routed into the TUN, so the poll measures the local stack
 * itself, not the tunnel. Framework APIs only (HttpURLConnection) —
 * no new dependencies, per the project's standing rule.
 */
class HealthExtension(
    override val info: ExtensionInfo,
    private val results: HealthResults,
    private val targetUrl: String = DEFAULT_TARGET_URL,
) : VpnExtension {

    @Volatile private var scheduler: ScheduledExecutorService? = null

    override fun start(context: Context) {
        if (scheduler != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mokavpn-health").apply { isDaemon = true }
        }
        scheduler = executor
        // First poll immediately, then on the fixed cadence. A poll
        // that hangs is bounded by the HTTP timeouts below, well
        // under the interval.
        executor.scheduleWithFixedDelay(
            { poll() }, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS,
        )
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun onTunnelStatus(status: TunnelStatus) {
        // Observation is tunnel-independent: the watched stack is a
        // local service. Nothing to do with tunnel state changes.
    }

    override fun statusText(): String {
        val last = results.lastSuccess ?: return "no checks yet"
        val checkedAt = formatTime(results.lastCheckAtMs)
        return if (last) {
            "OK · checked $checkedAt"
        } else {
            val failures = results.consecutiveFailures
            val lastOk = results.lastSuccessAtMs
            val lastOkText = if (lastOk > 0) formatTime(lastOk) else "never"
            "FAILING · $failures in a row · last OK $lastOkText · checked $checkedAt"
        }
    }

    private fun poll() {
        val success = try {
            val connection = URL(targetUrl).openConnection(Proxy.NO_PROXY)
                as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            try {
                connection.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            // Unreachable, timed out, malformed — all are "not
            // healthy" for a watcher; the detail lives in logcat via
            // the platform, the status line carries the outcome.
            false
        }
        results.record(success, System.currentTimeMillis())
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    companion object {
        const val DEFAULT_TARGET_URL = "http://127.0.0.1:4096/global/health"
        const val POLL_INTERVAL_SECONDS = 60L
        private const val TIMEOUT_MS = 5_000
    }
}

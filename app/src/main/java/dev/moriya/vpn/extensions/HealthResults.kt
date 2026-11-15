package dev.moriya.vpn.extensions

import java.util.concurrent.CopyOnWriteArraySet

/**
 * The shared health channel between extensions, owned by the
 * [ExtensionManager]: the Health extension publishes every poll
 * result here; Startup management (and the UI, via the Health
 * extension's status text) consumes it. Keeping the results in a
 * standalone holder — instead of the extensions referencing each
 * other — is what lets either side be replaced or tested alone.
 */
class HealthResults {

    /** Wall-clock time of the most recent poll, 0 = never polled. */
    @Volatile var lastCheckAtMs: Long = 0L
        private set

    /** Result of the most recent poll; null = never polled. */
    @Volatile var lastSuccess: Boolean? = null
        private set

    /** Failed polls in a row, reset to 0 by any success. */
    @Volatile var consecutiveFailures: Int = 0
        private set

    /** Wall-clock time of the most recent successful poll, 0 = never. */
    @Volatile var lastSuccessAtMs: Long = 0L
        private set

    private val listeners = CopyOnWriteArraySet<(HealthResults) -> Unit>()

    /** Record one poll outcome and notify consumers. */
    fun record(success: Boolean, atMs: Long) {
        lastCheckAtMs = atMs
        lastSuccess = success
        if (success) {
            consecutiveFailures = 0
            lastSuccessAtMs = atMs
        } else {
            consecutiveFailures += 1
        }
        listeners.forEach { it(this) }
    }

    fun addListener(listener: (HealthResults) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (HealthResults) -> Unit) {
        listeners.remove(listener)
    }
}

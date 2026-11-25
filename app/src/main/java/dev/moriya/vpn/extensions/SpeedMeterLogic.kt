package dev.moriya.vpn.extensions

import kotlin.math.floor

/**
 * Pure rate/format math for the speed meter extension (v1.5.0,
 * DESIGN.md §9.8) — kept free of Android APIs so it is covered by
 * plain-JVM tests in `tests/speed-meter/`, the same split the
 * Proxy and ADB keepalive extensions use.
 *
 * The display format is fixed by decision (Moriya, 2026-11-25):
 * always "XX.X MB/s" — the unit never switches (300 KB/s shows as
 * "0.3 MB/s"), exactly one decimal, quantised to 0.1 MB/s steps so
 * small fluctuations do not change the string. A fixed width and
 * shape keeps the status-bar icon stable, and quantisation plus
 * update-on-change (the extension notifies only when the string
 * changes) keeps icon updates rare at steady rates.
 *
 * MB here is decimal (1 MB = 1,000,000 bytes), the convention
 * speed meters and carriers use: 300,000 B/s is exactly 0.3 MB/s.
 */
object SpeedMeterLogic {

    /** Decimal megabyte, in bytes. */
    const val BYTES_PER_MB = 1_000_000.0

    /**
     * Selectable poll intervals, in seconds — the presets the UI
     * dropdown offers (Moriya, 2026-11-25). The lineup is
     * provisional: it is to be revisited once the separate lab APK's
     * on-device interval trials land; until then the default is
     * [DEFAULT_INTERVAL_SECONDS] and there is deliberately NO
     * adaptive-interval logic.
     */
    val INTERVAL_PRESETS_SECONDS: List<Int> = listOf(1, 2, 3, 5, 10)

    const val DEFAULT_INTERVAL_SECONDS = 1

    /**
     * Bytes per second for [deltaBytes] transferred over
     * [elapsedMs]. Uses the actual elapsed time, not the nominal
     * interval, so scheduler jitter does not skew the rate. A
     * non-positive elapsed time or a negative delta (the platform
     * counters went backwards, e.g. across a reboot) yields 0 —
     * never a negative or infinite rate.
     */
    fun rateBytesPerSecond(deltaBytes: Long, elapsedMs: Long): Double {
        if (elapsedMs <= 0L || deltaBytes <= 0L) return 0.0
        return deltaBytes * 1000.0 / elapsedMs
    }

    /**
     * A rate as an integer count of 0.1 MB/s steps, rounded to the
     * nearest step (halves round up). Working in integer tenths
     * keeps the formatted string exact — no float formatting
     * surprises at the step boundaries.
     */
    fun tenthsOfMbPerSecond(bytesPerSecond: Double): Long {
        if (bytesPerSecond <= 0.0) return 0L
        return floor(bytesPerSecond / BYTES_PER_MB * 10.0 + 0.5).toLong()
    }

    /** Format a count of 0.1 MB/s steps as "XX.X MB/s". */
    fun formatTenths(tenths: Long): String = "${tenths / 10}.${tenths % 10} MB/s"

    /** Format one direction's rate as "XX.X MB/s". */
    fun formatRate(bytesPerSecond: Double): String =
        formatTenths(tenthsOfMbPerSecond(bytesPerSecond))

    /** Format the combined up+down rate as "XX.X MB/s". */
    fun formatCombined(rxBytesPerSecond: Double, txBytesPerSecond: Double): String =
        formatRate(rxBytesPerSecond + txBytesPerSecond)

    /**
     * The interval to actually use for a stored value: one of the
     * presets, or the default when the stored value is not (a
     * hand-edited pref, or a preset removed by a later version).
     */
    fun sanitizeInterval(seconds: Int): Int =
        if (seconds in INTERVAL_PRESETS_SECONDS) seconds else DEFAULT_INTERVAL_SECONDS

    /** One measured interval: per-direction rates and the display string. */
    data class Sample(
        val rxBytesPerSecond: Double,
        val txBytesPerSecond: Double,
    ) {
        /** The status-bar string: combined rate, always "XX.X MB/s". */
        val display: String get() = formatCombined(rxBytesPerSecond, txBytesPerSecond)
    }

    /**
     * Turns cumulative platform counters into per-interval rates.
     * The first sample after construction or [reset] only sets the
     * baseline and yields null — there is no interval to measure
     * yet. The extension resets on screen-on, so traffic that moved
     * while the screen was off does not spike the first reading
     * after wake.
     */
    class Sampler {
        private var hasBaseline = false
        private var lastRxBytes = 0L
        private var lastTxBytes = 0L
        private var lastAtMs = 0L

        @Synchronized
        fun reset() {
            hasBaseline = false
        }

        @Synchronized
        fun onSample(totalRxBytes: Long, totalTxBytes: Long, nowMs: Long): Sample? {
            if (!hasBaseline) {
                lastRxBytes = totalRxBytes
                lastTxBytes = totalTxBytes
                lastAtMs = nowMs
                hasBaseline = true
                return null
            }
            val elapsedMs = nowMs - lastAtMs
            val sample = Sample(
                rxBytesPerSecond = rateBytesPerSecond(totalRxBytes - lastRxBytes, elapsedMs),
                txBytesPerSecond = rateBytesPerSecond(totalTxBytes - lastTxBytes, elapsedMs),
            )
            lastRxBytes = totalRxBytes
            lastTxBytes = totalTxBytes
            lastAtMs = nowMs
            return sample
        }
    }
}

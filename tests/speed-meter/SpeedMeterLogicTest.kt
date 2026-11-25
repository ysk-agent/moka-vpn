import dev.moriya.vpn.extensions.SpeedMeterLogic

/**
 * Plain-JVM tests for SpeedMeterLogic (the speed meter extension's
 * pure rate/format math). Run via tests/run-speed-meter-tests.sh.
 *
 * Only the pure functions and the Sampler are tested — they have
 * no Android dependencies by construction. The TrafficStats reads,
 * the notification icon rendering and the screen on/off handling
 * in SpeedMeterExtension are a thin shell and are device-verified
 * only.
 */

private var failures = 0
private var checks = 0

private fun checkEq(name: String, expected: String, actual: String) {
    checks++
    if (expected != actual) {
        failures++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun checkEq(name: String, expected: Int, actual: Int) {
    checks++
    if (expected != actual) {
        failures++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun checkClose(name: String, expected: Double, actual: Double) {
    checks++
    if (kotlin.math.abs(expected - actual) > 0.001) {
        failures++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun checkTrue(name: String, actual: Boolean) {
    checks++
    if (!actual) {
        failures++
        println("FAIL: $name — expected true")
    }
}

fun main() {
    // -- rate from delta and actual elapsed time --
    checkClose("300000 B over 1000 ms", 300_000.0, SpeedMeterLogic.rateBytesPerSecond(300_000, 1_000))
    checkClose("elapsed scaling: 600000 B over 2000 ms", 300_000.0, SpeedMeterLogic.rateBytesPerSecond(600_000, 2_000))
    checkClose("elapsed scaling: 150000 B over 500 ms", 300_000.0, SpeedMeterLogic.rateBytesPerSecond(150_000, 500))
    checkClose("zero delta", 0.0, SpeedMeterLogic.rateBytesPerSecond(0, 1_000))
    checkClose("negative delta (counters went backwards)", 0.0, SpeedMeterLogic.rateBytesPerSecond(-5, 1_000))
    checkClose("zero elapsed", 0.0, SpeedMeterLogic.rateBytesPerSecond(1_000, 0))

    // -- formatting: always "XX.X MB/s", quantised to 0.1 steps --
    checkEq("zero traffic", "0.0 MB/s", SpeedMeterLogic.formatRate(0.0))
    checkEq("sub-step: 0.04 MB/s rounds down", "0.0 MB/s", SpeedMeterLogic.formatRate(40_000.0))
    checkEq("half step: 0.05 MB/s rounds up", "0.1 MB/s", SpeedMeterLogic.formatRate(50_000.0))
    checkEq("0.06 MB/s", "0.1 MB/s", SpeedMeterLogic.formatRate(60_000.0))
    checkEq("0.149 MB/s stays 0.1", "0.1 MB/s", SpeedMeterLogic.formatRate(149_999.0))
    checkEq("0.15 MB/s rounds to 0.2", "0.2 MB/s", SpeedMeterLogic.formatRate(150_000.0))
    checkEq("300 KB/s is 0.3 MB/s", "0.3 MB/s", SpeedMeterLogic.formatRate(300_000.0))
    checkEq("~1.2 MB/s", "1.2 MB/s", SpeedMeterLogic.formatRate(1_200_000.0))
    checkEq("large: 95.4 MB/s", "95.4 MB/s", SpeedMeterLogic.formatRate(95_400_000.0))
    checkEq("larger: 250 MB/s keeps one decimal", "250.0 MB/s", SpeedMeterLogic.formatRate(250_000_000.0))
    checkEq("9.949 MB/s stays 9.9", "9.9 MB/s", SpeedMeterLogic.formatRate(9_949_999.0))
    checkEq("9.95 MB/s carries to 10.0", "10.0 MB/s", SpeedMeterLogic.formatRate(9_950_000.0))
    checkEq("negative rate clamps to zero", "0.0 MB/s", SpeedMeterLogic.formatRate(-1.0))

    // -- combined up+down --
    checkEq(
        "combined 200 KB/s down + 100 KB/s up",
        "0.3 MB/s",
        SpeedMeterLogic.formatCombined(200_000.0, 100_000.0),
    )
    checkEq(
        "combined zero",
        "0.0 MB/s",
        SpeedMeterLogic.formatCombined(0.0, 0.0),
    )

    // -- sampler: first sample is the baseline only --
    val sampler = SpeedMeterLogic.Sampler()
    checkTrue(
        "first sample yields null (baseline)",
        sampler.onSample(1_000_000, 500_000, 10_000) == null,
    )
    val second = sampler.onSample(1_300_000, 600_000, 11_000)
    checkTrue("second sample yields a value", second != null)
    checkClose("sampler rx rate", 300_000.0, second!!.rxBytesPerSecond)
    checkClose("sampler tx rate", 100_000.0, second.txBytesPerSecond)
    checkEq("sampler display", "0.4 MB/s", second.display)

    // -- sampler: elapsed-time scaling over a longer interval --
    val slow = SpeedMeterLogic.Sampler()
    slow.onSample(0, 0, 0)
    val slowSample = slow.onSample(600_000, 0, 2_000)
    checkEq("600000 B over 2000 ms displays 0.3 MB/s", "0.3 MB/s", slowSample!!.display)

    // -- sampler: no traffic over the interval --
    val idle = SpeedMeterLogic.Sampler()
    idle.onSample(42_000, 42_000, 1_000)
    checkEq("idle interval displays 0.0 MB/s", "0.0 MB/s", idle.onSample(42_000, 42_000, 2_000)!!.display)

    // -- sampler: reset makes the next sample a fresh baseline --
    val resetSampler = SpeedMeterLogic.Sampler()
    resetSampler.onSample(0, 0, 0)
    resetSampler.onSample(300_000, 0, 1_000)
    resetSampler.reset()
    checkTrue(
        "sample after reset yields null (baseline)",
        resetSampler.onSample(9_000_000, 9_000_000, 5_000) == null,
    )
    checkEq(
        "post-reset interval measures from the new baseline",
        "0.3 MB/s",
        resetSampler.onSample(9_300_000, 9_000_000, 6_000)!!.display,
    )

    // -- sampler: counters going backwards read as zero, not negative --
    val backwards = SpeedMeterLogic.Sampler()
    backwards.onSample(1_000_000, 1_000_000, 0)
    checkEq(
        "counter decrease displays 0.0 MB/s",
        "0.0 MB/s",
        backwards.onSample(500_000, 500_000, 1_000)!!.display,
    )

    // -- interval presets and sanitising --
    checkTrue(
        "presets are 1/2/3/5/10",
        SpeedMeterLogic.INTERVAL_PRESETS_SECONDS == listOf(1, 2, 3, 5, 10),
    )
    checkEq("default interval is 1", 1, SpeedMeterLogic.DEFAULT_INTERVAL_SECONDS)
    checkEq("preset passes through", 3, SpeedMeterLogic.sanitizeInterval(3))
    checkEq("non-preset falls back to default", 1, SpeedMeterLogic.sanitizeInterval(4))
    checkEq("zero falls back to default", 1, SpeedMeterLogic.sanitizeInterval(0))

    println("checks: $checks, failures: $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL PASS")
}

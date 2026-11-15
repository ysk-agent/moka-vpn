import dev.moriya.vpn.extensions.AdbKeepaliveLogic
import dev.moriya.vpn.extensions.AdbKeepaliveLogic.Action

/**
 * Plain-JVM tests for AdbKeepaliveLogic (the ADB keepalive
 * extension's pure decision logic). Run via
 * tests/run-adb-keepalive-tests.sh.
 *
 * Only the decision function is tested — it has no Android
 * dependencies by construction. The Settings.Global I/O around it
 * is a thin shell and is device-verified only.
 */

private var failures = 0
private var checks = 0

private fun checkEq(name: String, expected: Action, actual: Action) {
    checks++
    if (expected != actual) {
        failures++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

fun main() {
    // -- toggle on: NONE regardless of grant and Wi-Fi --
    checkEq("on, grant, wifi", Action.NONE, AdbKeepaliveLogic.decide(1, true, true))
    checkEq("on, grant, no wifi", Action.NONE, AdbKeepaliveLogic.decide(1, true, false))
    checkEq("on, no grant, wifi", Action.NONE, AdbKeepaliveLogic.decide(1, false, true))
    checkEq("on, no grant, no wifi", Action.NONE, AdbKeepaliveLogic.decide(1, false, false))

    // -- toggle off: the one writable state --
    checkEq(
        "off, grant, wifi -> RESTORE",
        Action.RESTORE, AdbKeepaliveLogic.decide(0, true, true),
    )

    // -- toggle off, Wi-Fi down: wait, never write --
    checkEq(
        "off, grant, no wifi -> WAIT_FOR_WIFI",
        Action.WAIT_FOR_WIFI, AdbKeepaliveLogic.decide(0, true, false),
    )

    // -- toggle off, no grant: never attempt the write --
    checkEq(
        "off, no grant, wifi -> NO_GRANT",
        Action.NO_GRANT, AdbKeepaliveLogic.decide(0, false, true),
    )
    checkEq(
        "off, no grant, no wifi -> NO_GRANT",
        Action.NO_GRANT, AdbKeepaliveLogic.decide(0, false, false),
    )

    // -- unreadable: a safe no-write action in every combination --
    checkEq("unreadable, grant, wifi", Action.UNREADABLE, AdbKeepaliveLogic.decide(null, true, true))
    checkEq("unreadable, grant, no wifi", Action.UNREADABLE, AdbKeepaliveLogic.decide(null, true, false))
    checkEq("unreadable, no grant, wifi", Action.UNREADABLE, AdbKeepaliveLogic.decide(null, false, true))
    checkEq("unreadable, no grant, no wifi", Action.UNREADABLE, AdbKeepaliveLogic.decide(null, false, false))

    // -- a readable value other than 1 is treated as off --
    checkEq(
        "value 2, grant, wifi -> RESTORE",
        Action.RESTORE, AdbKeepaliveLogic.decide(2, true, true),
    )
    checkEq(
        "value 2, grant, no wifi -> WAIT_FOR_WIFI",
        Action.WAIT_FOR_WIFI, AdbKeepaliveLogic.decide(2, true, false),
    )

    println("checks: $checks, failures: $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL PASS")
}

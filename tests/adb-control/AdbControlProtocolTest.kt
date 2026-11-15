import dev.moriya.vpn.extensions.AdbControlProtocol
import dev.moriya.vpn.extensions.AdbControlProtocol.Decision

/**
 * Plain-JVM tests for AdbControlProtocol (the ADB keepalive
 * extension's remote control endpoint authorization). Run via
 * tests/run-adb-control-tests.sh.
 *
 * Only the pure decision/shaping functions are tested — they have
 * no Android or socket dependencies by construction. The socket
 * core and the Settings.Global writes around them are thin shells
 * and are device-verified only.
 */

private var failures = 0
private var checks = 0

private fun checkEq(name: String, expected: Any?, actual: Any?) {
    checks++
    if (expected != actual) {
        failures++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private val ALLOWLIST = setOf(
    "***REMOVED***", "***REMOVED***", "***REMOVED***", "127.0.0.1", "::1",
)
private const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val VM = "***REMOVED***"

private fun auth(
    source: String? = VM,
    presented: String? = TOKEN,
    stored: String? = TOKEN,
    method: String,
    path: String,
): Decision = AdbControlProtocol.authorize(source, ALLOWLIST, presented, stored, method, path)

fun main() {
    // -- the three commands, allowlisted source + correct token --
    checkEq("POST on", Decision.ALLOW_ON, auth(method = "POST", path = "/adb-enabled/on"))
    checkEq("POST off", Decision.ALLOW_OFF, auth(method = "POST", path = "/adb-enabled/off"))
    checkEq("GET status", Decision.ALLOW_STATUS, auth(method = "GET", path = "/status"))
    checkEq("allow -> HTTP 200", 200, AdbControlProtocol.httpStatus(Decision.ALLOW_ON))

    // -- token failures (allowlisted source) --
    checkEq(
        "wrong token",
        Decision.DENY_TOKEN_MISMATCH,
        auth(presented = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", method = "POST", path = "/adb-enabled/on"),
    )
    checkEq(
        "wrong token -> HTTP 403",
        403, AdbControlProtocol.httpStatus(Decision.DENY_TOKEN_MISMATCH),
    )
    checkEq(
        "missing token",
        Decision.DENY_TOKEN_MISSING,
        auth(presented = null, method = "POST", path = "/adb-enabled/on"),
    )
    checkEq(
        "empty token counts as missing",
        Decision.DENY_TOKEN_MISSING,
        auth(presented = "", method = "GET", path = "/status"),
    )
    checkEq(
        "no stored token authorizes nothing",
        Decision.DENY_TOKEN_MISMATCH,
        auth(stored = null, method = "POST", path = "/adb-enabled/on"),
    )

    // -- allowlist failures (correct token is not enough) --
    checkEq(
        "stranger source with correct token",
        Decision.DENY_SOURCE_NOT_ALLOWED,
        auth(source = "100.99.99.99", method = "POST", path = "/adb-enabled/on"),
    )
    checkEq(
        "unknown source",
        Decision.DENY_SOURCE_NOT_ALLOWED,
        auth(source = null, method = "GET", path = "/status"),
    )
    checkEq(
        "source checked before token",
        Decision.DENY_SOURCE_NOT_ALLOWED,
        auth(source = "100.99.99.99", presented = "wrong", method = "POST", path = "/adb-enabled/on"),
    )
    checkEq(
        "source deny -> HTTP 403",
        403, AdbControlProtocol.httpStatus(Decision.DENY_SOURCE_NOT_ALLOWED),
    )

    // -- routing failures (both factors already passed) --
    checkEq(
        "unknown path",
        Decision.DENY_NOT_FOUND,
        auth(method = "GET", path = "/adb-enabled"),
    )
    checkEq(
        "unknown path -> HTTP 404",
        404, AdbControlProtocol.httpStatus(Decision.DENY_NOT_FOUND),
    )
    checkEq(
        "GET on the on path",
        Decision.DENY_METHOD_NOT_ALLOWED,
        auth(method = "GET", path = "/adb-enabled/on"),
    )
    checkEq(
        "POST on the status path",
        Decision.DENY_METHOD_NOT_ALLOWED,
        auth(method = "POST", path = "/status"),
    )
    checkEq(
        "method deny -> HTTP 404",
        404, AdbControlProtocol.httpStatus(Decision.DENY_METHOD_NOT_ALLOWED),
    )

    // -- token comparison --
    checkEq("token equal", true, AdbControlProtocol.tokenMatches(TOKEN, TOKEN))
    checkEq(
        "token different, same length",
        false, AdbControlProtocol.tokenMatches(TOKEN, TOKEN.replaceFirst('0', '1')),
    )
    checkEq(
        "token different length",
        false, AdbControlProtocol.tokenMatches(TOKEN, TOKEN.dropLast(1)),
    )
    checkEq("token null presented", false, AdbControlProtocol.tokenMatches(null, TOKEN))
    checkEq("token empty stored", false, AdbControlProtocol.tokenMatches(TOKEN, ""))

    // -- token header extraction --
    checkEq(
        "header found, value trimmed",
        TOKEN,
        AdbControlProtocol.tokenHeaderValue(
            listOf("Host: ***REMOVED***:8091", "X-Moka-Token:  $TOKEN ", "Accept: */*"),
        ),
    )
    checkEq(
        "header name is case-insensitive",
        TOKEN,
        AdbControlProtocol.tokenHeaderValue(listOf("x-moka-token: $TOKEN")),
    )
    checkEq(
        "header absent",
        null,
        AdbControlProtocol.tokenHeaderValue(listOf("Host: ***REMOVED***:8091")),
    )
    checkEq(
        "header empty value counts as absent",
        null,
        AdbControlProtocol.tokenHeaderValue(listOf("X-Moka-Token:")),
    )

    // -- status body shaping --
    checkEq(
        "status body",
        "adb_wifi_enabled: 1\nadb_enabled: 0\ngrant: held\n",
        AdbControlProtocol.formatStatus(1, 0, true),
    )
    checkEq(
        "status body with unreadable values",
        "adb_wifi_enabled: unknown\nadb_enabled: unknown\ngrant: not held\n",
        AdbControlProtocol.formatStatus(null, null, false),
    )

    println("checks: $checks, failures: $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL PASS")
}

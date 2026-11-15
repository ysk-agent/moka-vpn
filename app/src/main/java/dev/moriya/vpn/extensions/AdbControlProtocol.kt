package dev.moriya.vpn.extensions

import java.security.MessageDigest

/**
 * Authorization and response shaping for the ADB keepalive
 * extension's remote control endpoint (v1.3.0, DESIGN.md §9.7) —
 * pure functions, free of Android types AND of sockets, covered by
 * plain-JVM tests in `tests/adb-control/`. The socket half lives
 * in [AdbControlServerCore]; the settings writes themselves live
 * in [AdbKeepaliveExtension], which is the only class that touches
 * Settings.Global for this feature.
 *
 * What the endpoint is for (measured on moto g05, 2026-11-16):
 * wireless debugging needs Wi-Fi, so on mobile data adbd stops and
 * tailnet adb dies with it — while the tunnel itself stays alive.
 * Turning USB debugging on revives adbd and the persisted
 * `adb tcpip 5555` listener with it, so a command channel over the
 * tunnel restores VM-side adb without touching the phone. The
 * commands are exactly three (on, off, status); there is no
 * generic settings or shell surface.
 *
 * Authorization is two-factor by construction, and BOTH factors
 * are required: the source address must be on the allowlist (the
 * Proxy extension's list — Moriya's own devices plus loopback),
 * and the request must carry the per-device token in the
 * `X-Moka-Token` header. The token is generated on the device,
 * shown in the extension's status line for the owner to read off
 * the screen, and stored by the VM-side helper
 * (`tools/moka-adb-debug`) — it never appears in this repo.
 */
object AdbControlProtocol {

    /** The settings key the endpoint — and only the endpoint — writes. */
    const val USB_SETTING_KEY = "adb_enabled"

    const val PATH_ON = "/adb-enabled/on"
    const val PATH_OFF = "/adb-enabled/off"
    const val PATH_STATUS = "/status"

    /** Header carrying the token; matched case-insensitively. */
    const val TOKEN_HEADER = "x-moka-token"

    enum class Decision {
        ALLOW_ON,
        ALLOW_OFF,
        ALLOW_STATUS,
        DENY_SOURCE_NOT_ALLOWED,
        DENY_TOKEN_MISSING,
        DENY_TOKEN_MISMATCH,
        DENY_NOT_FOUND,
        DENY_METHOD_NOT_ALLOWED,
    }

    /**
     * The one authorization decision. Order is deliberate: the
     * allowlist is checked first (a stranger learns nothing about
     * the token), then the token, then routing — an unknown path
     * or a wrong method is only distinguishable to a caller that
     * already passed both factors.
     */
    fun authorize(
        sourceAddress: String?,
        allowedSources: Set<String>,
        presentedToken: String?,
        storedToken: String?,
        method: String,
        path: String,
    ): Decision {
        if (!ProxyProtocol.isAllowedSource(sourceAddress, allowedSources)) {
            return Decision.DENY_SOURCE_NOT_ALLOWED
        }
        if (presentedToken.isNullOrEmpty()) return Decision.DENY_TOKEN_MISSING
        if (!tokenMatches(presentedToken, storedToken)) {
            return Decision.DENY_TOKEN_MISMATCH
        }
        return when (path) {
            PATH_ON ->
                if (method == "POST") Decision.ALLOW_ON
                else Decision.DENY_METHOD_NOT_ALLOWED
            PATH_OFF ->
                if (method == "POST") Decision.ALLOW_OFF
                else Decision.DENY_METHOD_NOT_ALLOWED
            PATH_STATUS ->
                if (method == "GET") Decision.ALLOW_STATUS
                else Decision.DENY_METHOD_NOT_ALLOWED
            else -> Decision.DENY_NOT_FOUND
        }
    }

    /** HTTP status for a decision: 200 / 403 / 404, nothing else. */
    fun httpStatus(decision: Decision): Int = when (decision) {
        Decision.ALLOW_ON, Decision.ALLOW_OFF, Decision.ALLOW_STATUS -> 200
        Decision.DENY_SOURCE_NOT_ALLOWED,
        Decision.DENY_TOKEN_MISSING,
        Decision.DENY_TOKEN_MISMATCH -> 403
        Decision.DENY_NOT_FOUND, Decision.DENY_METHOD_NOT_ALLOWED -> 404
    }

    /** Plain-text body for a denied request (the reason, no more). */
    fun denyBody(decision: Decision): String = when (decision) {
        Decision.DENY_SOURCE_NOT_ALLOWED -> "denied: source not allowed\n"
        Decision.DENY_TOKEN_MISSING -> "denied: token missing\n"
        Decision.DENY_TOKEN_MISMATCH -> "denied: token mismatch\n"
        Decision.DENY_NOT_FOUND -> "denied: not found\n"
        Decision.DENY_METHOD_NOT_ALLOWED -> "denied: method not allowed\n"
        else -> "denied\n"
    }

    /**
     * Constant-time token comparison. A missing or empty value on
     * either side never matches — in particular, when no token has
     * been generated/stored yet, nothing is authorized.
     */
    fun tokenMatches(presented: String?, stored: String?): Boolean {
        if (presented.isNullOrEmpty() || stored.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            stored.toByteArray(Charsets.UTF_8),
        )
    }

    /** The `X-Moka-Token` header value from parsed header lines. */
    fun tokenHeaderValue(headerLines: List<String>): String? {
        for (line in headerLines) {
            val name = line.substringBefore(':', "").trim().lowercase()
            if (name == TOKEN_HEADER) {
                return line.substringAfter(':', "").trim()
                    .takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * The GET /status body: the two toggle values and the grant
     * state, one per line, `unknown` for an unreadable value.
     */
    fun formatStatus(
        adbWifiEnabled: Int?,
        adbEnabled: Int?,
        grantHeld: Boolean,
    ): String = buildString {
        append("adb_wifi_enabled: ").append(adbWifiEnabled?.toString() ?: "unknown").append('\n')
        append("adb_enabled: ").append(adbEnabled?.toString() ?: "unknown").append('\n')
        append("grant: ").append(if (grantHeld) "held" else "not held").append('\n')
    }

    /** A complete minimal HTTP response (text/plain, close). */
    fun httpResponse(code: Int, body: String): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reasonPhrase(code)}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + bodyBytes
    }

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }
}

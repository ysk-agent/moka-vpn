package dev.moriya.vpn.extensions

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/**
 * Proxy protocol logic as pure functions (v1.1.0, DESIGN.md §9.6).
 *
 * Everything in this file is free of Android types AND of sockets,
 * so it can be exercised on a plain JVM — which matters because JVM
 * socket tests cannot run on the build VM (its JVM TCP is
 * intercepted, the same fact that keeps Gradle off this machine).
 * The socket-carrying half lives in [ProxyServerCore]; the tests in
 * `tests/proxy/` cover this file.
 *
 * Semantics mirror phone-node's native proxy (ProxyServer.kt in
 * that repo): CONNECT tunnelling plus plain-HTTP absolute-URI
 * forwarding, a source allowlist, and proxy-specific headers
 * stripped before forwarding. Target URLs are never logged anywhere
 * — the functions below return them to the caller and that is all.
 */
object ProxyProtocol {

    /** Largest header block accepted, as in the reference proxy. */
    const val HEADER_LIMIT = 64 * 1024

    /** One parsed request line: `METHOD target HTTP/x.y`. */
    data class RequestLine(
        val method: String,
        val target: String,
        val version: String,
    )

    /** Parse a request line; null when it is not well-formed. */
    fun parseRequestLine(line: String): RequestLine? {
        val parts = line.split(" ")
        if (parts.size != 3) return null
        val method = parts[0].uppercase()
        if (method.isEmpty() || parts[1].isEmpty()) return null
        if (!parts[2].startsWith("HTTP/")) return null
        return RequestLine(method, parts[1], parts[2])
    }

    /**
     * Parse a CONNECT authority (`host:port`, `host`, `[v6]:port`,
     * `[v6]`); [defaultPort] applies when no port is present. Null
     * when malformed. (The reference proxy's last-colon split
     * mishandles bracketed IPv6 literals; this one does not.)
     */
    fun parseAuthority(authority: String, defaultPort: Int): Pair<String, Int>? {
        if (authority.isEmpty()) return null
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(1, close)
            if (host.isEmpty()) return null
            val rest = authority.substring(close + 1)
            if (rest.isEmpty()) return host to defaultPort
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            val checked = validPort(port) ?: return null
            return host to checked
        }
        val idx = authority.lastIndexOf(':')
        if (idx < 0) return authority to defaultPort
        // A bare IPv6 literal without brackets has several colons
        // and no port; treat the whole value as the host.
        if (authority.indexOf(':') != idx) return authority to defaultPort
        val host = authority.substring(0, idx)
        if (host.isEmpty()) return null
        val port = authority.substring(idx + 1).toIntOrNull() ?: return null
        val checked = validPort(port) ?: return null
        return host to checked
    }

    private fun validPort(port: Int): Int? = port.takeIf { it in 1..65535 }

    /** A plain-HTTP proxy target, split out of an absolute URI. */
    data class HttpTarget(
        val host: String,
        val port: Int,
        /** Origin-form path including the query string. */
        val path: String,
    )

    /**
     * Parse an absolute-URI proxy target
     * (`http://host[:port]/path?query`). Null when the URI has no
     * usable host. Mirrors the reference: default port 80, empty
     * path becomes "/", the raw query is preserved.
     */
    fun parseHttpTarget(absoluteUri: String): HttpTarget? {
        val uri = try {
            URI(absoluteUri)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        val port = if (uri.port == -1) 80 else uri.port
        var path = uri.rawPath ?: "/"
        if (path.isEmpty()) path = "/"
        if (uri.rawQuery != null) path += "?" + uri.rawQuery
        return HttpTarget(host, port, path)
    }

    /**
     * The allowlist decision: only listed source addresses may use
     * the proxy; anything else (including an unknown source) is
     * rejected. Loopback counts only when it is in the list — the
     * caller builds the list (see ProxyExtension.ALLOWED_SOURCES).
     */
    fun isAllowedSource(sourceAddress: String?, allowed: Set<String>): Boolean =
        sourceAddress != null && sourceAddress in allowed

    /**
     * Header lines to forward upstream: the client's lines minus
     * the proxy-specific ones (`Proxy-Connection`,
     * `Proxy-Authorization`), which must not leak to the origin —
     * the same two the reference proxy strips.
     */
    fun forwardableHeaders(headerLines: List<String>): List<String> =
        headerLines.filter { line ->
            if (line.isEmpty()) return@filter false
            val name = line.substringBefore(':').trim().lowercase()
            name != "proxy-connection" && name != "proxy-authorization"
        }

    /**
     * Read one header block from [input], byte at a time so body
     * bytes are never buffered (they must later be relayed
     * verbatim). Returns the block including the terminating
     * CRLFCRLF, the partial block at EOF, a block truncated at
     * [HEADER_LIMIT] when no terminator arrives in time, or null
     * when the stream is already at EOF — matching the reference
     * implementation's behaviour.
     */
    fun readHeaderBlock(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var state = 0 // matched chars of the \r\n\r\n terminator
        while (out.size() < HEADER_LIMIT) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> if (b == '\r'.code) 1 else 0
            }
            if (state == 4) break
        }
        return out.toByteArray()
    }

    /** Split a header block into lines (CRLF-separated). */
    fun headerLines(headerBlock: ByteArray): List<String> =
        String(headerBlock, Charsets.ISO_8859_1).split("\r\n")

    /** A minimal response head for proxy-generated errors. */
    fun simpleResponse(code: Int, message: String): ByteArray =
        "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1)

    /** The response head sent once a CONNECT tunnel is established. */
    val CONNECT_ESTABLISHED: ByteArray =
        "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
}

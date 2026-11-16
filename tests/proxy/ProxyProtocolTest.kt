import dev.moriya.vpn.extensions.ProxyProtocol
import java.io.ByteArrayInputStream

/**
 * Plain-JVM tests for ProxyProtocol (the proxy extension's pure
 * protocol logic). Run via tests/run-proxy-tests.sh.
 *
 * Only pure functions are tested here — deliberately no sockets:
 * JVM TCP is intercepted on the build VM, so socket/transit tests
 * cannot run there (the same fact that keeps Gradle off it). The
 * socket behaviour of ProxyServerCore is device-verified only.
 */

private var failures = 0
private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        failures++
        println("FAIL: $name")
    }
}

private fun <T> checkEq(name: String, expected: T, actual: T) {
    checks++
    if (expected != actual) {
        failures++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

fun main() {
    // -- parseRequestLine --
    checkEq(
        "request line GET",
        ProxyProtocol.RequestLine("GET", "http://example.com/", "HTTP/1.1"),
        ProxyProtocol.parseRequestLine("GET http://example.com/ HTTP/1.1"),
    )
    checkEq(
        "request line CONNECT lower-case method is upper-cased",
        ProxyProtocol.RequestLine("CONNECT", "example.com:443", "HTTP/1.1"),
        ProxyProtocol.parseRequestLine("connect example.com:443 HTTP/1.1"),
    )
    checkEq("request line two parts", null, ProxyProtocol.parseRequestLine("GET /"))
    checkEq(
        "request line four parts", null,
        ProxyProtocol.parseRequestLine("GET / HTTP/1.1 extra"),
    )
    checkEq(
        "request line bad version", null,
        ProxyProtocol.parseRequestLine("GET / HTTX/1.1"),
    )
    checkEq("request line empty", null, ProxyProtocol.parseRequestLine(""))

    // -- parseAuthority --
    checkEq(
        "authority host:port", "example.com" to 443,
        ProxyProtocol.parseAuthority("example.com:443", 443),
    )
    checkEq(
        "authority default port", "example.com" to 443,
        ProxyProtocol.parseAuthority("example.com", 443),
    )
    checkEq(
        "authority non-numeric port", null,
        ProxyProtocol.parseAuthority("example.com:https", 443),
    )
    checkEq(
        "authority port zero", null,
        ProxyProtocol.parseAuthority("example.com:0", 443),
    )
    checkEq(
        "authority port too large", null,
        ProxyProtocol.parseAuthority("example.com:65536", 443),
    )
    checkEq(
        "authority empty host", null,
        ProxyProtocol.parseAuthority(":8080", 443),
    )
    checkEq("authority empty", null, ProxyProtocol.parseAuthority("", 443))
    checkEq(
        "authority bracketed v6 with port", "::1" to 8080,
        ProxyProtocol.parseAuthority("[::1]:8080", 443),
    )
    checkEq(
        "authority bracketed v6 default port", "2001:db8::1" to 443,
        ProxyProtocol.parseAuthority("[2001:db8::1]", 443),
    )
    checkEq(
        "authority bare v6 is host-only", "::1" to 443,
        ProxyProtocol.parseAuthority("::1", 443),
    )
    checkEq(
        "authority unterminated bracket", null,
        ProxyProtocol.parseAuthority("[::1:8080", 443),
    )

    // -- parseHttpTarget --
    checkEq(
        "http target full",
        ProxyProtocol.HttpTarget("example.com", 80, "/path?q=1"),
        ProxyProtocol.parseHttpTarget("http://example.com/path?q=1"),
    )
    checkEq(
        "http target explicit port, empty path",
        ProxyProtocol.HttpTarget("example.com", 8080, "/"),
        ProxyProtocol.parseHttpTarget("http://example.com:8080"),
    )
    checkEq(
        "http target root with query",
        ProxyProtocol.HttpTarget("example.com", 80, "/?a=b"),
        ProxyProtocol.parseHttpTarget("http://example.com/?a=b"),
    )
    checkEq(
        "http target relative URI has no host", null,
        ProxyProtocol.parseHttpTarget("/just/a/path"),
    )
    checkEq(
        "http target garbage", null,
        ProxyProtocol.parseHttpTarget("http://exa mple.com/"),
    )

    // -- isAllowedSource --
    // Dummy addresses only (TEST-NET 192.0.2.0/24): real device
    // addresses never appear in this repository (see DESIGN.md).
    val allowed = setOf("192.0.2.10", "127.0.0.1")
    check("allowlist member", ProxyProtocol.isAllowedSource("192.0.2.10", allowed))
    check("allowlist loopback", ProxyProtocol.isAllowedSource("127.0.0.1", allowed))
    check("allowlist stranger", !ProxyProtocol.isAllowedSource("100.64.0.1", allowed))
    check("allowlist null source", !ProxyProtocol.isAllowedSource(null, allowed))
    check(
        "allowlist non-member address is NOT allowed",
        !ProxyProtocol.isAllowedSource("192.0.2.99", allowed),
    )

    // -- ipv4String (the generated allowlist's integer decoding) --
    checkEq("ipv4 integer decodes", "192.0.2.1", ProxyProtocol.ipv4String(3221225985L))
    checkEq("ipv4 integer zero", "0.0.0.0", ProxyProtocol.ipv4String(0L))
    checkEq("ipv4 integer max", "255.255.255.255", ProxyProtocol.ipv4String(4294967295L))
    checkEq("ipv4 integer high bit", "203.0.113.7", ProxyProtocol.ipv4String(3405803783L))

    // -- forwardableHeaders --
    checkEq(
        "proxy headers stripped, others kept in order",
        listOf("Host: example.com", "Content-Length: 5", "X-Test: 1"),
        ProxyProtocol.forwardableHeaders(
            listOf(
                "Host: example.com",
                "Proxy-Connection: keep-alive",
                "proxy-authorization: Basic abc",
                "Content-Length: 5",
                "",
                "X-Test: 1",
            ),
        ),
    )

    // -- readHeaderBlock --
    run {
        val body = "BODYBYTES"
        val stream = ByteArrayInputStream(
            "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n$body"
                .toByteArray(Charsets.ISO_8859_1),
        )
        val block = ProxyProtocol.readHeaderBlock(stream)
        check("header block read", block != null)
        checkEq(
            "header block content",
            "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n",
            block?.let { String(it, Charsets.ISO_8859_1) },
        )
        checkEq(
            "body bytes left unread (byte-wise read)",
            body,
            String(stream.readAllBytes(), Charsets.ISO_8859_1),
        )
    }
    run {
        val stream = ByteArrayInputStream(
            "PARTIAL WITHOUT TERMINATOR".toByteArray(Charsets.ISO_8859_1),
        )
        val block = ProxyProtocol.readHeaderBlock(stream)
        checkEq(
            "partial block at EOF",
            "PARTIAL WITHOUT TERMINATOR",
            block?.let { String(it, Charsets.ISO_8859_1) },
        )
    }
    run {
        val stream = ByteArrayInputStream(ByteArray(0))
        checkEq("empty stream is null", null, ProxyProtocol.readHeaderBlock(stream))
    }

    // -- responses --
    checkEq(
        "simple response shape",
        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        String(ProxyProtocol.simpleResponse(403, "Forbidden"), Charsets.ISO_8859_1),
    )
    checkEq(
        "connect established shape",
        "HTTP/1.1 200 Connection Established\r\n\r\n",
        String(ProxyProtocol.CONNECT_ESTABLISHED, Charsets.ISO_8859_1),
    )

    println("checks: $checks, failures: $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL PASS")
}

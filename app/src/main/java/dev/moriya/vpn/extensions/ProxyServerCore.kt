package dev.moriya.vpn.extensions

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Opens one outbound connection for the proxy. Implemented by the
 * Android side ([NetworkEgress]) so this core stays free of
 * Android types — and so tests can substitute a fake. Implementations
 * decide HOW the socket egresses (for the real one: bound to the
 * device's underlying network, never through our own TUN) and apply
 * the connect timeout; failures surface as [IOException].
 */
fun interface EgressConnector {
    fun connect(host: String, port: Int): Socket
}

/**
 * The proxy's socket core (v1.1.0, DESIGN.md §9.6): a forward proxy
 * listening on one address, speaking CONNECT and plain-HTTP
 * absolute-URI forwarding with a source allowlist — the semantics
 * of phone-node's native proxy, rewritten for this codebase.
 * Protocol decisions live in [ProxyProtocol]; this class is only
 * sockets, threads and counters.
 *
 * Concurrency is bounded on purpose (the host process is small and
 * must stay cheap to keep alive): at most [MAX_CONNECTIONS]
 * simultaneous client connections, further accepts are closed at
 * once and counted as rejected.
 */
class ProxyServerCore(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val allowedSources: Set<String>,
    private val egress: EgressConnector,
) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    private val permits = Semaphore(MAX_CONNECTIONS)
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "mokavpn-proxy-client").apply { isDaemon = true }
    }
    private val openSockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private val served = AtomicInteger(0)
    private val rejected = AtomicInteger(0)
    private val active = AtomicInteger(0)

    val isListening: Boolean get() = running && serverSocket != null
    val listenDescription: String get() = "${bindAddress.hostAddress}:$port"
    val servedCount: Int get() = served.get()
    val rejectedCount: Int get() = rejected.get()
    val activeCount: Int get() = active.get()

    /**
     * Bind and start accepting. Throws (e.g. BindException when the
     * address:port is taken) — the caller records the failure and
     * keeps the host alive; this class is never half-started.
     */
    fun start() {
        check(serverSocket == null) { "already started" }
        val socket = ServerSocket(port, BACKLOG, bindAddress)
        serverSocket = socket
        running = true
        thread(name = "mokavpn-proxy-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    // Closed by stop(), or a transient accept error.
                    break
                }
                if (!permits.tryAcquire()) {
                    rejected.incrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                active.incrementAndGet()
                openSockets.add(client)
                workers.execute {
                    try {
                        handle(client)
                    } finally {
                        openSockets.remove(client)
                        active.decrementAndGet()
                        served.incrementAndGet()
                        permits.release()
                    }
                }
            }
        }
    }

    /** Stop accepting and drop every live connection. Idempotent. */
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        openSockets.forEach { runCatching { it.close() } }
        openSockets.clear()
        workers.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use { c ->
            if (!ProxyProtocol.isAllowedSource(c.inetAddress?.hostAddress, allowedSources)) {
                rejected.incrementAndGet()
                writeSimple(c, 403, "Forbidden")
                return
            }
            c.soTimeout = IDLE_TIMEOUT_MS
            val input = c.getInputStream()
            val headerBytes = ProxyProtocol.readHeaderBlock(input) ?: return
            val lines = ProxyProtocol.headerLines(headerBytes)
            val request = ProxyProtocol.parseRequestLine(lines.firstOrNull() ?: "")
            if (request == null) {
                writeSimple(c, 400, "Bad Request")
                return
            }
            if (request.method == "CONNECT") {
                handleConnect(c, input, request.target)
            } else {
                handleHttp(c, input, lines, request)
            }
        }
    }

    private fun handleConnect(client: Socket, clientIn: InputStream, authority: String) {
        val (host, port) = ProxyProtocol.parseAuthority(authority, 443) ?: run {
            writeSimple(client, 400, "Bad Request")
            return
        }
        val upstream = try {
            egress.connect(host, port)
        } catch (_: Exception) {
            writeSimple(client, 502, "Bad Gateway")
            return
        }
        openSockets.add(upstream)
        try {
            upstream.use { up ->
                up.soTimeout = IDLE_TIMEOUT_MS
                val out = client.getOutputStream()
                out.write(ProxyProtocol.CONNECT_ESTABLISHED)
                out.flush()
                relayBoth(client, clientIn, up)
            }
        } finally {
            openSockets.remove(upstream)
        }
    }

    private fun handleHttp(
        client: Socket,
        clientIn: InputStream,
        lines: List<String>,
        request: ProxyProtocol.RequestLine,
    ) {
        val target = ProxyProtocol.parseHttpTarget(request.target) ?: run {
            writeSimple(client, 400, "Bad Request")
            return
        }
        val upstream = try {
            egress.connect(target.host, target.port)
        } catch (_: Exception) {
            writeSimple(client, 502, "Bad Gateway")
            return
        }
        openSockets.add(upstream)
        try {
            upstream.use { up ->
                up.soTimeout = IDLE_TIMEOUT_MS
                val head = StringBuilder()
                head.append(request.method).append(' ')
                    .append(target.path).append(' ')
                    .append(request.version).append("\r\n")
                for (line in ProxyProtocol.forwardableHeaders(lines.drop(1))) {
                    head.append(line).append("\r\n")
                }
                head.append("\r\n")
                val upOut = up.getOutputStream()
                upOut.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                upOut.flush()
                relayBoth(client, clientIn, up)
            }
        } finally {
            openSockets.remove(upstream)
        }
    }

    /** Pumps bytes in both directions until either side closes. */
    private fun relayBoth(client: Socket, clientIn: InputStream, upstream: Socket) {
        val upIn = upstream.getInputStream()
        val clientOut = client.getOutputStream()
        val upOut = upstream.getOutputStream()
        val t = thread(name = "mokavpn-proxy-pump", isDaemon = true) {
            pump(upIn, clientOut)
            runCatching { client.shutdownOutput() }
        }
        pump(clientIn, upOut)
        runCatching { upstream.shutdownOutput() }
        t.join(5_000)
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // Peer reset or timeout; the other direction ends via shutdown.
        }
    }

    private fun writeSimple(client: Socket, code: Int, message: String) {
        try {
            val out = client.getOutputStream()
            out.write(ProxyProtocol.simpleResponse(code, message))
            out.flush()
        } catch (_: Exception) {
            // Client already gone.
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 32
        private const val BACKLOG = 50

        /** Read-idle timeout on relayed sockets, as in the reference proxy. */
        private const val IDLE_TIMEOUT_MS = 120_000
    }
}

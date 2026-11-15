package dev.moriya.vpn.extensions

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
 * The settings actions behind the control endpoint, implemented
 * by [AdbKeepaliveExtension] (the Android side). Kept as an
 * interface so this core stays free of Android types, the same
 * split the proxy uses with its egress connector.
 */
interface AdbControlActions {

    /**
     * Write `adb_enabled` (the ONLY settings write the endpoint
     * performs). Returns the HTTP status and response body: 200
     * with the read-back value on success, 500 with the recorded
     * failure otherwise — a refused write is reported, never
     * thrown through here.
     */
    fun setUsbDebugging(on: Boolean): Pair<Int, String>

    /** The GET /status body (both toggle values + grant state). */
    fun statusBody(): String
}

/**
 * The ADB control endpoint's socket core (v1.3.0, DESIGN.md §9.7):
 * a deliberately tiny HTTP server on one bound address. Every
 * request runs through [AdbControlProtocol.authorize] — allowlist
 * AND token — before anything is dispatched; the three commands
 * are the whole surface. Audit state (accepted count, denied
 * count, last accepted command) is kept here as counters for the
 * extension's status line, mirroring [ProxyServerCore].
 *
 * Concurrency is bounded tighter than the proxy's (8): a control
 * endpoint has no bulk traffic, and the host process must stay
 * cheap to keep alive.
 */
class AdbControlServerCore(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val allowedSources: Set<String>,
    private val tokenProvider: () -> String?,
    private val actions: AdbControlActions,
) {

    /** One accepted command, for the status line's audit display. */
    data class AcceptedCommand(
        val action: String,
        val sourceAddress: String,
        val atMs: Long,
    )

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    private val permits = Semaphore(MAX_CONNECTIONS)
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "mokavpn-adb-control-client").apply { isDaemon = true }
    }
    private val openSockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private val accepted = AtomicInteger(0)
    private val denied = AtomicInteger(0)

    @Volatile var lastAccepted: AcceptedCommand? = null
        private set

    val isListening: Boolean get() = running && serverSocket != null
    val listenDescription: String get() = "${bindAddress.hostAddress}:$port"
    val acceptedCount: Int get() = accepted.get()
    val deniedCount: Int get() = denied.get()

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
        thread(name = "mokavpn-adb-control-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    // Closed by stop(), or a transient accept error.
                    break
                }
                if (!permits.tryAcquire()) {
                    denied.incrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                openSockets.add(client)
                workers.execute {
                    try {
                        handle(client)
                    } finally {
                        openSockets.remove(client)
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
            val source = c.inetAddress?.hostAddress
            c.soTimeout = IDLE_TIMEOUT_MS
            val input = c.getInputStream()
            val headerBytes = ProxyProtocol.readHeaderBlock(input)
            val lines = if (headerBytes != null) {
                ProxyProtocol.headerLines(headerBytes)
            } else {
                emptyList()
            }
            val request = ProxyProtocol.parseRequestLine(lines.firstOrNull() ?: "")
            if (request == null) {
                // Not a well-formed request at all: the endpoint's
                // vocabulary has no 400, so this is "anything else".
                denied.incrementAndGet()
                writeResponse(
                    c, AdbControlProtocol.httpStatus(
                        AdbControlProtocol.Decision.DENY_NOT_FOUND,
                    ),
                    AdbControlProtocol.denyBody(
                        AdbControlProtocol.Decision.DENY_NOT_FOUND,
                    ),
                )
                return
            }
            val decision = AdbControlProtocol.authorize(
                sourceAddress = source,
                allowedSources = allowedSources,
                presentedToken = AdbControlProtocol.tokenHeaderValue(lines.drop(1)),
                storedToken = tokenProvider(),
                method = request.method,
                path = request.target,
            )
            when (decision) {
                AdbControlProtocol.Decision.ALLOW_ON ->
                    dispatchWrite(c, on = true, source)
                AdbControlProtocol.Decision.ALLOW_OFF ->
                    dispatchWrite(c, on = false, source)
                AdbControlProtocol.Decision.ALLOW_STATUS -> {
                    accepted.incrementAndGet()
                    lastAccepted = AcceptedCommand(
                        "status", source ?: "unknown", System.currentTimeMillis(),
                    )
                    writeResponse(c, 200, actions.statusBody())
                }
                else -> {
                    denied.incrementAndGet()
                    writeResponse(
                        c,
                        AdbControlProtocol.httpStatus(decision),
                        AdbControlProtocol.denyBody(decision),
                    )
                }
            }
        }
    }

    private fun dispatchWrite(client: Socket, on: Boolean, source: String?) {
        val (code, body) = actions.setUsbDebugging(on)
        if (code == 200) {
            accepted.incrementAndGet()
            lastAccepted = AcceptedCommand(
                if (on) "on" else "off",
                source ?: "unknown",
                System.currentTimeMillis(),
            )
        }
        writeResponse(client, code, body)
    }

    private fun writeResponse(client: Socket, code: Int, body: String) {
        try {
            val out = client.getOutputStream()
            out.write(AdbControlProtocol.httpResponse(code, body))
            out.flush()
        } catch (_: Exception) {
            // Client already gone.
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        private const val BACKLOG = 10

        /** Read-idle timeout: requests are one small header block. */
        private const val IDLE_TIMEOUT_MS = 30_000
    }
}

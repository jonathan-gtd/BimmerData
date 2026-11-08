/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import io.github.jonathangtd.bimmerdata.api.tls.restrictToModernTls

/**
 * One parsed HTTP request. [path] is percent-decoded and has no query string; header names are
 * lower case.
 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress,
    /** True when the request came over TLS (HTTPS). */
    val secure: Boolean = false,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun queryParam(name: String): String? = query[name]?.firstOrNull()
    fun bodyText(): String = String(body, Charsets.UTF_8)
}

sealed class HttpResponse(val status: Int, val contentType: String, val headers: Map<String, String>) {
    /** A complete body, sent with Content-Length. */
    class Bytes(status: Int, contentType: String, val body: ByteArray, headers: Map<String, String> = emptyMap()) :
        HttpResponse(status, contentType, headers)

    /**
     * A body written progressively (Server-Sent Events): sent without Content-Length, then the
     * connection is closed. [write] returns when the stream ends; an [IOException] means the
     * client went away (or the server stops).
     */
    class Stream(status: Int, contentType: String, headers: Map<String, String> = emptyMap(), val write: (OutputStream) -> Unit) :
        HttpResponse(status, contentType, headers)

    companion object {
        fun text(status: Int, text: String, contentType: String = "text/plain; charset=utf-8", headers: Map<String, String> = emptyMap()) =
            Bytes(status, contentType, text.toByteArray(Charsets.UTF_8), headers)
    }
}

/** Limits of the server; the defaults suit a small LAN API on a phone. */
data class HttpLimits(
    val maxHeaderBytes: Int = 16 * 1024,
    val maxBodyBytes: Int = 1024 * 1024,
    val maxConnections: Int = 16,
    val maxRequestsPerConnection: Int = 500,
    /** Idle keep-alive and slow-client read timeout. */
    val readTimeoutMs: Int = 15_000,
    /** A TLS client must finish its handshake within this time. */
    val handshakeTimeoutMs: Int = 10_000,
    /** Long-lived responses (event streams) at the same time; they do not use connection slots. */
    val maxStreams: Int = 4,
    /** Watchdog period: checks that every listening socket still accepts (0 = no watchdog). */
    val watchdogPeriodMs: Long = 20_000,
)

/**
 * Minimal HTTP/1.1 server on plain [java.net] sockets: no dependency, identical behaviour on the
 * JVM (tests) and on Android. Supports keep-alive, Content-Length request bodies (no chunked
 * uploads), `Expect: 100-continue` and streamed responses. Each connection is served by one thread
 * of a bounded pool; requests above [HttpLimits.maxConnections] get 503.
 *
 * It is meant for a token-protected LAN API, not for the internet. With an [SSLContext] given to
 * [start] it speaks HTTPS only (TLS 1.3 / 1.2, see [restrictToModernTls]): a plain HTTP client
 * fails the handshake and gets no answer.
 *
 * Nothing can stop the server from accepting connections:
 * - the listening sockets are plain TCP sockets; TLS is layered on each accepted connection on its
 *   own worker thread, with a handshake timeout, so the accept loop never reads from a client;
 * - the accept loop survives any exception (it logs it and goes on);
 * - every read has a timeout; event streams use their own slots ([HttpLimits.maxStreams]) and do
 *   not hold connection slots;
 * - a watchdog connects to each listening socket now and then; one whose pending connection is not
 *   accepted within a few seconds is closed and bound again.
 */
class HttpServer(
    private val handler: (HttpRequest) -> HttpResponse,
    private val limits: HttpLimits = HttpLimits(),
    /** Connections from other addresses are closed without an answer. */
    private val acceptPeer: (InetAddress) -> Boolean = { true },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val sockets = Collections.synchronizedList(ArrayList<ServerSocket>())
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private val slots = Semaphore(limits.maxConnections)
    private val streamSlots = Semaphore(limits.maxStreams)
    private val threadIds = AtomicInteger()
    /** Connections accepted per listening socket (watchdog). */
    private val accepted = ConcurrentHashMap<ServerSocket, AtomicLong>()
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var tlsContext: SSLContext? = null
    @Volatile private var watchdog: Thread? = null
    @Volatile private var running = false

    /** Listening sockets bound again by the watchdog (tests, diagnostics). */
    val rebinds = AtomicInteger()

    /** True while serving HTTPS. */
    @Volatile var secure: Boolean = false
        private set

    /** Port actually bound (useful when started with port 0), or -1 when stopped. */
    @Volatile var port: Int = -1
        private set

    /** Addresses actually bound. */
    val boundAddresses: List<InetAddress> get() = synchronized(sockets) { sockets.map { it.inetAddress } }

    /**
     * Binds [port] on each of [addresses] (0 = pick a free port, then the same one everywhere).
     * Addresses that cannot be bound are skipped; throws if none could be bound. With [tls] every
     * socket is a TLS server socket (HTTPS only).
     */
    @Synchronized
    fun start(addresses: List<InetAddress>, port: Int, tls: SSLContext? = null) {
        check(!running) { "already started" }
        require(addresses.isNotEmpty()) { "no address to bind" }
        val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "bd-api-" + threadIds.incrementAndGet()).apply { isDaemon = true }
        }
        var chosen = port
        var lastError: IOException? = null
        for (address in addresses.distinct()) {
            try {
                val ss = bindSocket(address, chosen)
                chosen = ss.localPort
                sockets += ss
            } catch (e: IOException) {
                lastError = e
                log("Cannot bind ${address.hostAddress}:$chosen", e)
            }
        }
        if (sockets.isEmpty()) {
            executor.shutdownNow()
            throw lastError ?: IOException("Cannot bind any address")
        }
        pool = executor
        tlsContext = tls
        running = true
        secure = tls != null
        this.port = chosen
        for (ss in sockets.toList()) startAcceptLoop(ss)
        if (limits.watchdogPeriodMs > 0) {
            watchdog = Thread({ watchdogLoop() }, "bd-api-watchdog").apply { isDaemon = true; start() }
        }
    }

    private fun bindSocket(address: InetAddress, port: Int): ServerSocket {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(address, port), 50)
        } catch (e: IOException) {
            runCatching { ss.close() }
            throw e
        }
        return ss
    }

    private fun startAcceptLoop(ss: ServerSocket) {
        accepted[ss] = AtomicLong()
        Thread({ acceptLoop(ss) }, "bd-api-accept-" + ss.inetAddress.hostAddress).apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        watchdog?.interrupt()
        watchdog = null
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
        accepted.clear()
        tlsContext = null
        synchronized(clients) { clients.forEach { runCatching { it.close() } }; clients.clear() }
        pool?.shutdownNow()
        pool = null
        port = -1
        secure = false
    }

    val isRunning: Boolean get() = running

    private fun acceptLoop(ss: ServerSocket) {
        var failures = 0
        while (running && !ss.isClosed) {
            try {
                val socket = ss.accept()
                accepted[ss]?.incrementAndGet()
                failures = 0
                dispatch(socket)
            } catch (t: Throwable) {
                if (!running || ss.isClosed) break
                // Never let the loop die: a dead accept loop leaves a socket that queues and never answers.
                log("accept loop: ${t.javaClass.simpleName}", t)
                failures++
                if (failures > 3) runCatching { Thread.sleep((failures * 50L).coerceAtMost(1_000)) }
            }
        }
    }

    /** Hands an accepted connection to a worker; never blocks and never reads from the client. */
    private fun dispatch(socket: Socket) {
        if (!acceptPeer(socket.inetAddress)) {
            runCatching { socket.close() }
            return
        }
        val executor = pool
        if (executor == null) {
            runCatching { socket.close() }
            return
        }
        if (!slots.tryAcquire()) {
            // Busy: answer 503 from a worker (plain HTTP only; for HTTPS a refusal needs a handshake: just close).
            if (tlsContext != null) {
                runCatching { socket.close() }
            } else {
                runCatching {
                    executor.execute {
                        runCatching {
                            socket.soTimeout = 2_000
                            writeSimple(socket.getOutputStream(), 503, "busy", "Too many connections")
                        }
                        runCatching { socket.close() }
                    }
                }.onFailure { runCatching { socket.close() } }
            }
            return
        }
        clients += socket
        val slot = SlotHolder(slots)
        try {
            executor.execute {
                try {
                    serve(socket, slot)
                } catch (t: Throwable) {
                    if (running) log("connection: ${t.javaClass.simpleName}", t)
                } finally {
                    clients -= socket
                    runCatching { socket.close() }
                    slot.release()
                }
            }
        } catch (t: Throwable) {
            slot.release(); clients -= socket; runCatching { socket.close() }
        }
    }

    /** A connection slot, released once (early for event streams, which use [streamSlots]). */
    private class SlotHolder(private val semaphore: Semaphore) {
        private var held = true

        @Synchronized
        fun release() {
            if (held) {
                held = false
                semaphore.release()
            }
        }
    }

    /**
     * Connects to each listening socket and checks that the connection gets accepted; a socket
     * whose accept loop is stuck or gone is closed and bound again on the same address and port.
     */
    private fun watchdogLoop() {
        while (running) {
            try {
                Thread.sleep(limits.watchdogPeriodMs)
            } catch (_: InterruptedException) {
                return
            }
            for (ss in synchronized(sockets) { sockets.toList() }) {
                if (!running) return
                runCatching { checkAccepting(ss) }.onFailure { log("watchdog", it) }
            }
        }
    }

    private fun checkAccepting(ss: ServerSocket) {
        val counter = accepted[ss] ?: return
        val before = counter.get()
        val probe = Socket()
        try {
            // Not being able to connect at all is not the accept loop's fault (network rules): skip.
            probe.connect(InetSocketAddress(ss.inetAddress, ss.localPort), 2_000)
        } catch (_: IOException) {
            runCatching { probe.close() }
            return
        }
        try {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (counter.get() == before && System.nanoTime() < deadline) Thread.sleep(50)
        } finally {
            runCatching { probe.close() }
        }
        if (counter.get() != before || !running) return
        log("watchdog: ${ss.inetAddress.hostAddress}:${ss.localPort} does not accept connections any more, binding it again", null)
        rebind(ss)
    }

    private fun rebind(old: ServerSocket) {
        val address = old.inetAddress
        val port = old.localPort
        synchronized(sockets) { sockets -= old }
        accepted.remove(old)
        runCatching { old.close() }
        if (!running) return
        val fresh = try {
            bindSocket(address, port)
        } catch (e: IOException) {
            log("watchdog: cannot bind ${address.hostAddress}:$port again", e)
            return
        }
        synchronized(sockets) { sockets += fresh }
        rebinds.incrementAndGet()
        startAcceptLoop(fresh)
    }

    private fun serve(raw: Socket, slot: SlotHolder) {
        raw.tcpNoDelay = true
        val tls = tlsContext
        val socket: Socket = if (tls != null) {
            raw.soTimeout = limits.handshakeTimeoutMs
            val ssl = tls.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
            ssl.useClientMode = false
            ssl.restrictToModernTls()
            try {
                ssl.startHandshake()
            } catch (e: IOException) {
                // plain HTTP on the HTTPS port, old TLS version, client that does not trust us, silent client...
                runCatching { ssl.close() }
                return
            }
            clients += ssl
            ssl
        } else {
            raw
        }
        socket.soTimeout = limits.readTimeoutMs
        try {
            serveHttp(socket, slot)
        } finally {
            if (socket !== raw) {
                clients -= socket
                runCatching { socket.close() }
            }
        }
    }

    private fun serveHttp(socket: Socket, slot: SlotHolder) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var served = 0
        try {
            while (running) {
                val head = try {
                    readHead(input) ?: return
                } catch (e: HttpError) {
                    writeSimple(output, e.status, e.code, e.message ?: "")
                    return
                }
                val request = try {
                    parse(head, input, output, socket.inetAddress, socket is SSLSocket)
                } catch (e: HttpError) {
                    writeSimple(output, e.status, e.code, e.message ?: "")
                    return
                }
                served++
                val keepAlive = wantsKeepAlive(head) && served < limits.maxRequestsPerConnection
                val response = try {
                    handler(request)
                } catch (e: Exception) {
                    log("Handler failed for ${request.method} ${request.path}", e)
                    HttpResponse.text(500, errorJson("internal_error", "Internal error"), JSON)
                }
                when (response) {
                    is HttpResponse.Bytes -> {
                        writeHead(output, response.status, response.contentType, response.headers, response.body.size.toLong(), keepAlive)
                        if (request.method != "HEAD") output.write(response.body)
                        output.flush()
                        if (!keepAlive) return
                    }
                    is HttpResponse.Stream -> {
                        if (!streamSlots.tryAcquire()) {
                            writeSimple(output, 503, "busy", "Too many open streams (at most ${limits.maxStreams})")
                            return
                        }
                        // the stream may last long: it gives its connection slot back to normal requests
                        slot.release()
                        try {
                            writeHead(output, response.status, response.contentType, response.headers, null, false)
                            output.flush()
                            response.write(output)
                            output.flush()
                        } finally {
                            streamSlots.release()
                        }
                        return
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            // idle keep-alive connection
        } catch (_: SocketException) {
            // client closed or server stopping
        } catch (e: IOException) {
            if (running) log("connection error", e)
        }
    }

    private class Head(val method: String, val target: String, val version: String, val headers: Map<String, String>)

    private class HttpError(val status: Int, val code: String, message: String) : Exception(message)

    /** Reads up to the empty line; null on a clean end of stream before any byte. */
    private fun readHead(input: InputStream): Head? {
        val buf = ByteArrayOutputStream(512)
        var state = 0 // matches \r\n\r\n (also accepts bare \n\n)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw HttpError(400, "bad_request", "Truncated request")
            }
            // Ignore blank lines before a request (RFC 9112 2.2).
            if (buf.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue
            buf.write(b)
            if (buf.size() > limits.maxHeaderBytes) throw HttpError(431, "headers_too_large", "Request headers too large")
            state = when {
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\n'.code && state == 4 -> 4
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && state == 0 -> 2 // bare LF
                b == '\n'.code && state == 2 -> 4
                else -> 0
            }
            if (state == 4) break
        }
        val lines = String(buf.toByteArray(), Charsets.ISO_8859_1).split("\n").map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "bad_request", "Malformed request line")
        val method = parts[0]
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) throw HttpError(400, "bad_request", "Malformed method")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line[0] == ' ' || line[0] == '\t') throw HttpError(400, "bad_request", "Folded headers are not supported")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "bad_request", "Malformed header")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
            if (headers.size > 100) throw HttpError(431, "headers_too_large", "Too many headers")
        }
        return Head(method, parts[1], parts[2], headers)
    }

    private fun parse(head: Head, input: InputStream, output: OutputStream, remote: InetAddress, secure: Boolean): HttpRequest {
        if (!head.target.startsWith("/")) throw HttpError(400, "bad_request", "Only origin-form request targets are supported")
        if (head.headers.containsKey("transfer-encoding")) throw HttpError(501, "not_implemented", "Chunked request bodies are not supported; send Content-Length")
        val length = head.headers["content-length"]?.let { v ->
            v.toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400, "bad_request", "Invalid Content-Length")
        } ?: 0L
        if (length > limits.maxBodyBytes) throw HttpError(413, "body_too_large", "Request body larger than ${limits.maxBodyBytes} bytes")
        if (length > 0 && head.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw HttpError(400, "bad_request", "Truncated body")
            read += n
        }
        val q = head.target.indexOf('?')
        val rawPath = if (q >= 0) head.target.substring(0, q) else head.target
        val rawQuery = if (q >= 0) head.target.substring(q + 1) else ""
        val path = try {
            percentDecode(rawPath, plusIsSpace = false)
        } catch (e: IllegalArgumentException) {
            throw HttpError(400, "bad_request", "Malformed path encoding")
        }
        val query = LinkedHashMap<String, MutableList<String>>()
        if (rawQuery.isNotEmpty()) {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                try {
                    val k = percentDecode(if (eq >= 0) pair.substring(0, eq) else pair, true)
                    val v = if (eq >= 0) percentDecode(pair.substring(eq + 1), true) else ""
                    query.getOrPut(k) { ArrayList() }.add(v)
                } catch (e: IllegalArgumentException) {
                    throw HttpError(400, "bad_request", "Malformed query encoding")
                }
            }
        }
        return HttpRequest(head.method, path, query, head.headers, body, remote, secure)
    }

    private fun wantsKeepAlive(head: Head): Boolean {
        val conn = head.headers["connection"]?.lowercase().orEmpty()
        return if (head.version == "HTTP/1.0") conn.contains("keep-alive") else !conn.contains("close")
    }

    private fun writeHead(out: OutputStream, status: Int, contentType: String, headers: Map<String, String>, length: Long?, keepAlive: Boolean) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        for ((k, v) in headers) {
            require(k.none { it == '\r' || it == '\n' } && v.none { it == '\r' || it == '\n' }) { "header injection" }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeSimple(out: OutputStream, status: Int, code: String, message: String) {
        runCatching {
            val body = errorJson(code, message).toByteArray(Charsets.UTF_8)
            writeHead(out, status, JSON, emptyMap(), body.size.toLong(), false)
            out.write(body)
            out.flush()
        }
    }

    companion object {
        const val JSON = "application/json; charset=utf-8"

        /** `{"error":{"code":…,"message":…}}`, the error shape of the whole API. */
        fun errorJson(code: String, message: String): String =
            "{\"error\":{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}}"

        private fun escape(s: String) = buildString {
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }

        fun reason(status: Int): String = when (status) {
            100 -> "Continue"
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Content Too Large"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Content"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "Status"
        }

        /** RFC 3986 percent-decoding as UTF-8; [plusIsSpace] for form-style query strings. */
        fun percentDecode(s: String, plusIsSpace: Boolean): String {
            if (s.indexOf('%') < 0 && !(plusIsSpace && s.indexOf('+') >= 0)) return s
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' -> {
                        require(i + 3 <= s.length) { "bad escape" }
                        val hi = Character.digit(s[i + 1], 16)
                        val lo = Character.digit(s[i + 2], 16)
                        require(hi >= 0 && lo >= 0) { "bad escape" }
                        out.write(hi * 16 + lo)
                        i += 3
                    }
                    c == '+' && plusIsSpace -> { out.write(' '.code); i++ }
                    else -> {
                        val bytes = c.toString().toByteArray(Charsets.UTF_8)
                        out.write(bytes, 0, bytes.size)
                        i++
                    }
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}

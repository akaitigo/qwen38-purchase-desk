package com.example.purchasedesk.http

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A decoded incoming request. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val bodyString: String get() = String(body, StandardCharsets.UTF_8)
    val header: (String) -> String? = { name ->
        headers[name.lowercase()]
    }
}

class HttpResponse(
    var status: Int,
    val headers: MutableMap<String, MutableList<String>> = linkedMapOf(),
    var body: ByteArray = ByteArray(0)
) {
    fun header(name: String, value: String) {
        headers.getOrPut(name) { mutableListOf() }.add(value)
    }
}

/** A small handler abstraction: returns a [HttpResponse] for a request. */
typealias Handler = (HttpRequest) -> HttpResponse

/**
 * Minimal HTTP/1.1 server on a plain ServerSocket. No frameworks.
 * Each connection is handled on its own worker thread; keep-alive is
 * supported so browsers and `curl` can reuse connections.
 */
class HttpServer(
    host: String,
    port: Int,
    private val handler: Handler
) : AutoCloseable {

    private val serverSocket = ServerSocket()
    private val running = AtomicBoolean(true)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "http").apply { isDaemon = true }
    }

    init {
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(host, port))
        serverSocket.soTimeout = 500
        pool.submit { acceptLoop() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val sock = serverSocket.accept()
                pool.submit { handleConnection(sock) }
            } catch (e: java.net.SocketTimeoutException) {
                // poll the running flag
            } catch (e: Exception) {
                if (running.get()) {
                    // unexpected
                }
            }
        }
    }

    fun actualPort(): Int = serverSocket.localPort

    private fun handleConnection(sock: java.net.Socket) {
        try {
            sock.soTimeout = 30_000
            val inStream = sock.getInputStream()
            val out = sock.getOutputStream()
            // Read request lines and headers.
            val requestLine = readLine(inStream) ?: return
            if (requestLine.isBlank()) return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val (path, query) = splitTarget(target)

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(inStream) ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val body: ByteArray = when {
                "content-length" in headers -> {
                    val len = headers["content-length"]!!.toIntOrNull() ?: 0
                    if (len < 0) return
                    readFully(inStream, len)
                }
                "transfer-encoding" in headers -> ByteArray(0)
                else -> ByteArray(0)
            }

            val req = HttpRequest(method, path, query, headers, body)
            val res: HttpResponse = try {
                handler(req)
            } catch (e: Exception) {
                // Never leak a stack trace.
                jsonResponse(500, """{"error":"サーバー内部エラーです。"}""")
            }
            writeResponse(out, res)
            out.flush()
        } catch (_: Exception) {
            // per-connection failures are non-fatal
        } finally {
            try { sock.close() } catch (_: Exception) { }
        }
    }

    private fun readLine(s: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = s.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readFully(s: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.read(buf, off, n - off)
            if (r == -1) break
            off += r
        }
        return if (off < n) buf.copyOf(off) else buf
    }

    private fun splitTarget(target: String): Pair<String, Map<String, String>> {
        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val queryStr = if (qIdx >= 0) target.substring(qIdx + 1) else ""
        val query = if (queryStr.isEmpty()) emptyMap()
        else queryStr.split('&').mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) URLDecoder.decode(it, "UTF-8") to null
            else URLDecoder.decode(it.substring(0, idx), "UTF-8") to
                    URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }.filter { it.second != null }.associate { it.first to it.second!! }
        return path to query
    }

    private fun writeResponse(out: OutputStream, res: HttpResponse) {
        val reason = when (res.status) {
            in 200..299 -> "OK"
            in 300..399 -> "Found"
            in 400..499 -> "Error"
            else -> "Error"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${res.status} $reason\r\n")
        val hasCt = res.headers.any { it.key.equals("content-type", true) }
        val hasCl = res.headers.any { it.key.equals("content-length", true) }
        for ((k, vs) in res.headers) {
            for (v in vs) {
                sb.append("$k: $v\r\n")
            }
        }
        if (!hasCt) sb.append("Content-Type: application/json; charset=utf-8\r\n")
        if (!hasCl) sb.append("Content-Length: ${res.body.size}\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(res.body)
    }

    override fun close() {
        running.set(false)
        try { serverSocket.close() } catch (_: Exception) { }
        pool.shutdownNow()
    }
}

/** Convenience constructors. */
fun jsonResponse(status: Int, json: String, cookie: String? = null): HttpResponse {
    val r = HttpResponse(status)
    r.header("Content-Type", "application/json; charset=utf-8")
    r.body = json.toByteArray()
    if (cookie != null) r.header("Set-Cookie", cookie)
    return r
}

fun htmlResponse(status: Int, html: String, cookie: String? = null): HttpResponse {
    val r = HttpResponse(status)
    r.header("Content-Type", "text/html; charset=utf-8")
    r.body = html.toByteArray()
    if (cookie != null) r.header("Set-Cookie", cookie)
    return r
}

fun redirect(status: Int, location: String): HttpResponse {
    val r = HttpResponse(status)
    r.header("Location", location)
    r.header("Content-Length", "0")
    return r
}

fun cookieValue(
    name: String,
    value: String,
    maxAge: Int? = null,
    httpOnly: Boolean = true,
    secure: Boolean = false,
    samesite: String = "Lax"
): String {
    val sb = StringBuilder()
    sb.append("$name=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}")
    sb.append("; Path=/")
    sb.append("; HttpOnly")
    if (secure) sb.append("; Secure")
    sb.append("; SameSite=$samesite")
    if (maxAge != null) sb.append("; Max-Age=$maxAge")
    return sb.toString()
}

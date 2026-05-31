package com.truthshield.androidproxies

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HttpProxyServer(
    private val bindAddress: String,
    private val port: Int,
    private val router: NetworkRouter? = null,
    private val useCellular: Boolean = false,
) {

    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, port))
        serverSocket = s
        acceptThread = Thread({ acceptLoop(s) }, "http-proxy-accept").also { it.start() }
        Log.i(TAG, "Proxy listening on $bindAddress:$port")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        workers.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept failed", e)
                break
            }
            workers.submit { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = READ_TIMEOUT_MS
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            val requestLine = readLine(cin) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 3) {
                writeStatus(cout, 400, "Bad Request"); return
            }
            val method = parts[0]
            val target = parts[1]
            val version = parts[2]

            val headers = mutableListOf<String>()
            while (true) {
                val line = readLine(cin) ?: break
                if (line.isEmpty()) break
                headers += line
                if (headers.size > MAX_HEADERS) {
                    writeStatus(cout, 431, "Request Header Fields Too Large"); return
                }
            }

            if (method.equals("CONNECT", ignoreCase = true)) {
                val (host, port) = splitHostPort(target, 443) ?: run {
                    writeStatus(cout, 400, "Bad Request"); return
                }
                upstream = openUpstream(host, port) ?: run {
                    writeStatus(cout, 502, "Bad Gateway"); return
                }
                cout.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: AndroidProxies/0.1\r\n\r\n".toByteArray())
                cout.flush()
                pipe(client, upstream)
            } else {
                val uri = try { URI(target) } catch (_: Exception) { null }
                if (uri?.host == null) { writeStatus(cout, 400, "Bad Request"); return }
                val host = uri.host
                val port = if (uri.port == -1) 80 else uri.port
                val path = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")

                upstream = openUpstream(host, port) ?: run {
                    writeStatus(cout, 502, "Bad Gateway"); return
                }
                val uout = upstream.getOutputStream()
                val sb = StringBuilder()
                sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
                var contentLength = 0L
                var chunked = false
                for (h in headers) {
                    val colon = h.indexOf(':')
                    if (colon <= 0) continue
                    val name = h.substring(0, colon).trim()
                    val value = h.substring(colon + 1).trim()
                    if (name.equals("Proxy-Connection", true)) continue
                    if (name.equals("Connection", true)) continue
                    if (name.equals("Content-Length", true)) contentLength = value.toLongOrNull() ?: 0L
                    if (name.equals("Transfer-Encoding", true) && value.contains("chunked", true)) chunked = true
                    sb.append(name).append(": ").append(value).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                uout.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                uout.flush()

                when {
                    chunked -> copyChunked(cin, uout, bytesUp)
                    contentLength > 0 -> copyExact(cin, uout, contentLength, bytesUp)
                }
                uout.flush()

                pipeOneWay(upstream.getInputStream(), cout, bytesDown)
            }
        } catch (e: Exception) {
            Log.d(TAG, "handle: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
        }
    }

    private fun openUpstream(host: String, port: Int): Socket? {
        val s = Socket()
        s.tcpNoDelay = true
        if (useCellular) {
            val net = router?.cellular()
            if (net == null) {
                Log.w(TAG, "cellular network not available; refusing upstream connect")
                try { s.close() } catch (_: Exception) {}
                return null
            }
            try {
                net.bindSocket(s)
            } catch (e: Exception) {
                // Do NOT fall through to an unbound connect: that would exit
                // over the default network (Wi-Fi) and leak the proxied traffic
                // off cellular. Refuse instead.
                Log.w(TAG, "bindSocket(cellular) failed; refusing to avoid Wi-Fi leak", e)
                try { s.close() } catch (_: Exception) {}
                return null
            }
        }
        return try {
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            Log.d(TAG, "upstream connect to $host:$port failed: ${e.message}")
            null
        }
    }

    private fun pipe(a: Socket, b: Socket) {
        val t1 = Thread { pipeOneWay(a.getInputStream(), b.getOutputStream(), bytesUp) }
        val t2 = Thread { pipeOneWay(b.getInputStream(), a.getOutputStream(), bytesDown) }
        t1.start(); t2.start()
        t1.join(); t2.join()
    }

    private fun pipeOneWay(input: InputStream, output: OutputStream, counter: AtomicLong?) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                counter?.addAndGet(n.toLong())
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { output.flush() } catch (_: Exception) {}
        }
    }

    private fun copyExact(input: InputStream, output: OutputStream, total: Long, counter: AtomicLong?) {
        val buf = ByteArray(16 * 1024)
        var remaining = total
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            output.write(buf, 0, n)
            counter?.addAndGet(n.toLong())
            remaining -= n
        }
    }

    private fun copyChunked(input: InputStream, output: OutputStream, counter: AtomicLong?) {
        while (true) {
            val sizeLine = readLine(input) ?: return
            output.write(sizeLine.toByteArray(Charsets.ISO_8859_1)); output.write(CRLF)
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: return
                    output.write(trailer.toByteArray(Charsets.ISO_8859_1)); output.write(CRLF)
                    if (trailer.isEmpty()) return
                }
            }
            copyExact(input, output, size.toLong(), counter)
            readLine(input)
            output.write(CRLF)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty() && prev == -1) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) return sb.substring(0, sb.length - 1)
            sb.append(b.toChar())
            prev = b
            if (sb.length > MAX_LINE) return null
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        try {
            out.write("HTTP/1.1 $code $reason\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: Exception) {}
    }

    private fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int>? {
        val idx = s.lastIndexOf(':')
        if (idx < 0) return s to defaultPort
        val host = s.substring(0, idx)
        val port = s.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }

    companion object {
        private const val TAG = "HttpProxyServer"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_HEADERS = 128
        private const val MAX_LINE = 16_384
        private val CRLF = byteArrayOf(0x0D, 0x0A)
    }
}

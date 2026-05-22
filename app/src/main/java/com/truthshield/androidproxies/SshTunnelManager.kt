package com.truthshield.androidproxies

import android.util.Log
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.IOException
import java.net.InetSocketAddress
import java.security.Security
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SshTunnelManager(
    private val cfg: Config,
    private val router: NetworkRouter?,
    private val onStatus: (String) -> Unit,
) {
    data class Config(
        val host: String,
        val port: Int,
        val user: String,
        val privateKeyPem: String,
        val password: String,
        val remoteBindHost: String,
        val remoteBindPort: Int,
        val localProxyHost: String,
        val localProxyPort: Int,
        val killStaleByPort: Boolean,
        val useWifi: Boolean,
    )

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var client: SSHClient? = null
    private val kickLock = Object()
    @Volatile private var kicked = false
    @Volatile private var paused = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ supervise() }, "ssh-tunnel").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { client?.disconnect() } catch (_: Exception) {}
        synchronized(kickLock) { kickLock.notifyAll() }
        thread?.interrupt()
    }

    fun kickReconnect() {
        val c = client
        if (c != null && c.isConnected) return
        synchronized(kickLock) {
            kicked = true
            kickLock.notifyAll()
        }
    }

    fun pause() {
        paused = true
        try { client?.disconnect() } catch (_: Exception) {}
        synchronized(kickLock) { kickLock.notifyAll() }
    }

    fun resume() {
        paused = false
        synchronized(kickLock) {
            kicked = true
            kickLock.notifyAll()
        }
    }

    fun isPaused(): Boolean = paused

    private fun supervise() {
        var backoffMs = 2_000L
        while (running.get()) {
            synchronized(kickLock) {
                while (running.get() && paused) {
                    try { kickLock.wait() } catch (_: InterruptedException) { return }
                }
            }
            if (!running.get()) break

            try {
                runSession()
                backoffMs = 2_000L
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!paused) onStatus("SSH error: ${e.message}")
                Log.w(TAG, "session error", e)
            }
            if (!running.get()) break
            if (paused) continue

            synchronized(kickLock) {
                if (!paused && !kicked) {
                    try { kickLock.wait(backoffMs) } catch (_: InterruptedException) { return }
                }
                if (kicked) {
                    kicked = false
                    backoffMs = 2_000L
                    if (!paused) onStatus("Reconnecting…")
                } else if (!paused) {
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
            }
        }
        onStatus("Tunnel stopped")
    }

    private fun runSession() {
        val c = SSHClient(DefaultConfig())
        c.addHostKeyVerifier(PromiscuousVerifier())
        c.connectTimeout = 15_000
        c.timeout = 30_000
        c.connection.keepAlive.keepAliveInterval = 15

        if (cfg.useWifi) {
            val r = router ?: throw IOException("Wi-Fi routing requested but no NetworkRouter")
            onStatus("Waiting for Wi-Fi to bind SSH socket…")
            val wifi = r.waitForWifi(10_000)
                ?: throw IOException("Wi-Fi network not available")
            c.socketFactory = BoundSocketFactory(wifi)
            onStatus("Connecting to ${cfg.host}:${cfg.port} via Wi-Fi…")
        } else {
            onStatus("Connecting to ${cfg.host}:${cfg.port}…")
        }

        c.connect(cfg.host, cfg.port)
        client = c

        try {
            if (cfg.privateKeyPem.isNotBlank()) {
                val keyProvider = c.loadKeys(cfg.privateKeyPem, null, null)
                c.authPublickey(cfg.user, keyProvider)
                onStatus("Authenticated as ${cfg.user} (key)")
            } else {
                c.authPassword(cfg.user, cfg.password)
                onStatus("Authenticated as ${cfg.user} (password)")
            }

            if (cfg.killStaleByPort) {
                killStaleHolderOfPort(c, cfg.remoteBindPort)
            }

            val forward = RemotePortForwarder.Forward(cfg.remoteBindHost, cfg.remoteBindPort)
            val listener = SocketForwardingConnectListener(InetSocketAddress(cfg.localProxyHost, cfg.localProxyPort))
            val bound = bindWithRetry(c, forward, listener)
            onStatus("Tunnel up: server:${bound.port} → phone:${cfg.localProxyPort}")

            while (running.get() && !paused && c.isConnected) {
                Thread.sleep(2_000)
            }
        } finally {
            try { c.disconnect() } catch (_: Exception) {}
            client = null
        }

        if (running.get()) throw IOException("Connection closed")
    }

    private fun killStaleHolderOfPort(c: SSHClient, port: Int) {
        val script = """
            PORT=$port
            pids=${'$'}({ fuser -n tcp ${'$'}PORT 2>/dev/null; lsof -ti tcp:${'$'}PORT 2>/dev/null; ss -Hntlp "sport = :${'$'}PORT" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2; } | tr '\n' ' ' | tr -s ' ')
            me=${'$'}PPID
            killed=0
            for p in ${'$'}pids; do
              [ -z "${'$'}p" ] && continue
              [ "${'$'}p" = "${'$'}me" ] && continue
              n=${'$'}(ps -o comm= -p "${'$'}p" 2>/dev/null)
              case "${'$'}n" in
                sshd*) kill -9 "${'$'}p" 2>/dev/null && killed=${'$'}((killed+1)) ;;
              esac
            done
            sleep 1
            echo "killed=${'$'}killed"
        """.trimIndent()
        val session = c.startSession()
        try {
            val cmd = session.exec(script)
            cmd.join(10, TimeUnit.SECONDS)
            val output = cmd.inputStream.bufferedReader().readText().trim()
            onStatus("Stale-port cleanup: $output")
        } catch (e: Exception) {
            Log.w(TAG, "stale cleanup failed", e)
            onStatus("Stale-port cleanup failed: ${e.message}")
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    private fun bindWithRetry(
        c: SSHClient,
        forward: RemotePortForwarder.Forward,
        listener: SocketForwardingConnectListener,
    ): RemotePortForwarder.Forward {
        var attempt = 0
        while (running.get() && c.isConnected) {
            try {
                return c.remotePortForwarder.bind(forward, listener)
            } catch (e: ConnectionException) {
                attempt++
                val waitS = (10 + attempt * 10).coerceAtMost(60)
                onStatus("Server port ${cfg.remoteBindPort} busy (attempt $attempt) — retry in ${waitS}s. Old session not yet reaped.")
                Log.w(TAG, "bind failed: ${e.message}")
                try { Thread.sleep(waitS * 1000L) } catch (_: InterruptedException) {
                    throw IOException("Interrupted while waiting to retry bind")
                }
            }
        }
        throw IOException(if (!c.isConnected) "SSH disconnected during bind retry" else "Stopped during bind retry")
    }

    companion object {
        private const val TAG = "SshTunnelManager"

        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}

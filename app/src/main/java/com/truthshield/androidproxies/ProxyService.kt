package com.truthshield.androidproxies

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class ProxyService : Service() {

    private var proxy: HttpProxyServer? = null
    private var tunnel: TunnelGroup? = null
    private var cycler: AirplaneCycler? = null
    private var rebootScheduler: RebootScheduler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var meterThread: Thread? = null
    @Volatile private var meterRunning = false

    @Volatile private var lastStatus: String = "Starting…"
    @Volatile private var lastSpeedLine: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
        }
        startForegroundCompat(buildNotification("Starting…"))
        try {
            startEverything()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            updateNotification("Failed: ${e.message}")
            stopEverything()
        }
        return START_STICKY
    }

    private fun startEverything() {
        val settings = SettingsStore(this)
        val proxyPort = settings.localProxyPort
        val useSsh = settings.useSshTunnel

        // With SSH, only the local reverse-forwarder needs to reach the proxy,
        // so loopback is safest. Without SSH (e.g. a separate WireGuard tunnel
        // fronts it), the remote peer reaches the proxy over the wg interface,
        // which loopback can't serve — bind all interfaces instead.
        val bindAddr = if (useSsh) "127.0.0.1" else "0.0.0.0"
        proxy = HttpProxyServer(
            bindAddress = bindAddr,
            port = proxyPort,
        ).also { it.start() }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidProxies::tunnel")
            .also { it.setReferenceCounted(false); it.acquire() }

        val sshStatus: (String) -> Unit = { s ->
            if (cycler == null || tunnel?.isPaused() != true) {
                lastStatus = s
                updateNotification(s)
            } else {
                Log.d(TAG, "SSH status suppressed during rotation: $s")
            }
        }

        val newTunnel = if (useSsh) {
            val count = settings.tunnelCount.coerceIn(1, MAX_TUNNELS)
            // Each tunnel reverse-forwards a consecutive server port (base, base+1, …)
            // all pointing at the same local proxy; a server-side load balancer
            // fronts them. Kill-stale must be off with >1 tunnel — it identifies
            // holders by process and would kill sibling tunnels (same user, @notty).
            val killStale = settings.killStale && count == 1
            val mgrs = (0 until count).map { i ->
                val statusCb: (String) -> Unit =
                    if (i == 0) sshStatus else { s -> Log.d(TAG, "[T${i + 1}] $s") }
                SshTunnelManager(
                    SshTunnelManager.Config(
                        host = settings.sshHost,
                        port = settings.sshPort,
                        user = settings.sshUser,
                        privateKeyPem = settings.privateKeyPem,
                        password = settings.password,
                        remoteBindHost = settings.remoteBindHost,
                        remoteBindPort = settings.remoteBindPort + i,
                        localProxyHost = "127.0.0.1",
                        localProxyPort = proxyPort,
                        killStaleByPort = killStale,
                    ),
                    onStatus = statusCb,
                )
            }
            TunnelGroup(mgrs).also { tunnel = it; it.start() }
        } else {
            tunnel = null
            null
        }

        registerNetworkCallback()

        if (settings.cycleEnabled) {
            cycler = AirplaneCycler(
                intervalSec = settings.cycleIntervalSec.coerceAtLeast(30),
                airplaneOnDurationSec = settings.airplaneOnSec.coerceAtLeast(2),
                tunnel = newTunnel,
                context = applicationContext,
                onStatus = { s -> lastStatus = s; updateNotification(s) },
            ).also { it.start() }
        }

        if (settings.autoReboot) {
            rebootScheduler = RebootScheduler(
                intervalSec = settings.rebootIntervalSec.coerceAtLeast(60),
                context = applicationContext,
                onStatus = { s -> lastStatus = s; updateNotification(s) },
            ).also { it.start() }
        }

        startMeter()

        val mode = if (useSsh) "connecting tunnel…" else "direct (WireGuard fronts it)"
        updateNotification("Proxy on $bindAddr:$proxyPort · $mode")
        broadcastState(true)
    }

    private fun startMeter() {
        if (meterRunning) return
        meterRunning = true
        meterThread = Thread({
            val p = proxy ?: return@Thread
            var lastUp = p.bytesUp.get()
            var lastDown = p.bytesDown.get()
            var lastNs = System.nanoTime()
            while (meterRunning) {
                try { Thread.sleep(METER_INTERVAL_MS) } catch (_: InterruptedException) { break }
                val now = System.nanoTime()
                val dtSec = (now - lastNs).coerceAtLeast(1).toDouble() / 1_000_000_000.0
                val u = p.bytesUp.get()
                val d = p.bytesDown.get()
                val upRate = ((u - lastUp) / dtSec).toLong().coerceAtLeast(0)
                val downRate = ((d - lastDown) / dtSec).toLong().coerceAtLeast(0)
                lastUp = u; lastDown = d; lastNs = now
                lastSpeedLine = "↓ ${formatRate(downRate)}   ↑ ${formatRate(upRate)}"
                updateNotification(lastStatus)
            }
        }, "proxy-meter").also { it.start() }
    }

    private fun stopMeter() {
        meterRunning = false
        meterThread?.interrupt()
        meterThread = null
        lastSpeedLine = ""
    }

    private fun formatRate(bps: Long): String {
        return when {
            bps < 1024 -> "$bps B/s"
            bps < 1024 * 1024 -> "%.1f KB/s".format(bps / 1024.0)
            bps < 1024L * 1024 * 1024 -> "%.2f MB/s".format(bps / (1024.0 * 1024.0))
            else -> "%.2f GB/s".format(bps / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "network available: $network")
                tunnel?.kickReconnect()
            }
            override fun onLost(network: Network) {
                Log.d(TAG, "network lost: $network")
            }
        }
        try {
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = networkCallback ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        networkCallback = null
    }

    private fun stopEverything() {
        stopMeter()
        unregisterNetworkCallback()
        try { rebootScheduler?.stop() } catch (_: Exception) {}
        try { cycler?.stop() } catch (_: Exception) {}
        try { tunnel?.stop() } catch (_: Exception) {}
        try { proxy?.stop() } catch (_: Exception) {}
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        rebootScheduler = null; cycler = null; tunnel = null; proxy = null; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastState(false)
        stopSelf()
    }

    private fun broadcastState(running: Boolean) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
        )
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val speed = lastSpeedLine
        val combined = if (speed.isEmpty()) text else "$text\n$speed"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Android Proxies")
            .setContentText(text)
            .setSubText(speed.ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(combined))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Proxy / Tunnel", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.truthshield.androidproxies.STOP"
        const val ACTION_STATE_CHANGED = "com.truthshield.androidproxies.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        private const val CHANNEL_ID = "proxy_tunnel"
        private const val NOTIF_ID = 1
        private const val TAG = "ProxyService"
        private const val METER_INTERVAL_MS = 2000L
        private const val MAX_TUNNELS = 16

        fun start(context: Context) {
            val i = Intent(context, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProxyService::class.java).setAction(ACTION_STOP))
        }
    }
}

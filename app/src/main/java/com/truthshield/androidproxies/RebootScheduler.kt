package com.truthshield.androidproxies

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically reboots the phone. A full reboot is a heavier IP rotation than
 * airplane cycling (fresh cellular attach, cleared state). After the reboot the
 * BootReceiver restarts the service if auto-start is on, which re-arms this
 * scheduler — so it forms a self-sustaining cycle.
 */
class RebootScheduler(
    private val intervalSec: Int,
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "reboot-scheduler").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
    }

    private fun loop() {
        onStatus("Auto-reboot enabled — every ${intervalSec}s (via ${RebootHelper.availableMethod(context)})")
        while (running.get()) {
            try { Thread.sleep(intervalSec * 1000L) } catch (_: InterruptedException) { return }
            if (!running.get()) return
            onStatus("Auto-reboot: rebooting now…")
            val err = RebootHelper.reboot(context)
            if (err != null) {
                Log.w(TAG, "auto-reboot failed: $err")
                onStatus("Auto-reboot failed: $err")
            }
            // On success the device goes down before reaching here.
        }
    }

    companion object { private const val TAG = "RebootScheduler" }
}

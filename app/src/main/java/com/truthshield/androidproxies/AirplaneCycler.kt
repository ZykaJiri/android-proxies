package com.truthshield.androidproxies

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AirplaneCycler(
    private val intervalSec: Int,
    private val airplaneOnDurationSec: Int,
    private val tunnel: SshTunnelManager,
    private val onStatus: (String) -> Unit,
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "airplane-cycler").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        try { tunnel.resume() } catch (_: Exception) {}
    }

    private fun loop() {
        onStatus("Airplane rotation enabled — every ${intervalSec}s, OFF for ${airplaneOnDurationSec}s")
        while (running.get()) {
            if (!sleep(intervalSec * 1000L)) return
            if (!running.get()) return

            val svc = AssistantVoiceInteractionService.instance
            if (svc == null) {
                onStatus("Rotation skipped — app is not the default digital assistant")
                continue
            }

            try {
                onStatus("Rotating IP: pausing SSH…")
                tunnel.pause()

                onStatus("Rotating IP: airplane mode → ON (${airplaneOnDurationSec}s)")
                svc.toggleAirplane(true)
                if (!sleep(airplaneOnDurationSec * 1000L)) {
                    safeResume(); return
                }

                onStatus("Rotating IP: airplane mode → OFF, waiting for radio…")
                svc.toggleAirplane(false)
                if (!sleep(RADIO_SETTLE_MS)) {
                    safeResume(); return
                }

                onStatus("Rotation complete — reconnecting SSH")
                tunnel.resume()
            } catch (e: Exception) {
                Log.w(TAG, "rotation iteration failed", e)
                onStatus("Rotation failed: ${e.message} — resuming SSH")
                safeResume()
            }
        }
        safeResume()
    }

    private fun safeResume() {
        try { tunnel.resume() } catch (_: Exception) {}
    }

    private fun sleep(ms: Long): Boolean {
        return try { Thread.sleep(ms); true } catch (_: InterruptedException) { false }
    }

    companion object {
        private const val TAG = "AirplaneCycler"
        private const val RADIO_SETTLE_MS = 4_000L
    }
}

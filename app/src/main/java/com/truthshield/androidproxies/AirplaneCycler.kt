package com.truthshield.androidproxies

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AirplaneCycler(
    private val intervalSec: Int,
    private val airplaneOnDurationSec: Int,
    // Null in direct/WireGuard mode: there is no SSH session to pause, and
    // WireGuard re-handshakes from the new cellular IP on its own.
    private val tunnel: TunnelGroup?,
    private val context: Context,
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
        try { tunnel?.resume() } catch (_: Exception) {}
    }

    private fun loop() {
        onStatus("Airplane rotation enabled — every ${intervalSec}s, OFF for ${airplaneOnDurationSec}s")
        while (running.get()) {
            if (!sleep(intervalSec * 1000L)) return
            if (!running.get()) return

            // Authoritative check: the persisted system setting, NOT the live
            // service instance. After a reboot the instance can be null for a
            // while even though we ARE the default assistant — relying on it
            // made the app wrongly give up on rotation post-reboot.
            if (!AssistantVoiceInteractionService.isDefaultAssistant(context)) {
                onStatus("Rotation skipped — app is not the default digital assistant")
                continue
            }
            val svc = AssistantVoiceInteractionService.awaitInstance(ASSISTANT_BIND_WAIT_MS)
            if (svc == null) {
                onStatus("Assistant not bound yet — skipping this cycle, will retry")
                continue
            }

            try {
                if (tunnel != null) {
                    onStatus("Rotating IP: pausing SSH…")
                    tunnel.pause()
                }

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

                onStatus(if (tunnel != null) "Rotation complete — reconnecting SSH" else "Rotation complete")
                tunnel?.resume()
            } catch (e: Exception) {
                Log.w(TAG, "rotation iteration failed", e)
                onStatus("Rotation failed: ${e.message}")
                safeResume()
            }
        }
        safeResume()
    }

    private fun safeResume() {
        try { tunnel?.resume() } catch (_: Exception) {}
    }

    private fun sleep(ms: Long): Boolean {
        return try { Thread.sleep(ms); true } catch (_: InterruptedException) { false }
    }

    companion object {
        private const val TAG = "AirplaneCycler"
        private const val RADIO_SETTLE_MS = 4_000L
        private const val ASSISTANT_BIND_WAIT_MS = 8_000L
    }
}

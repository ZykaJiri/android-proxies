package com.truthshield.androidproxies

import android.util.Log

/**
 * Fans out lifecycle calls to a set of independent SSH tunnels, each with its
 * own reverse-forward on a consecutive server port. Multiple tunnels = multiple
 * TCP flows phone→server, so a server-side load balancer can spread incoming
 * proxy connections across them and aggregate throughput (one SSH connection is
 * a single TCP flow and caps out well below the link's parallel capacity).
 */
class TunnelGroup(private val tunnels: List<SshTunnelManager>) {

    fun start() = tunnels.forEach { it.start() }

    fun stop() = tunnels.forEach { try { it.stop() } catch (e: Exception) { Log.w(TAG, "stop", e) } }

    fun pause() = tunnels.forEach { it.pause() }

    fun resume() = tunnels.forEach { it.resume() }

    fun kickReconnect() = tunnels.forEach { it.kickReconnect() }

    /** Paused only when every tunnel is paused (used to gate status updates). */
    fun isPaused(): Boolean = tunnels.isNotEmpty() && tunnels.all { it.isPaused() }

    companion object { private const val TAG = "TunnelGroup" }
}

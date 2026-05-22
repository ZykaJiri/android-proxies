package com.truthshield.androidproxies

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

class NetworkRouter(context: Context) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)!!
    private val wifiRef = AtomicReference<Network?>(null)
    private val cellRef = AtomicReference<Network?>(null)

    private val wifiCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            Log.i(TAG, "wifi available: $n")
            wifiRef.set(n)
        }
        override fun onLost(n: Network) {
            Log.i(TAG, "wifi lost: $n")
            wifiRef.compareAndSet(n, null)
        }
    }

    private val cellCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            Log.i(TAG, "cellular available: $n")
            cellRef.set(n)
        }
        override fun onLost(n: Network) {
            Log.i(TAG, "cellular lost: $n")
            cellRef.compareAndSet(n, null)
        }
    }

    init {
        cm.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            wifiCb,
        )
        cm.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cellCb,
        )
    }

    fun wifi(): Network? = wifiRef.get()
    fun cellular(): Network? = cellRef.get()

    fun waitForWifi(timeoutMs: Long): Network? = waitFor(wifiRef, timeoutMs)
    fun waitForCellular(timeoutMs: Long): Network? = waitFor(cellRef, timeoutMs)

    private fun waitFor(ref: AtomicReference<Network?>, timeoutMs: Long): Network? {
        ref.get()?.let { return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { return ref.get() }
            ref.get()?.let { return it }
        }
        return null
    }

    fun close() {
        try { cm.unregisterNetworkCallback(wifiCb) } catch (_: Exception) {}
        try { cm.unregisterNetworkCallback(cellCb) } catch (_: Exception) {}
    }

    companion object { private const val TAG = "NetworkRouter" }
}

package com.truthshield.androidproxies

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Rebooting the device needs privilege a normal app doesn't have. Two paths are
 * supported, tried in order:
 *   1. Device owner — DevicePolicyManager.reboot() (provision via
 *      `adb shell dpm set-device-owner com.truthshield.androidproxies/.RebootAdminReceiver`).
 *   2. Root — `su -c reboot`.
 */
object RebootHelper {
    private const val TAG = "RebootHelper"

    /** "device-owner", "root", or "none" — for the UI status line. */
    fun availableMethod(context: Context): String = when {
        isDeviceOwner(context) -> "device-owner"
        hasRoot() -> "root"
        else -> "none"
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return try { dpm.isDeviceOwnerApp(context.packageName) } catch (_: Exception) { false }
    }

    fun hasRoot(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/vendor/bin/su", "/system/sbin/su",
        )
        return paths.any { File(it).exists() }
    }

    /**
     * Attempt a reboot. Returns null if a reboot was initiated (the process is
     * about to go down); otherwise a human-readable error explaining why not.
     */
    fun reboot(context: Context): String? {
        if (isDeviceOwner(context)) {
            try {
                context.getSystemService(DevicePolicyManager::class.java)!!
                    .reboot(ComponentName(context, RebootAdminReceiver::class.java))
                return null
            } catch (e: Exception) {
                Log.w(TAG, "device-owner reboot failed", e)
            }
        }
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            // If root is granted the device reboots and waitFor never returns.
            p.waitFor()
            return "Root reboot did not take effect (su denied or unavailable)."
        } catch (e: Exception) {
            Log.w(TAG, "root reboot failed", e)
        }
        return "No reboot method available. Grant root, or provision as device owner: " +
            "adb shell dpm set-device-owner com.truthshield.androidproxies/.RebootAdminReceiver"
    }
}

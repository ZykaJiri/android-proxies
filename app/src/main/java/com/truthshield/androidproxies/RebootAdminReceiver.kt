package com.truthshield.androidproxies

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin component. Only needed so that, when this app is provisioned as
 * device owner (adb dpm set-device-owner com.truthshield.androidproxies/.RebootAdminReceiver),
 * DevicePolicyManager.reboot() can be called. Carries no policies of its own.
 */
class RebootAdminReceiver : DeviceAdminReceiver()

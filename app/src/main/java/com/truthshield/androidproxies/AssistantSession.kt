package com.truthshield.androidproxies

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val enable = args?.getBoolean(AssistantVoiceInteractionService.EXTRA_AIRPLANE_ENABLED, false) ?: false
        Log.i(TAG, "onShow enable=$enable showFlags=$showFlags")
        val intent = Intent("android.settings.VOICE_CONTROL_AIRPLANE_MODE")
            .putExtra(AssistantVoiceInteractionService.EXTRA_AIRPLANE_ENABLED, enable)
        try {
            startVoiceActivity(intent)
            Log.i(TAG, "startVoiceActivity dispatched")
        } catch (e: Throwable) {
            Log.e(TAG, "startVoiceActivity FAILED", e)
            try { hide() } catch (_: Exception) {}
        }
    }

    override fun onTaskFinished(intent: Intent?, taskId: Int) {
        super.onTaskFinished(intent, taskId)
        Log.i(TAG, "onTaskFinished intent=$intent taskId=$taskId — hiding session")
        try { hide() } catch (_: Exception) {}
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "onHide")
    }

    companion object { private const val TAG = "AssistantSession" }
}

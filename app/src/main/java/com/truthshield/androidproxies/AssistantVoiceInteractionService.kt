package com.truthshield.androidproxies

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.util.Log

class AssistantVoiceInteractionService : VoiceInteractionService() {

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onReady() {
        super.onReady()
        instance = this
        Log.i(TAG, "onReady: bound as voice interaction service, pkg=$packageName")
    }

    override fun onShutdown() {
        Log.i(TAG, "onShutdown")
        if (instance == this) instance = null
        super.onShutdown()
    }

    fun toggleAirplane(enable: Boolean) {
        Log.i(TAG, "toggleAirplane(enable=$enable) called")
        val args = Bundle().apply { putBoolean(EXTRA_AIRPLANE_ENABLED, enable) }
        main.post {
            try {
                showSession(args, 0)
                Log.i(TAG, "showSession returned without throwing")
            } catch (e: Throwable) {
                Log.e(TAG, "showSession threw", e)
            }
        }
    }

    companion object {
        private const val TAG = "VoiceAssistant"
        const val EXTRA_AIRPLANE_ENABLED = "airplane_mode_enabled"

        @Volatile var instance: AssistantVoiceInteractionService? = null

        fun isDefaultAssistant(context: Context): Boolean {
            val current = Settings.Secure.getString(
                context.contentResolver,
                "voice_interaction_service",
            ) ?: return false
            return current.startsWith("${context.packageName}/")
        }
    }
}

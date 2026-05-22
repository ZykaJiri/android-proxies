package com.truthshield.androidproxies

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        android.util.Log.i("AssistantSession", "onNewSession args=$args")
        return AssistantSession(this)
    }
}

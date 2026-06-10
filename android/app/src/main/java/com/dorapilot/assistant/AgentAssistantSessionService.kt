package com.dorapilot.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AgentAssistantSessionService : VoiceInteractionSessionService() {
    override fun onCreate() {
        super.onCreate()
        AliveForegroundService.ensureRunning(this)
        AssistantWorkScheduler.ensurePeriodicCapabilityScan(this)
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AgentAssistantSession(this)
    }
}

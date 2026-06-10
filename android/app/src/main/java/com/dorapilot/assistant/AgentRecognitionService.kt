package com.dorapilot.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService

class AgentRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        listener?.readyForSpeech(Bundle())
    }

    override fun onStopListening(listener: Callback?) {
        listener?.endOfSpeech()
    }

    override fun onCancel(listener: Callback?) {
        // No-op for now.
    }
}

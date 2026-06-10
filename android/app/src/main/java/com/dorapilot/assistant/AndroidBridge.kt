package com.dorapilot.assistant

import android.webkit.JavascriptInterface

class AndroidBridge(
    private val session: AgentAssistantSession
) {
    @JavascriptInterface
    fun dispatchAgentAction(actionJson: String) {
        session.dispatchAgentAction(actionJson)
    }

    @JavascriptInterface
    fun requestKeyboard() {
        session.requestKeyboard()
    }

    @JavascriptInterface
    fun dismissAssistant() {
        session.dismissAssistant()
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        session.copyToClipboard(text)
    }

    @JavascriptInterface
    fun openModelFileMenu() {
        session.openModelFileMenu()
    }

    @JavascriptInterface
    fun startDictation() {
        session.startDictation()
    }

    @JavascriptInterface
    fun stopDictation() {
        session.stopDictation()
    }

    @JavascriptInterface
    fun cancelDictation() {
        session.cancelDictation()
    }

    @JavascriptInterface
    fun requestNaturalTts(text: String) {
        session.requestNaturalTts(text)
    }

    @JavascriptInterface
    fun isVoiceResponsesEnabled(): Boolean {
        return session.isVoiceResponsesEnabled()
    }

    @JavascriptInterface
    fun vibrate(timingsJson: String, amplitudesJson: String) {
        session.vibrate(timingsJson, amplitudesJson)
    }
}

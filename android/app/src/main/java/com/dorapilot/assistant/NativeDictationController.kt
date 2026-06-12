package com.dorapilot.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import org.json.JSONObject

class NativeDictationController(
    private val context: Context,
    private val mainHandler: Handler,
    private val evaluateJavascript: (String) -> Unit,
    private val emitStatus: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutedStreams = mutableSetOf<Int>()
    private var pendingUnmute: Runnable? = null

    fun start() {
        mainHandler.post {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                emitError("Microphone permission is required for dictation.")
                return@post
            }
            val onDeviceSupported = isOnDeviceRecognitionSupported()
            if (!SpeechRecognizer.isRecognitionAvailable(context) && !onDeviceSupported) {
                emitError("Speech recognition is not available on this device.")
                return@post
            }
            // Offline: go straight to the on-device recognizer so listening
            // doesn't fail with a network error.
            val offline = !isNetworkAvailable()
            startListening(
                useOnDevice = offline && onDeviceSupported,
                preferOffline = offline,
                allowOnDeviceFallback = true
            )
        }
    }

    private fun startListening(
        useOnDevice: Boolean,
        preferOffline: Boolean,
        allowOnDeviceFallback: Boolean
    ) {
        destroy()
        muteRecognizerSounds()
        val recognizer = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                emitStatus("Dictation ready.")
                evaluateJavascript("window.onDictationState && window.onDictationState('ready');")
            }

            override fun onBeginningOfSpeech() {
                evaluateJavascript("window.onDictationState && window.onDictationState('listening');")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                evaluateJavascript("window.onDictationState && window.onDictationState('processing');")
            }

            override fun onError(error: Int) {
                destroy()
                // Network-class failure on the default recognizer: retry once
                // with the on-device recognizer instead of surfacing an error.
                if (allowOnDeviceFallback && !useOnDevice &&
                    error in NETWORK_ERROR_CODES && isOnDeviceRecognitionSupported()
                ) {
                    emitStatus("Network speech recognition failed; retrying on-device.")
                    startListening(useOnDevice = true, preferOffline = true, allowOnDeviceFallback = false)
                    return
                }
                emitError(describeRecognizerError(error))
            }

            override fun onResults(results: Bundle?) {
                val text = bestResult(results)
                destroy()
                emitText(text, isFinal = true)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                emitText(bestResult(partialResults), isFinal = false)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        recognizer.startListening(intent)
        evaluateJavascript("window.onDictationState && window.onDictationState('listening');")
    }

    private fun isOnDeviceRecognitionSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    }

    private fun isNetworkAvailable(): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun describeRecognizerError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK ->
            "No internet for speech recognition, and on-device recognition isn't available."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for dictation."
        else -> "Dictation failed ($error)."
    }

    fun stop() {
        mainHandler.post {
            speechRecognizer?.stopListening()
        }
    }

    fun cancel() {
        mainHandler.post {
            speechRecognizer?.cancel()
            destroy()
            evaluateJavascript("window.onDictationState && window.onDictationState('idle');")
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        // Restore volume after a short delay so the recognizer's end-of-speech
        // earcon (Samsung plays a beep right around onResults/onError) stays muted.
        scheduleRestoreRecognizerSounds()
    }

    private fun bestResult(bundle: Bundle?): String {
        return bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    }

    private fun emitText(text: String, isFinal: Boolean) {
        if (text.isBlank()) return
        val script = "window.onDictationText && window.onDictationText(${JSONObject.quote(text)}, $isFinal);"
        evaluateJavascript(script)
    }

    private fun emitError(message: String) {
        emitStatus(message)
        evaluateJavascript("window.onDictationError && window.onDictationError(${JSONObject.quote(message)});")
    }

    private fun muteRecognizerSounds() {
        pendingUnmute?.let { mainHandler.removeCallbacks(it) }
        pendingUnmute = null
        recognizerStreams().forEach { stream ->
            runCatching {
                if (audioManager.isStreamMute(stream)) return@forEach
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                mutedStreams.add(stream)
            }
        }
    }

    private fun scheduleRestoreRecognizerSounds() {
        pendingUnmute?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { restoreRecognizerSounds() }
        pendingUnmute = runnable
        mainHandler.postDelayed(runnable, RESTORE_DELAY_MS)
    }

    private fun restoreRecognizerSounds() {
        pendingUnmute = null
        mutedStreams.forEach { stream ->
            runCatching {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            }
        }
        mutedStreams.clear()
    }

    private fun recognizerStreams() = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_ALARM
    )

    private companion object {
        const val RESTORE_DELAY_MS = 600L

        // ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_SERVER, ERROR_SERVER_DISCONNECTED
        // (the last is API 31+, so use the raw value).
        val NETWORK_ERROR_CODES = setOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_SERVER,
            11
        )
    }
}

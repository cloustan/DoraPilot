package com.dorapilot.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                emitError("Speech recognition is not available on this device.")
                return@post
            }

            destroy()
            muteRecognizerSounds()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
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
                    emitError("Dictation failed ($error).")
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
            }
            recognizer.startListening(intent)
            evaluateJavascript("window.onDictationState && window.onDictationState('listening');")
        }
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
    }
}

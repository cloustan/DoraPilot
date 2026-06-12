package com.dorapilot.assistant

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Voice output with a graceful offline path. When online with a backend key,
 * uses the cloud MeloTTS voice (best quality). When offline, with no key, or if
 * the cloud call fails, falls back to the device's built-in [TextToSpeech]
 * engine, which runs fully on-device - so voice still works with no network.
 */
class NaturalTtsPlayer(
    private val context: Context,
    private val config: BackendConfig,
    private val executor: ExecutorService,
    private val emitStatus: (String) -> Unit
) {
    @Volatile
    private var player: MediaPlayer? = null

    // Lazily-initialized on-device engine. `ready` flips true on successful init;
    // a single utterance requested before init is held in `pending`.
    @Volatile
    private var systemTts: TextToSpeech? = null
    @Volatile
    private var systemTtsReady = false
    @Volatile
    private var pending: String? = null

    fun speak(text: String) {
        val spoken = text.trim().take(900)
        if (spoken.isEmpty()) return
        if (!config.voiceResponsesEnabled) {
            emitStatus("Voice responses are disabled; using chime feedback.")
            return
        }

        // Offline or no key -> straight to on-device voice, no network attempt.
        if (config.apiKey.isBlank() || !isOnline()) {
            speakOnDevice(spoken)
            return
        }

        executor.execute {
            runCatching {
                val audioFile = fetchSpeech(spoken)
                playAudio(audioFile)
            }.onFailure { error ->
                // Cloud voice failed (network blip, worker down) -> on-device.
                emitStatus("Cloud voice unavailable, using on-device voice.")
                speakOnDevice(spoken)
            }
        }
    }

    fun stop() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        runCatching { systemTts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { systemTts?.shutdown() }
        systemTts = null
        systemTtsReady = false
    }

    // ---- on-device fallback -------------------------------------------------

    private fun speakOnDevice(text: String) {
        val engine = systemTts
        if (engine != null && systemTtsReady) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dora_tts")
            return
        }
        // First use: hold the text and init the engine; speak on ready.
        pending = text
        if (engine == null) {
            systemTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    systemTts?.let { tts ->
                        runCatching { tts.language = Locale.getDefault() }
                        systemTtsReady = true
                        pending?.let { tts.speak(it, TextToSpeech.QUEUE_FLUSH, null, "dora_tts") }
                        pending = null
                    }
                } else {
                    emitStatus("On-device voice unavailable on this device.")
                    pending = null
                }
            }
        }
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    // ---- cloud MeloTTS ------------------------------------------------------

    private fun fetchSpeech(text: String): File {
        val endpoint = ttsEndpoint()
        val payload = JSONObject().put("text", text).toString()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/*")
        }

        return try {
            connection.outputStream.bufferedWriter().use { it.write(payload) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            if (status !in 200..299) {
                error(bytes.decodeToString().take(300).ifBlank { "HTTP $status" })
            }
            if (bytes.isEmpty()) error("empty audio response")

            File(context.cacheDir, "dora_tts.wav").apply {
                outputStream().use { it.write(bytes) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun playAudio(file: File) {
        stop()
        val nextPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                if (player === mp) player = null
                true
            }
            prepare()
            start()
        }
        player = nextPlayer
    }

    private fun ttsEndpoint(): String {
        val endpoint = config.endpoint.trim()
        return when {
            endpoint.endsWith("/v1/chat/completions") ->
                endpoint.removeSuffix("/v1/chat/completions") + "/v1/tts"
            endpoint.endsWith("/chat/completions") ->
                endpoint.removeSuffix("/chat/completions") + "/tts"
            else -> endpoint.trimEnd('/') + "/v1/tts"
        }
    }
}

package com.dorapilot.assistant

import android.content.Context
import android.media.MediaPlayer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService

class NaturalTtsPlayer(
    private val context: Context,
    private val config: BackendConfig,
    private val executor: ExecutorService,
    private val emitStatus: (String) -> Unit
) {
    @Volatile
    private var player: MediaPlayer? = null

    fun speak(text: String) {
        val spoken = text.trim().take(900)
        if (spoken.isEmpty()) return
        if (!config.voiceResponsesEnabled) {
            emitStatus("Voice responses are disabled; using chime feedback.")
            return
        }
        if (config.apiKey.isBlank()) {
            emitStatus("Natural TTS requires backend API key.")
            return
        }

        executor.execute {
            runCatching {
                val audioFile = fetchSpeech(spoken)
                playAudio(audioFile)
            }.onFailure { error ->
                emitStatus("Natural TTS failed: ${error.message}")
            }
        }
    }

    fun stop() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

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

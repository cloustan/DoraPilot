package com.dorapilot.assistant

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainBackendClient {
    data class CompletionRequest(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val messages: JSONArray,
        val tools: JSONArray? = null,
        val headers: JSONObject = JSONObject(),
        val temperature: Double = 0.2
    )

    data class CompletionResult(
        val ok: Boolean,
        val status: Int,
        val body: String,
        val message: JSONObject,
        val toolCalls: JSONArray,
        val outputText: String,
        val error: String = ""
    )

    fun infer(args: JSONObject): JSONObject {
        val endpoint = args.optString("endpoint", DEFAULT_INFERENCE_ENDPOINT).trim()
        val apiKey = args.optString("api_key", "").trim()
        val model = args.optString("model", DEFAULT_INFERENCE_MODEL).trim()
        val prompt = args.optString("prompt", "").trim()
        val system = args.optString("system", "").trim().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val headers = args.optJSONObject("headers") ?: JSONObject()
        val history = args.optJSONArray("history") ?: JSONArray()

        if (prompt.isEmpty()) {
            return errorJson("missing_prompt", "Missing required argument: prompt")
        }
        if (apiKey.isEmpty()) {
            return errorJson(
                "missing_api_key",
                "Backend is not configured. Add DORA_BACKEND_API_KEY to use cloud answers."
            )
        }

        val uri = Uri.parse(endpoint)
        if (uri.scheme != "https") {
            return errorJson("invalid_endpoint", "Only https backend URLs are allowed")
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        val historyStart = maxOf(0, history.length() - MAX_HISTORY_MESSAGES)
        for (i in historyStart until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role", "").trim()
            val content = item.optString("content", "").trim()
            if (content.isEmpty() || (role != "user" && role != "assistant")) continue
            messages.put(JSONObject().put("role", role).put("content", content.take(2000)))
        }
        val lastRole = messages.optJSONObject(messages.length() - 1)?.optString("role", "")
        if (lastRole != "user") {
            messages.put(JSONObject().put("role", "user").put("content", prompt))
        }

        val request = CompletionRequest(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            messages = messages,
            headers = headers
        )
        val result = complete(request)
        val errorText = result.error.ifBlank { "Inference request failed." }
        return JSONObject()
            .put("ok", result.ok)
            .put("status", result.status)
            .put("endpoint", endpoint)
            .put("model", model)
            .put("output", if (result.ok) result.outputText.take(1200) else errorText)
            .put("body_preview", result.body.take(600))
            .put("error", if (result.ok) "" else errorText)
            .put("retryable", isRetryable(result.status))
    }

    fun complete(request: CompletionRequest): CompletionResult {
        val uri = Uri.parse(request.endpoint)
        if (uri.scheme != "https") {
            return CompletionResult(
                ok = false,
                status = 0,
                body = "",
                message = JSONObject(),
                toolCalls = JSONArray(),
                outputText = "",
                error = "Only https URLs are allowed"
            )
        }

        val payload = JSONObject()
            .put("model", request.model)
            .put("messages", request.messages)
            .put("temperature", request.temperature)
        if (request.tools != null && request.tools.length() > 0) {
            payload.put("tools", request.tools)
            payload.put("tool_choice", "auto")
        }

        val connection = (URL(request.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 180000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${request.apiKey}")
            applyCustomHeaders(this, request.headers)
        }

        return runCatching {
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.connect()

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val choice = parsed.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
            val message = choice.optJSONObject("message") ?: JSONObject()
            val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
            val output = extractAssistantText(parsed)

            CompletionResult(
                ok = status in 200..299,
                status = status,
                body = body,
                message = message,
                toolCalls = toolCalls,
                outputText = output,
                error = if (status in 200..299) "" else extractErrorText(parsed, body, status)
            )
        }.getOrElse { error ->
            CompletionResult(
                ok = false,
                status = 0,
                body = "",
                message = JSONObject(),
                toolCalls = JSONArray(),
                outputText = "",
                error = error.message?.takeIf { it.isNotBlank() } ?: "Inference call failed"
            )
        }.also {
            connection.disconnect()
        }
    }

    private fun errorJson(code: String, message: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("error", message)
            .put("output", message)
            .put("retryable", false)

    private fun extractErrorText(parsed: JSONObject, body: String, status: Int): String {
        val apiError = parsed.optJSONObject("error")
        val message = apiError?.optString("message", "")?.trim().orEmpty()
        if (message.isNotBlank()) return message.take(500)
        val direct = parsed.optString("message", "").trim()
        if (direct.isNotBlank()) return direct.take(500)
        return body.take(500).ifBlank { "Backend request failed with HTTP $status." }
    }

    private fun isRetryable(status: Int): Boolean =
        status == 0 || status == 408 || status == 429 || status >= 500

    private fun extractAssistantText(json: JSONObject): String {
        val choices = json.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message") ?: return ""
        val content = message.opt("content") ?: return ""

        return when (content) {
            is String -> content
            is JSONArray -> {
                val builder = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    val text = item.optString("text", "")
                    if (text.isNotEmpty()) {
                        if (builder.isNotEmpty()) builder.append('\n')
                        builder.append(text)
                    }
                }
                builder.toString()
            }
            else -> content.toString()
        }
    }

    private fun applyCustomHeaders(connection: HttpURLConnection, headers: JSONObject) {
        headers.keys().forEach { key ->
            val value = headers.optString(key, "")
            if (value.isNotBlank()) {
                connection.setRequestProperty(key, value)
            }
        }
    }

    companion object {
        private const val DEFAULT_INFERENCE_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_INFERENCE_MODEL = "gpt-4o-mini"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are Dora, a helpful on-phone assistant. Keep replies short and direct: " +
                "1-3 sentences or a few brief bullet points. Expand only when the user " +
                "explicitly asks for more detail."
        private const val MAX_HISTORY_MESSAGES = 16
    }
}

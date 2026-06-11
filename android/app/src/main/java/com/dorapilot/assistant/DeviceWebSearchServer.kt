package com.dorapilot.assistant

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Device-run web lookup: the phone makes the HTTPS request directly to free,
 * no-key public sources. Dora's backend is not involved in the search step.
 */
class DeviceWebSearchServer {
    fun search(query: String): JSONObject {
        val q = query.trim()
        if (q.isBlank()) {
            return JSONObject().put("ok", false).put("error", "query is required")
        }

        val candidates = JSONArray()
        val providerErrors = JSONArray()
        runCatching { duckDuckGo(q) }
            .onSuccess { merge(candidates, it) }
            .onFailure { providerErrors.put(providerError("DuckDuckGo", it)) }
        if (candidates.length() < 2) {
            runCatching { wikipedia(q) }
                .onSuccess { merge(candidates, it) }
                .onFailure { providerErrors.put(providerError("Wikipedia", it)) }
        }

        val ranked = rank(q, candidates)
        if (ranked.length() == 0) {
            val browserUrl = "https://duckduckgo.com/?q=${Uri.encode(q)}"
            val hadProviderFailure = providerErrors.length() > 0
            val message = if (hadProviderFailure) {
                "Instant web providers were unavailable from this device/network. Open this search: $browserUrl"
            } else {
                "I couldn't get an instant free result on-device. Open this search: $browserUrl"
            }
            return JSONObject()
                .put("ok", true)
                .put("query", q)
                .put("mode", "browser_fallback")
                .put("output", message)
                .put("url", browserUrl)
                .put("results", JSONArray())
                .put("provider_errors", providerErrors)
                .put("needs_network", hadProviderFailure)
                .put("retryable", hadProviderFailure)
        }

        return JSONObject()
            .put("ok", true)
            .put("query", q)
            .put("mode", "device_web_search")
            .put("output", formatAnswer(q, ranked))
            .put("results", ranked)
            .put("provider_errors", providerErrors)
            .put("retryable", false)
    }

    private fun duckDuckGo(query: String): JSONArray {
        val url = "https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1"
        val json = JSONObject(fetch(url))
        val out = JSONArray()
        val abstractText = json.optString("AbstractText").trim()
        if (abstractText.isNotBlank()) {
            out.put(
                JSONObject()
                    .put("title", json.optString("Heading", query).ifBlank { query })
                    .put("snippet", abstractText)
                    .put("url", json.optString("AbstractURL", ""))
                    .put("source", "DuckDuckGo")
            )
        }
        json.optString("Answer").trim().takeIf { it.isNotBlank() }?.let { answer ->
            out.put(
                JSONObject()
                    .put("title", "Instant answer")
                    .put("snippet", answer)
                    .put("url", json.optString("AnswerType", ""))
                    .put("source", "DuckDuckGo")
            )
        }
        collectRelated(json.optJSONArray("RelatedTopics") ?: JSONArray(), out)
        return out
    }

    private fun wikipedia(query: String): JSONArray {
        val title = query.replace(Regex("\\s+"), "_")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(title)}"
        val json = JSONObject(fetch(url))
        val extract = json.optString("extract").trim()
        if (extract.isBlank()) return JSONArray()
        return JSONArray().put(
            JSONObject()
                .put("title", json.optString("title", query))
                .put("snippet", extract)
                .put("url", json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page", ""))
                .put("source", "Wikipedia")
        )
    }

    private fun collectRelated(items: JSONArray, out: JSONArray) {
        for (i in 0 until minOf(items.length(), 8)) {
            val item = items.optJSONObject(i) ?: continue
            val nested = item.optJSONArray("Topics")
            if (nested != null) {
                collectRelated(nested, out)
                continue
            }
            val text = item.optString("Text").trim()
            if (text.isBlank()) continue
            out.put(
                JSONObject()
                    .put("title", text.substringBefore(" - ").take(80))
                    .put("snippet", text)
                    .put("url", item.optString("FirstURL", ""))
                    .put("source", "DuckDuckGo")
            )
        }
    }

    private fun merge(target: JSONArray, incoming: JSONArray) {
        val seen = mutableSetOf<String>()
        for (i in 0 until target.length()) {
            val item = target.optJSONObject(i) ?: continue
            seen += item.optString("url").ifBlank { item.optString("snippet").take(80) }
        }
        for (i in 0 until incoming.length()) {
            val item = incoming.optJSONObject(i) ?: continue
            val key = item.optString("url").ifBlank { item.optString("snippet").take(80) }
            if (key.isNotBlank() && seen.add(key)) target.put(item)
        }
    }

    private fun rank(query: String, candidates: JSONArray): JSONArray {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.distinct()
        val scored = mutableListOf<Pair<JSONObject, Double>>()
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            val title = item.optString("title")
            val snippet = item.optString("snippet")
            val hay = "$title $snippet".lowercase()
            var score = 0.0
            terms.forEach { term ->
                if (title.lowercase().contains(term)) score += 2.0
                if (hay.contains(term)) score += 1.0
            }
            if (snippet.length > 80) score += 0.5
            if (item.optString("source") == "Wikipedia") score += 0.25
            scored += item.put("score", String.format("%.2f", score).toDouble()) to score
        }
        return JSONArray().apply {
            scored.sortedByDescending { it.second }.take(5).forEach { put(it.first) }
        }
    }

    private fun formatAnswer(query: String, results: JSONArray): String {
        val lines = mutableListOf("Dora web search: $query")
        for (i in 0 until minOf(results.length(), 3)) {
            val item = results.optJSONObject(i) ?: continue
            val source = item.optString("source", "source")
            val title = item.optString("title").ifBlank { source }
            val snippet = item.optString("snippet").take(360)
            val url = item.optString("url")
            lines += "- $title ($source): $snippet${if (url.isNotBlank()) "\n  $url" else ""}"
        }
        return lines.joinToString("\n")
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DoraPilot/1.0 Android device web search")
        }
        return try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) error("HTTP $status: ${body.take(160)}")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun providerError(provider: String, error: Throwable): JSONObject {
        val root = error.cause ?: error
        return JSONObject()
            .put("provider", provider)
            .put("type", root.javaClass.simpleName)
            .put("message", root.message?.take(180) ?: root.toString().take(180))
            .put("network_error", root is IOException)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

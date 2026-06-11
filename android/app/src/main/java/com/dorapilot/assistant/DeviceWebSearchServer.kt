package com.dorapilot.assistant

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Web lookup that fetches an instant answer and returns it IN-APP (never opens
 * the browser).
 *
 * Primary path: the Dora Worker fetches DuckDuckGo/Wikipedia server-side and
 * grounds a concise answer - this works even when the device's network blocks
 * those domains directly. Secondary path: a direct on-device fetch for networks
 * where the worker is unreachable but the providers are not.
 */
class DeviceWebSearchServer(
    private val configProvider: () -> BackendConfig
) {
    fun search(query: String): JSONObject {
        val q = query.trim()
        if (q.isBlank()) {
            return JSONObject().put("ok", false).put("error", "query is required")
        }

        // 1) Worker-proxied search (reliable internet, grounded answer).
        runCatching { workerSearch(q) }.getOrNull()?.let { worker ->
            val answer = worker.optString("answer").trim()
            val results = worker.optJSONArray("results") ?: JSONArray()
            if (answer.isNotBlank() || results.length() > 0) {
                val output = if (answer.isNotBlank()) answer else formatAnswer(q, results)
                return JSONObject()
                    .put("ok", true)
                    .put("query", q)
                    .put("mode", "worker_web_search")
                    .put("output", output)
                    .put("results", results)
            }
        }

        // 2) Direct on-device fallback.
        val candidates = JSONArray()
        runCatching { duckDuckGo(q) }.onSuccess { merge(candidates, it) }
        if (candidates.length() < 2) {
            runCatching { wikipedia(q) }.onSuccess { merge(candidates, it) }
        }
        val ranked = rank(q, candidates)
        if (ranked.length() > 0) {
            return JSONObject()
                .put("ok", true)
                .put("query", q)
                .put("mode", "device_web_search")
                .put("output", formatAnswer(q, ranked))
                .put("results", ranked)
        }

        // Nothing fetched: return empty so the caller can fall back to the
        // assistant's own answer (in-app), NOT the browser.
        return JSONObject()
            .put("ok", true)
            .put("query", q)
            .put("mode", "empty")
            .put("output", "")
            .put("results", JSONArray())
    }

    private fun workerSearch(query: String): JSONObject {
        val config = configProvider()
        val endpoint = config.endpoint.trim()
        if (endpoint.isBlank() || config.apiKey.isBlank()) error("backend not configured")
        val base = endpoint.substringBeforeLast("/v1/", "")
        val searchUrl = if (base.isNotBlank()) "$base/v1/search"
        else Uri.parse(endpoint).buildUpon().path("/v1/search").build().toString()

        val payload = JSONObject().put("query", query)
        val connection = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            val headers = config.headers
            headers.keys().forEach { key ->
                val value = headers.optString(key, "")
                if (value.isNotBlank()) setRequestProperty(key, value)
            }
        }
        return try {
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            connection.connect()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bodyText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("HTTP $status: ${bodyText.take(160)}")
            JSONObject(bodyText)
        } finally {
            connection.disconnect()
        }
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
        val lines = mutableListOf("Web results for: $query")
        for (i in 0 until minOf(results.length(), 3)) {
            val item = results.optJSONObject(i) ?: continue
            val source = item.optString("source", "source")
            val title = item.optString("title").ifBlank { source }
            val snippet = item.optString("snippet").take(360)
            lines += "- $title ($source): $snippet"
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

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

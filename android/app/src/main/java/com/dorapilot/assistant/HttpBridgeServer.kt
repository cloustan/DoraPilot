package com.dorapilot.assistant

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * The agent's hands on the web: a guarded HTTP/HTTPS client exposed as an MCP
 * tool so the CLI-style agent can hit any web API/webhook directly instead of
 * driving app UIs. Most "UI tasks" are really API calls underneath, so this is
 * what gets the headless agent close to UI parity.
 *
 * Guardrails (so an autonomous agent can't be abused): HTTPS by default, an
 * SSRF block on loopback/private/link-local hosts, a response size cap, and a
 * request timeout.
 */
class HttpBridgeServer {

    fun request(args: JSONObject): JSONObject {
        val method = args.optString("method", "GET").trim().uppercase().ifBlank { "GET" }
        if (method !in ALLOWED_METHODS) {
            return fail("Unsupported method: $method")
        }

        val rawUrl = args.optString("url", "").trim()
        if (rawUrl.isEmpty()) return fail("url is required")

        val allowInsecure = args.optBoolean("allow_insecure", false)
        val allowLocal = args.optBoolean("allow_local", false)
        val timeoutMs = args.optInt("timeout_ms", 20_000).coerceIn(1_000, 60_000)
        val maxBytes = args.optInt("max_bytes", 200_000).coerceIn(1_024, 2_000_000)

        val withQuery = appendQuery(rawUrl, args.optJSONObject("query"))
        val parsed = runCatching { URL(withQuery) }.getOrNull()
            ?: return fail("Invalid URL: $rawUrl")
        val scheme = parsed.protocol.lowercase()
        if (scheme != "https" && !(scheme == "http" && allowInsecure)) {
            return fail("Only https is allowed (set allow_insecure for http).")
        }
        if (!allowLocal && isPrivateHost(parsed.host)) {
            return fail("Refusing to call a local/private host: ${parsed.host}")
        }

        val body = encodeBody(args.opt("body"))
        val headers = args.optJSONObject("headers") ?: JSONObject()

        return runCatching {
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = args.optBoolean("follow_redirects", true)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", "DoraPilot/1.0")
                headers.keys().forEach { key ->
                    val v = headers.optString(key, "")
                    if (v.isNotBlank()) setRequestProperty(key, v)
                }
                if (body != null) {
                    doOutput = true
                    if (getRequestProperty("Content-Type") == null) {
                        setRequestProperty("Content-Type", body.second)
                    }
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.first.toByteArray(Charsets.UTF_8)) }
            }
            conn.connect()

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input ->
                val buf = ByteArray(maxBytes)
                var total = 0
                while (total < maxBytes) {
                    val read = input.read(buf, total, maxBytes - total)
                    if (read < 0) break
                    total += read
                }
                buf.copyOf(total)
            } ?: ByteArray(0)
            val truncated = bytes.size >= maxBytes
            val contentType = conn.contentType ?: ""
            val text = String(bytes, Charsets.UTF_8)

            val respHeaders = JSONObject()
            conn.headerFields?.forEach { (k, v) -> if (k != null) respHeaders.put(k, v.joinToString(", ")) }
            conn.disconnect()

            JSONObject()
                .put("ok", status in 200..299)
                .put("status", status)
                .put("content_type", contentType)
                .put("bytes", bytes.size)
                .put("truncated", truncated)
                .put("headers", respHeaders)
                .put("body", text)
                .put("spoken", if (status in 200..299) "Request succeeded ($status)." else "Request returned $status.")
        }.getOrElse { error ->
            fail(error.message ?: "HTTP request failed")
        }
    }

    private fun appendQuery(url: String, query: JSONObject?): String {
        if (query == null || query.length() == 0) return url
        val builder = Uri.parse(url).buildUpon()
        query.keys().forEach { k -> builder.appendQueryParameter(k, query.opt(k)?.toString() ?: "") }
        return builder.build().toString()
    }

    /** Returns (body, contentType) or null when there is no body. */
    private fun encodeBody(body: Any?): Pair<String, String>? = when (body) {
        null, JSONObject.NULL -> null
        is JSONObject -> body.toString() to "application/json"
        is JSONArray -> body.toString() to "application/json"
        is String -> body.ifBlank { null }?.let { it to "text/plain; charset=utf-8" }
        else -> body.toString() to "text/plain; charset=utf-8"
    }

    private fun isPrivateHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        val lower = host.lowercase()
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".internal")) return true
        return runCatching {
            InetAddress.getAllByName(host).any {
                it.isLoopbackAddress || it.isSiteLocalAddress ||
                    it.isLinkLocalAddress || it.isAnyLocalAddress
            }
        }.getOrDefault(false)
    }

    private fun fail(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private companion object {
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    }
}

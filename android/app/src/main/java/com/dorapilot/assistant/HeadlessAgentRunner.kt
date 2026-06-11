package com.dorapilot.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs an agent goal headlessly (no overlay/UI) for background "heartbeat" jobs.
 *
 * It's a compact native tool-calling loop limited to background-safe tools
 * (HTTP, web search, on-device personal data, capability registry) — no intent
 * launching, which the OS blocks from the background anyway. The model selects a
 * tool, we execute it, feed the result back, and iterate until it produces a
 * short final answer (the "observation feedback" loop).
 */
class HeadlessAgentRunner(private val context: Context) {

    private val config = BackendConfig.load()
    private val backendClient = MainBackendClient()
    private val scanner = SystemCapabilityScanner(context)
    private val httpBridge = HttpBridgeServer()
    private val webSearch = DeviceWebSearchServer(configProvider = { config })
    private val appCapabilities = AppCapabilityIndexer(context)
    private val personalContext = PersonalContextEngine(
        context = context,
        scanner = scanner,
        foregroundPackageProvider = { "" }
    )

    /**
     * Run [goal] to completion; returns a short result string. [allowedTools]
     * optionally restricts the tool set (a skill's allowlist); [onStep] receives
     * a human-readable progress line for each turn/tool call.
     */
    fun run(
        goal: String,
        maxTurns: Int = 4,
        allowedTools: Set<String>? = null,
        onStep: (String) -> Unit = {}
    ): String {
        if (config.apiKey.isBlank()) return "Backend not configured."
        val tools = backgroundTools().let { all ->
            if (allowedTools.isNullOrEmpty()) all else {
                val filtered = JSONArray()
                for (i in 0 until all.length()) {
                    val t = all.optJSONObject(i) ?: continue
                    if (t.optJSONObject("function")?.optString("name") in allowedTools) filtered.put(t)
                }
                if (filtered.length() > 0) filtered else all
            }
        }
        val now = java.text.SimpleDateFormat("EEEE, MMM d yyyy, h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date())
        val messages = JSONArray()
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    "You are Dora running a scheduled background job, unattended. The current " +
                        "local date/time is $now. Use tools when helpful (web, the user's " +
                        "on-device data, HTTP APIs), then reply with a concise result the user " +
                        "can read at a glance. ALWAYS produce a useful result. NEVER ask for " +
                        "clarification or more details - make reasonable assumptions and proceed. " +
                        "If a tool isn't needed, answer directly from what you know."
                )
            )
            .put(JSONObject().put("role", "user").put("content", goal))

        var answer = ""
        for (turn in 1..maxTurns) {
            onStep("Thinking (step $turn)\u2026")
            val completion = backendClient.complete(
                MainBackendClient.CompletionRequest(
                    endpoint = config.endpoint,
                    apiKey = config.apiKey,
                    model = config.model,
                    messages = messages,
                    tools = tools,
                    headers = config.headers,
                    temperature = 0.0
                )
            )
            if (!completion.ok) return "Job failed: ${completion.error}"

            val toolCalls = completion.toolCalls
            val content = completion.outputText
                .ifBlank { completion.message.optString("content", "") }
                .trim()
            if (toolCalls.length() == 0) {
                answer = content
                break
            }

            val observation = StringBuilder("Tool results:\n")
            for (i in 0 until toolCalls.length()) {
                val fn = toolCalls.optJSONObject(i)?.optJSONObject("function") ?: continue
                val name = fn.optString("name", "").trim()
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                    .getOrDefault(JSONObject())
                onStep("Using ${name.substringAfterLast('.')}\u2026")
                val result = executeTool(name, args)
                observation.append("- ").append(name).append(": ")
                    .append(result.toString().take(1200)).append('\n')
            }
            observation.append("\nIf you have enough to answer, reply with the final result now.")
            messages.put(JSONObject().put("role", "user").put("content", observation.toString()))
        }
        return answer.ifBlank { "Job completed." }
    }

    private fun executeTool(name: String, args: JSONObject): JSONObject = runCatching {
        when (name) {
            "http.request" -> httpBridge.request(args)
            "device_web_search.search" -> webSearch.search(args.optString("query", ""))
            "app_capabilities.search" -> appCapabilities.search(args.optString("query", ""), 8)
            "personal_context.search" -> personalContext.searchPersonalData(args.optString("query", ""))
            "personal_context.snapshot" -> personalContext.getSnapshot()
            else -> JSONObject().put("ok", false).put("error", "Unknown tool: $name")
        }
    }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "tool failed") }

    private fun backgroundTools(): JSONArray {
        fun tool(name: String, desc: String, props: JSONObject, required: JSONArray = JSONArray()): JSONObject =
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", name).put("description", desc)
                    .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", required))
            )

        val q = JSONObject().put("query", JSONObject().put("type", "string"))
        return JSONArray()
            .put(
                tool(
                    "http.request",
                    "Call any HTTP/HTTPS API or webhook. Args: method, url, headers, query, body.",
                    JSONObject()
                        .put("method", JSONObject().put("type", "string"))
                        .put("url", JSONObject().put("type", "string"))
                        .put("headers", JSONObject().put("type", "object"))
                        .put("body", JSONObject().put("type", "object")),
                    JSONArray().put("url")
                )
            )
            .put(tool("device_web_search.search", "Search the web for current/factual info.", q, JSONArray().put("query")))
            .put(tool("personal_context.search", "Search the user's on-device notifications/messages/memory.", q, JSONArray().put("query")))
            .put(tool("personal_context.snapshot", "Get a snapshot of recent personal context (notifications, messages, calendar).", JSONObject()))
            .put(tool("app_capabilities.search", "Find what apps/capabilities can do something.", q, JSONArray().put("query")))
    }
}

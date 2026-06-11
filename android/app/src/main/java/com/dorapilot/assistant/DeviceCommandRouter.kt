package com.dorapilot.assistant

import org.json.JSONObject

/**
 * Fast on-device parser for explicit phone-control + app-control + writing
 * commands. Returns a normalized inference-style result ({ok, output}) when it
 * confidently matches, or null so the caller falls back to the cloud/LLM path.
 * Device/app actions are instant; writing tools call the backend through the
 * injected TextIntelligenceServer. Kept conservative so normal conversation is
 * not hijacked.
 */
class DeviceCommandRouter(
    private val deviceControl: DeviceControlServer,
    private val intentRouter: IntentRoutingServer,
    private val appResolver: (String) -> String?,
    private val appActions: AppActionsServer? = null,
    private val textIntelligence: TextIntelligenceServer? = null,
    private val screenIntelligence: ScreenIntelligenceServer? = null,
    private val timelineIntelligence: TimelineIntelligenceServer? = null,
    private val webSearch: DeviceWebSearchServer? = null,
    private val contactResolver: ((String) -> String?)? = null,
    private val deviceSearchFallback: ((String) -> JSONObject)? = null
) {
    fun tryHandle(rawPrompt: String): JSONObject? {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) return null
        val text = prompt.lowercase().replace(Regex("[\\p{Punct}]"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null

        // Don't hijack informational questions (e.g. "how does bluetooth work")
        // unless they also carry a clear imperative cue ("can you turn on ...").
        val firstWord = text.substringBefore(' ')
        val isQuestion = firstWord in QUESTION_STARTS
        val hasActionCue = ACTION_CUES.any { text.contains(it) }
        // Plain informational questions ("how does bluetooth work") must NOT be
        // hijacked by device matchers or on-device web search - let the cloud
        // assistant answer them. Only intercept when there is a clear command cue.
        if (isQuestion && !hasActionCue) return null

        timelineAction(prompt, text)?.let { return it }
        screenAction(prompt, text)?.let { return it }

        flashlight(text)?.let { return it }
        volume(text)?.let { return it }
        appAction(prompt, text)?.let { return it }
        communicationAction(prompt, text)?.let { return it }
        media(text)?.let { return it }
        quickSettings(text)?.let { return it }
        systemSearchAction(prompt, text)?.let { return it }
        openApp(prompt, text)?.let { return it }
        textIntelligence?.parseAndRun(prompt)?.let { return it }
        if (isInformationQuery(text)) {
            runWebSearch(prompt)?.let { return it }
        }
        return null
    }

    /**
     * Explicit web-search commands ("search the web for X", "look up X"). Fetches
     * an instant answer (via the worker-proxied search) and returns it IN-APP.
     * If nothing is fetched, returns null so the caller falls back to the cloud
     * assistant's own answer - never opens the browser.
     */
    private fun runWebSearch(prompt: String): JSONObject? {
        val ws = webSearch ?: return null
        val cleaned = stripSearchPrefix(prompt)
        if (cleaned.isBlank()) return null
        val res = runCatching { ws.search(cleaned) }.getOrNull() ?: return null
        val hasResults = (res.optJSONArray("results")?.length() ?: 0) > 0
        val output = res.optString("output").trim()
        if (output.isNotBlank() || hasResults) {
            return res.put("device_action", false)
        }
        return null
    }

    private fun stripSearchPrefix(prompt: String): String {
        var q = prompt.trim()
        val lower = q.lowercase()
        val prefixes = listOf(
            "search the web for ", "web search for ", "web search ", "search for ",
            "look up ", "google ", "search "
        )
        for (p in prefixes) {
            if (lower.startsWith(p)) { q = q.substring(p.length).trim(); break }
        }
        return q.trim('"', '\'', '.', ',', ':', '?')
    }

    private fun timelineAction(originalPrompt: String, text: String): JSONObject? {
        val timeline = timelineIntelligence ?: return null
        val wantsTimeline = text.contains("dora timeline") ||
            text == "timeline" ||
            text.contains("timeline my day") ||
            text.contains("my timeline") ||
            text.contains("what happened today") ||
            text.contains("what s my day look like") ||
            text.contains("what's my day look like") ||
            text.contains("what does my day look like")
        if (!wantsTimeline) return null
        return timeline.doraTimeline(originalPrompt)
            .put("device_action", false)
    }

    private fun screenAction(originalPrompt: String, text: String): JSONObject? {
        val screen = screenIntelligence ?: return null
        val mentionsScreen = text.contains("screen") || text.contains("page") ||
            text.contains("what i m looking at") || text.contains("what im looking at") ||
            text.contains("what is this") || text.contains("what s this") ||
            text.contains("what's this")
        if (!mentionsScreen) return null

        val result = when {
            text.contains("translate") -> screen.translate(extractLanguage(originalPrompt).ifBlank { "English" })
            text.contains("key point") || text.contains("bullet") -> screen.keyPoints()
            text.contains("action item") || text.contains("todo") || text.contains("to do") -> screen.actionItems()
            text.contains("summarize") || text.contains("summarise") || text.contains("tldr") ||
                text.contains("tl dr") || text.contains("what is on") || text.contains("what s on") ||
                text.contains("what's on") || text.contains("what am i looking at") ||
                text.contains("what im looking at") || text.contains("what i m looking at") ||
                text.contains("what is this") || text.contains("what s this") || text.contains("what's this") ->
                screen.summarize()
            else -> null
        } ?: return null
        return result.put("device_action", false)
    }

    private fun communicationAction(originalPrompt: String, text: String): JSONObject? {
        val actions = appActions ?: return null
        val callTarget = when {
            text.startsWith("call ") -> originalPrompt.substringAfter("call", "").trim()
            text.startsWith("dial ") -> originalPrompt.substringAfter("dial", "").trim()
            else -> ""
        }
        if (callTarget.isNotBlank()) {
            val phone = extractPhone(callTarget) ?: contactResolver?.invoke(cleanContactName(callTarget))
            if (phone.isNullOrBlank()) {
                return JSONObject()
                    .put("ok", false)
                    .put("output", "I need a phone number, or enable Contacts in Personal context so I can find ${cleanContactName(callTarget)}.")
                    .put("device_action", true)
            }
            return wrap(actions.dialNumber(phone))
        }

        val textMatch = Regex("^(?:text|message|sms)\\s+(.+?)(?:\\s+(?:message|saying|that|body)\\s+(.+))?$", RegexOption.IGNORE_CASE)
            .find(originalPrompt.trim())
        if (textMatch != null) {
            val target = textMatch.groupValues[1].trim()
            val body = textMatch.groupValues.getOrNull(2)?.trim().orEmpty()
            val phone = extractPhone(target) ?: contactResolver?.invoke(cleanContactName(target))
            if (phone.isNullOrBlank()) {
                return JSONObject()
                    .put("ok", false)
                    .put("output", "I need a phone number, or enable Contacts in Personal context so I can message ${cleanContactName(target)}.")
                    .put("device_action", true)
            }
            return wrap(actions.sendSms(phone, body))
        }
        return null
    }

    private fun flashlight(text: String): JSONObject? {
        val mentionsTorch = text.contains("flashlight") || text.contains("flash light") || text.contains("torch")
        if (!mentionsTorch) return null
        val desired = when {
            text.contains("off") || text.contains("disable") -> "off"
            text.contains("toggle") -> "toggle"
            text.contains("on") || text.contains("enable") ||
                text == "flashlight" || text == "flash light" || text == "torch" -> "on"
            else -> return null
        }
        return wrap(deviceControl.setTorch(desired))
    }

    private fun volume(text: String): JSONObject? {
        if (!text.contains("volume") && !text.contains("mute") && !text.contains("louder") &&
            !text.contains("quieter")
        ) {
            return null
        }
        val percentMatch = Regex("(\\d{1,3})\\s*(percent|%)?").find(text)
        val explicitPercent = if (text.contains("set volume") || text.contains("volume to") ||
            text.contains("volume at")
        ) {
            percentMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        } else {
            null
        }
        return when {
            explicitPercent != null -> wrap(deviceControl.setVolume(explicitPercent, null))
            text.contains("max") || text.contains("full") -> wrap(deviceControl.setVolume(null, "max"))
            text.contains("unmute") -> wrap(deviceControl.setVolume(null, "unmute"))
            text.contains("mute") || text.contains("silence") -> wrap(deviceControl.setVolume(null, "mute"))
            text.contains("up") || text.contains("raise") || text.contains("increase") ||
                text.contains("louder") -> wrap(deviceControl.setVolume(null, "up"))
            text.contains("down") || text.contains("lower") || text.contains("decrease") ||
                text.contains("quieter") -> wrap(deviceControl.setVolume(null, "down"))
            else -> null
        }
    }

    private fun appAction(originalPrompt: String, text: String): JSONObject? {
        val actions = appActions ?: return null

        // Play <query> on <spotify|youtube>
        Regex("play (.+?) on (spotify|youtube)", RegexOption.IGNORE_CASE).find(originalPrompt)?.let { m ->
            val query = m.groupValues[1].trim()
            return when (m.groupValues[2].lowercase()) {
                "spotify" -> wrap(actions.playOnSpotify(query))
                else -> wrap(actions.playOnYouTube(query))
            }
        }

        // Alarm
        if (text.contains("alarm") || text.contains("wake me up")) {
            val time = parseClockTime(text) ?: return null
            val label = extractLabel(originalPrompt)
            return wrap(actions.setAlarm(time.first, time.second, label))
        }

        // Timer / countdown
        if (text.contains("timer") || text.contains("countdown")) {
            val seconds = parseDurationSeconds(text) ?: return null
            return wrap(actions.setTimer(seconds, extractLabel(originalPrompt)))
        }

        // Navigation
        val navVerb = listOf("navigate to ", "directions to ", "take me to ", "drive to ", "navigate ")
            .firstOrNull { text.startsWith(it) }
        if (navVerb != null) {
            val destination = extractAfter(originalPrompt, listOf("navigate to", "directions to", "take me to", "drive to", "navigate"))
            val mode = when {
                text.contains("walk") -> "walk"
                text.contains("bike") || text.contains("cycl") -> "bike"
                text.contains("transit") || text.contains("public transport") -> "transit"
                else -> "drive"
            }
            if (destination.isNotBlank()) return wrap(actions.startNavigation(destination, mode))
        }

        // Camera
        if (text.contains("take a photo") || text.contains("take a picture") || text.contains("open camera")) {
            return wrap(actions.openCamera(false))
        }
        if (text.contains("record a video") || text.contains("take a video")) {
            return wrap(actions.openCamera(true))
        }

        // Calendar event. Keep parsing conservative: prefill title and only add
        // time when "today/tomorrow at <time>" is obvious.
        if (text.startsWith("create calendar event") || text.startsWith("add calendar event") ||
            text.startsWith("schedule ")
        ) {
            val title = extractAfter(originalPrompt, listOf("create calendar event", "add calendar event", "schedule"))
                .ifBlank { "New event" }
            val start = parseRelativeEventStart(text)
            val end = if (start > 0) start + 60L * 60L * 1000L else 0L
            return wrap(actions.createCalendarEvent(title, start, end, ""))
        }

        Regex("\\b(?:open|go to)\\s+((?:https?://)?[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?:/\\S*)?)", RegexOption.IGNORE_CASE)
            .find(originalPrompt)?.groupValues?.getOrNull(1)?.let { url ->
                return wrap(actions.openUrl(url))
            }

        return null
    }

    private fun systemSearchAction(originalPrompt: String, text: String): JSONObject? {
        val query = when {
            text.startsWith("search my phone for ") -> originalPrompt.substringAfter("search my phone for", "").trim()
            text.startsWith("system search ") -> originalPrompt.substringAfter("system search", "").trim()
            text.startsWith("search apps for ") -> originalPrompt.substringAfter("search apps for", "").trim()
            text == "open android search" || text == "open system search" -> ""
            else -> return null
        }
        val result = intentRouter.searchDevice(query)
        if (result.optBoolean("ok", false)) return wrap(result)
        if (query.isNotBlank()) {
            val fallback = deviceSearchFallback?.invoke(query)
            if (fallback != null) {
                val results = fallback.optJSONArray("results")
                val names = (0 until minOf(results?.length() ?: 0, 5)).mapNotNull { idx ->
                    results?.optJSONObject(idx)?.let { item ->
                        item.optString("label", item.optString("short_label", item.optString("app_label", "")))
                            .takeIf(String::isNotBlank)
                    }
                }
                return JSONObject()
                    .put("ok", true)
                    .put(
                        "output",
                        if (names.isNotEmpty()) {
                            "Android search is unavailable, but Dora found: ${names.joinToString(", ")}."
                        } else {
                            "Android search is unavailable and Dora did not find matching installed apps."
                        }
                    )
                    .put("device_action", false)
                    .put("detail", fallback)
            }
        }
        return wrap(result)
    }

    private fun media(text: String): JSONObject? {
        val mediaContext = text.contains("music") || text.contains("song") || text.contains("track") ||
            text.contains("playback") || text.contains("media") || text.contains("audio")
        return when {
            (text.contains("pause") && (mediaContext || text == "pause")) -> wrap(deviceControl.mediaControl("pause"))
            (text.contains("resume") || text.contains("unpause")) && (mediaContext || text == "resume") ->
                wrap(deviceControl.mediaControl("play"))
            (text.contains("next") || text.contains("skip")) && (mediaContext || text == "next" || text == "skip") ->
                wrap(deviceControl.mediaControl("next"))
            text.contains("previous") && (mediaContext || text == "previous") ->
                wrap(deviceControl.mediaControl("previous"))
            text.contains("stop") && mediaContext -> wrap(deviceControl.mediaControl("stop"))
            (text.startsWith("play ") || text == "play") && (mediaContext || text == "play" || text.startsWith("play music") || text.startsWith("play some")) ->
                wrap(deviceControl.mediaControl("play"))
            else -> null
        }
    }

    private fun quickSettings(text: String): JSONObject? {
        val (action, label) = when {
            text.contains("bluetooth") -> "open_bluetooth_settings" to "Opening Bluetooth settings."
            text.contains("wifi") || text.contains("wi fi") -> "open_wifi_settings" to "Opening Wi-Fi settings."
            text.contains("do not disturb") || text.contains("dnd") -> "open_do_not_disturb" to "Opening Do Not Disturb settings."
            text.contains("airplane") || text.contains("flight mode") -> "open_airplane_settings" to "Opening airplane mode settings."
            else -> return null
        }
        val result = intentRouter.launchSystemAction(action, JSONObject())
        if (result.optBoolean("ok", false) && !result.has("spoken")) {
            result.put("spoken", label)
        }
        return wrap(result)
    }

    private fun openApp(originalPrompt: String, text: String): JSONObject? {
        val verbs = listOf("open ", "launch ", "start ", "go to ")
        val verb = verbs.firstOrNull { text.startsWith(it) } ?: return null
        val rawName = originalPrompt.trim().substring(verb.length).trim()
            .removePrefix("the ").trim()
            .removeSuffix(" app").trim()
        if (rawName.isEmpty()) return null
        if (rawName.equals("settings", ignoreCase = true)) {
            return wrap(intentRouter.launchSystemAction("open_settings", JSONObject()))
        }
        val pkg = appResolver(rawName) ?: return null
        val result = intentRouter.openApp(pkg)
        if (result.optBoolean("ok", false) && !result.has("spoken")) {
            result.put("spoken", "Opening $rawName.")
        }
        return wrap(result)
    }

    // ---- parsing helpers ---------------------------------------------------

    private fun parseClockTime(text: String): Pair<Int, Int>? {
        // Matches 7, 7:30, 7 30, with optional am/pm.
        val m = Regex("\\b(\\d{1,2})(?:[:\\s](\\d{2}))?\\s*(am|pm)?\\b").find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val meridian = m.groupValues[3].lowercase()
        when (meridian) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    private fun parseDurationSeconds(text: String): Int? {
        var total = 0
        var found = false
        Regex("(\\d+)\\s*(hours?|hrs?|h)\\b").find(text)?.let { total += it.groupValues[1].toInt() * 3600; found = true }
        Regex("(\\d+)\\s*(minutes?|mins?|m)\\b").find(text)?.let { total += it.groupValues[1].toInt() * 60; found = true }
        Regex("(\\d+)\\s*(seconds?|secs?|s)\\b").find(text)?.let { total += it.groupValues[1].toInt(); found = true }
        if (!found) {
            // Bare number after "for" defaults to minutes.
            Regex("for\\s+(\\d+)\\b").find(text)?.let { total = it.groupValues[1].toInt() * 60; found = true }
        }
        return if (found && total > 0) total else null
    }

    private fun parseRelativeEventStart(text: String): Long {
        val time = parseClockTime(text) ?: return 0L
        val now = java.time.ZonedDateTime.now()
        val base = when {
            text.contains("tomorrow") -> now.plusDays(1)
            text.contains("today") -> now
            else -> now
        }
        return base.withHour(time.first).withMinute(time.second).withSecond(0).withNano(0)
            .toInstant()
            .toEpochMilli()
    }

    private fun extractAfter(prompt: String, markers: List<String>): String {
        val lower = prompt.lowercase()
        for (marker in markers) {
            val idx = lower.indexOf(marker)
            if (idx >= 0) {
                return prompt.substring(idx + marker.length).trim().trim('.', ',', ' ')
            }
        }
        return ""
    }

    private fun extractLabel(prompt: String): String {
        val m = Regex("(?:called|labeled|named|for)\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(prompt)
        return m?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun extractLanguage(prompt: String): String {
        Regex("\\b(?:to|into|in)\\s+([A-Za-z][A-Za-z\\s-]{1,30})\\s*$", RegexOption.IGNORE_CASE)
            .find(prompt.trim())?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun extractPhone(text: String): String? {
        return Regex("\\+?\\d[\\d\\s().-]{6,}\\d")
            .find(text)
            ?.value
            ?.filter { it.isDigit() || it == '+' }
    }

    private fun cleanContactName(value: String): String {
        return value
            .replace(Regex("\\b(message|saying|that|body)\\b.*", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('"', '\'', '.', ',', ':')
    }

    private fun isInformationQuery(text: String): Boolean {
        return text.startsWith("tell me about ") ||
            text.startsWith("explain ") ||
            text.startsWith("define ") ||
            text.startsWith("look up ") ||
            text.startsWith("search the web for ") ||
            text.startsWith("web search ") ||
            text.startsWith("latest ")
    }

    companion object {
        private val QUESTION_STARTS = setOf(
            "what", "whats", "how", "why", "who", "whom", "whose", "when", "where",
            "which", "is", "are", "was", "were", "am", "do", "does", "did", "can",
            "could", "would", "will", "should", "shall", "may", "might",
            "tell", "explain", "define", "describe"
        )
        private val ACTION_CUES = listOf(
            "turn on", "turn off", "turn up", "turn down", "set ", "open ", "play ",
            "launch ", "start ", "go to ", "navigate", "take me", "mute", "unmute",
            "skip", "pause", "resume", "stop", "toggle", "enable", "disable",
            "increase", "decrease", "lower", "raise", "louder", "quieter",
            "summarize", "summarise", "translate", "proofread", "fix grammar",
            "screen", "page", "what's on", "what is on", "what am i looking at",
            "dora timeline", "timeline my day", "what happened today",
            "make this", "make it", "take a photo", "take a picture",
            "record a video", "wake me", "remind", "dial", "call ", "text ", "send ",
            "search my phone", "system search", "search apps", "create calendar event",
            "add calendar event", "schedule "
        )
    }

    private fun wrap(result: JSONObject): JSONObject {
        val ok = result.optBoolean("ok", false)
        val output = when {
            result.has("spoken") -> result.optString("spoken")
            result.has("output") -> result.optString("output")
            ok -> "Done."
            else -> result.optString("error", "That command failed.")
        }
        return JSONObject()
            .put("ok", ok)
            .put("output", output)
            .put("device_action", true)
            .put("detail", result)
    }
}

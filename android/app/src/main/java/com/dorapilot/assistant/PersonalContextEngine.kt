package com.dorapilot.assistant

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Aggregates on-device, privacy-preserving signals into a compact context the
 * assistant can use to personalize answers and navigate the phone. Everything
 * stays local; nothing here makes network calls. Each probe is wrapped so a
 * single failure can never break a response.
 *
 * Durable "memory" (learned facts/preferences) is stored as JSON in filesDir so
 * it survives across sessions.
 */
class PersonalContextEngine(
    private val context: Context,
    private val scanner: SystemCapabilityScanner,
    private val foregroundPackageProvider: () -> String,
    private val config: ContextSourcesConfig = ContextSourcesConfig(context)
) {
    private val calendarReader = CalendarReader(context)
    private val contactsReader = ContactsReader(context)

    private val memoryFile: File by lazy {
        File(context.filesDir, "context/memory.json").apply { parentFile?.mkdirs() }
    }

    /** Full structured snapshot for the personal_context.get_snapshot MCP tool. */
    fun getSnapshot(): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("captured_at_ms", System.currentTimeMillis())
            .put("device", deviceState())
            .put("usage", appUsage())
            .put("navigation", navigation())
            .put("memory", memoryObject())
            .put("sources", config.all())
            .put("notifications", recentNotifications())
            .put("messages", recentMessages())
            .put("calendar", upcomingCalendar())
    }

    private fun recentNotifications(): JSONArray {
        if (!config.isEnabled(ContextSourcesConfig.NOTIFICATIONS) || !hasNotificationAccess()) {
            return JSONArray()
        }
        // recent() with messagesOnly=false includes messages too; keep only true notifications.
        val out = JSONArray()
        PersonalContextStore.recent(context, 16, messagesOnly = false)
            .filter { !it.optBoolean("is_message", false) }
            .take(8)
            .forEach { out.put(it.put("when", relativeTime(it.optLong("time", 0L)))) }
        return out
    }

    private fun recentMessages(): JSONArray {
        if (!config.isEnabled(ContextSourcesConfig.MESSAGES) || !hasNotificationAccess()) {
            return JSONArray()
        }
        val out = JSONArray()
        PersonalContextStore.recent(context, 8, messagesOnly = true)
            .forEach { out.put(it.put("when", relativeTime(it.optLong("time", 0L)))) }
        return out
    }

    private fun upcomingCalendar(): JSONArray {
        if (!config.isEnabled(ContextSourcesConfig.CALENDAR)) return JSONArray()
        return calendarReader.upcoming()
    }

    /** Compact text block injected into the chat system prompt. */
    fun summaryForPrompt(): String {
        val lines = mutableListOf<String>()
        val device = deviceState()
        lines += "Now: ${device.optString("local_time")}"
        if (device.has("battery_percent")) {
            val charging = if (device.optBoolean("charging")) " (charging)" else ""
            lines += "Battery: ${device.optInt("battery_percent")}%$charging"
        }
        device.optString("network").takeIf { it.isNotBlank() }?.let { lines += "Network: $it" }
        lines += "Media volume: ${device.optInt("media_volume_percent")}%, sound: ${device.optString("ringer_mode")}"
        device.optString("device_model").takeIf { it.isNotBlank() }?.let { lines += "Device: $it" }

        val foreground = foregroundPackageProvider().trim()
        if (foreground.isNotEmpty()) lines += "Foreground app: $foreground"

        val usage = appUsage()
        val top = usage.optJSONArray("top_apps") ?: JSONArray()
        if (top.length() > 0) {
            val names = (0 until minOf(5, top.length())).mapNotNull {
                top.optJSONObject(it)?.optString("label")?.takeIf(String::isNotBlank)
            }
            if (names.isNotEmpty()) lines += "Frequently used: ${names.joinToString(", ")}"
        }

        val installed = scanner.readCatalog().optJSONArray("apps")?.length() ?: 0
        if (installed > 0) lines += "Installed apps indexed: $installed"

        val messages = recentMessages()
        if (messages.length() > 0) {
            lines += "Recent messages:"
            for (i in 0 until minOf(5, messages.length())) {
                val m = messages.optJSONObject(i) ?: continue
                val who = m.optString("title").ifBlank { m.optString("app") }
                val body = m.optString("text").take(120)
                lines += "  - ${m.optString("app")}: $who — $body"
            }
        }

        val notifications = recentNotifications()
        if (notifications.length() > 0) {
            lines += "Recent notifications:"
            for (i in 0 until minOf(5, notifications.length())) {
                val n = notifications.optJSONObject(i) ?: continue
                lines += "  - ${n.optString("app")}: ${n.optString("title")} ${n.optString("text").take(100)}".trim()
            }
        }

        val events = upcomingCalendar()
        if (events.length() > 0) {
            lines += "Upcoming calendar:"
            for (i in 0 until minOf(5, events.length())) {
                val e = events.optJSONObject(i) ?: continue
                val loc = e.optString("location").let { if (it.isNotBlank()) " @ $it" else "" }
                lines += "  - ${e.optString("title")} (${e.optString("when")})$loc"
            }
        }

        val memory = memoryObject()
        val facts = memory.optJSONObject("facts") ?: JSONObject()
        if (facts.length() > 0) {
            val factLines = facts.keys().asSequence().take(12)
                .map { "  - $it: ${facts.optString(it)}" }
                .toList()
            if (factLines.isNotEmpty()) {
                lines += "Known user facts:"
                lines += factLines
            }
        }

        return buildString {
            append("On-device context (private, do not read back unless asked):\n")
            append(lines.joinToString("\n"))
        }
    }

    fun deviceState(): JSONObject {
        val json = JSONObject()
        runCatching {
            val now = ZonedDateTime.now()
            json.put("local_time", now.format(DateTimeFormatter.ofPattern("EEEE, MMM d yyyy, h:mm a")))
            json.put("timezone", now.zone.id)
        }
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) json.put("battery_percent", capacity)
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            json.put("charging", charging)
        }
        runCatching { json.put("network", networkType()) }
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            json.put("media_volume_percent", (cur * 100.0 / max).roundToInt())
            json.put(
                "ringer_mode",
                when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
            )
        }
        runCatching { json.put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}") }
        return json
    }

    private fun networkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val active = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(active) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "online"
        }
    }

    fun appUsage(): JSONObject {
        val result = JSONObject()
        if (!config.isEnabled(ContextSourcesConfig.USAGE)) {
            return result.put("usage_access", false).put("enabled", false).put("top_apps", JSONArray())
        }
        val granted = hasUsageAccess()
        result.put("usage_access", granted)
        if (!granted) {
            return result.put("top_apps", JSONArray())
        }
        runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 7L * 24 * 60 * 60 * 1000
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, start, end)
                ?.filter { it.totalTimeInForeground > 0 }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.take(8)
                .orEmpty()
            val pm = context.packageManager
            val top = JSONArray()
            stats.forEach { stat ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
                }.getOrDefault(stat.packageName)
                top.put(
                    JSONObject()
                        .put("package", stat.packageName)
                        .put("label", label)
                        .put("minutes", (stat.totalTimeInForeground / 60000.0).roundToInt())
                )
            }
            result.put("top_apps", top)
        }.onFailure {
            result.put("top_apps", JSONArray())
        }
        return result
    }

    private fun navigation(): JSONObject {
        val catalog = scanner.readCatalog()
        return JSONObject()
            .put("foreground_package", foregroundPackageProvider())
            .put("indexed_app_count", catalog.optJSONArray("apps")?.length() ?: 0)
            .put("catalog_age_ms", System.currentTimeMillis() - catalog.optLong("updated_at_ms", 0L))
    }

    fun hasUsageAccess(): Boolean {
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasNotificationAccess(): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    // ---- Cross-source personal search (on-device RAG retrieval) -------------

    /**
     * Builds a unified, searchable corpus from every enabled+granted source so a
     * single question can be answered from data spread across messages,
     * notifications and calendar (e.g. "when does my friend's flight land and
     * what restaurant did they suggest?"). Lexical scoring keeps it fully
     * on-device with no model; can be upgraded to embeddings later.
     */
    fun searchPersonalData(query: String, limit: Int = 8): JSONObject {
        val terms = tokenize(query)
        if (terms.isEmpty()) {
            return JSONObject().put("ok", true).put("query", query).put("count", 0).put("results", JSONArray())
        }
        val corpus = searchCorpus(terms)
        val scored = corpus.mapNotNull { item ->
            val title = item.optString("title")
            val text = item.optString("text")
            val app = item.optString("app")
            val titleLc = title.lowercase()
            val hay = "$title $text $app".lowercase()
            var score = 0.0
            for (t in terms) {
                if (hay.contains(t)) score += if (titleLc.contains(t)) 2.0 else 1.0
            }
            if (score <= 0.0) return@mapNotNull null
            // Mild recency boost so newer items win ties.
            val ageDays = ((System.currentTimeMillis() - item.optLong("time", 0L)) / 86_400_000.0).coerceAtLeast(0.0)
            item to (score + (1.0 / (1.0 + ageDays)) * 0.5)
        }.sortedByDescending { it.second }.take(limit.coerceIn(1, 20))

        val results = JSONArray()
        scored.forEach { (item, score) ->
            results.put(JSONObject(item.toString()).put("score", String.format("%.2f", score).toDouble()))
        }
        return JSONObject().put("ok", true).put("query", query).put("count", results.length()).put("results", results)
    }

    /** Build search candidates from the encrypted store (full history) + live calendar. */
    private fun searchCorpus(terms: List<String>): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        val notifAccess = hasNotificationAccess()
        val msgsOn = config.isEnabled(ContextSourcesConfig.MESSAGES)
        val notifsOn = config.isEnabled(ContextSourcesConfig.NOTIFICATIONS)
        if (notifAccess && (msgsOn || notifsOn)) {
            PersonalContextStore.searchCandidates(context, terms, 200).forEach { row ->
                val isMsg = row.optBoolean("is_message", false)
                if ((isMsg && msgsOn) || (!isMsg && notifsOn)) {
                    items += row.put("when", relativeTime(row.optLong("time", 0L)))
                }
            }
        }
        if (config.isEnabled(ContextSourcesConfig.CALENDAR)) {
            val events = calendarReader.upcoming(limit = 25, windowHours = 24 * 14)
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                items += JSONObject()
                    .put("source", "calendar")
                    .put("app", "Calendar")
                    .put("title", e.optString("title"))
                    .put("text", e.optString("location"))
                    .put("when", e.optString("when"))
                    .put("time", e.optLong("begin_ms", 0L))
            }
        }
        return items
    }

    /** Ambient context + query-relevant retrieved snippets, for the system prompt. */
    fun contextForPrompt(query: String): String {
        val ambient = summaryForPrompt()
        val search = searchPersonalData(query, 6)
        val results = search.optJSONArray("results") ?: JSONArray()
        if (results.length() == 0) return ambient
        val sb = StringBuilder("Personal data relevant to the question (retrieved on-device):")
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val src = r.optString("source")
            val app = r.optString("app")
            val title = r.optString("title")
            val text = r.optString("text").take(160)
            val whenText = r.optString("when")
            val head = listOf(title, text).filter { it.isNotBlank() }.joinToString(": ")
            sb.append("\n  - [$src · $app] $head").apply { if (whenText.isNotBlank()) append(" ($whenText)") }
        }
        return "$ambient\n\n$sb"
    }

    private fun tokenize(query: String): List<String> =
        query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }
            .distinct()

    private fun relativeTime(ms: Long): String {
        if (ms <= 0) return ""
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }

    // ---- Source management (used by the settings UI / MCP) -----------------

    /** Per-source enabled + permission-granted status for the settings panel. */
    fun getSources(): JSONObject {
        val sources = JSONObject()
        ContextSourcesConfig.KEYS.forEach { key ->
            sources.put(
                key,
                JSONObject()
                    .put("enabled", config.isEnabled(key))
                    .put("granted", isSourceGranted(key))
            )
        }
        return JSONObject().put("ok", true).put("sources", sources)
    }

    fun setSource(key: String, enabled: Boolean): JSONObject {
        if (key !in ContextSourcesConfig.KEYS) {
            return JSONObject().put("ok", false).put("error", "Unknown source: $key")
        }
        config.setEnabled(key, enabled)
        // When all notification-backed sources are off, purge the stored corpus.
        if (!enabled &&
            !config.isEnabled(ContextSourcesConfig.NOTIFICATIONS) &&
            !config.isEnabled(ContextSourcesConfig.MESSAGES)
        ) {
            PersonalContextStore.clearCorpus(context)
        }
        return JSONObject().put("ok", true).put("source", key).put("enabled", enabled)
            .put("granted", isSourceGranted(key))
    }

    fun isSourceGranted(key: String): Boolean = when (key) {
        ContextSourcesConfig.NOTIFICATIONS, ContextSourcesConfig.MESSAGES -> hasNotificationAccess()
        ContextSourcesConfig.CALENDAR -> calendarReader.hasPermission()
        ContextSourcesConfig.CONTACTS -> contactsReader.hasPermission()
        ContextSourcesConfig.USAGE -> hasUsageAccess()
        else -> false
    }

    fun resolveContactNumber(name: String): String? {
        if (!config.isEnabled(ContextSourcesConfig.CONTACTS)) return null
        return contactsReader.resolveNumber(name)
    }

    // ---- Durable memory ----------------------------------------------------

    fun remember(key: String, value: String): JSONObject {
        val k = key.trim()
        if (k.isEmpty()) return JSONObject().put("ok", false).put("error", "key is required")
        PersonalContextStore.memorySet(context, k, value.trim())
        return JSONObject().put("ok", true).put("remembered", k)
    }

    fun forget(key: String): JSONObject {
        val k = key.trim()
        PersonalContextStore.memoryDelete(context, k)
        return JSONObject().put("ok", true).put("forgot", k)
    }

    fun memoryObject(): JSONObject {
        migrateLegacyMemoryIfNeeded()
        return JSONObject().put("facts", PersonalContextStore.memoryAll(context))
    }

    private fun migrateLegacyMemoryIfNeeded() {
        if (!memoryFile.exists()) return
        runCatching {
            val legacy = JSONObject(memoryFile.readText()).optJSONObject("facts") ?: JSONObject()
            legacy.keys().forEach { key -> PersonalContextStore.memorySet(context, key, legacy.optString(key)) }
            memoryFile.delete()
        }.onFailure { memoryFile.delete() }
    }

    companion object {
        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "you", "your", "are", "was", "were", "been", "this",
            "that", "what", "when", "where", "who", "whom", "why", "how", "does", "did", "can",
            "could", "would", "should", "will", "about", "from", "they", "them", "their", "its",
            "have", "has", "had", "but", "not", "any", "all", "out", "get", "got", "tell", "ask",
            "please", "let", "she", "her", "his", "him", "our", "into", "than", "then", "there",
            "here", "just", "like", "want", "need", "say", "said", "tonight", "today"
        )
    }
}

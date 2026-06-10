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
        return NotificationStore.recent(8, messagesOnly = false)
    }

    private fun recentMessages(): JSONArray {
        if (!config.isEnabled(ContextSourcesConfig.MESSAGES) || !hasNotificationAccess()) {
            return JSONArray()
        }
        return NotificationStore.recent(8, messagesOnly = true)
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
        // When all notification-backed sources are off, drop the in-memory buffer.
        if (!enabled &&
            !config.isEnabled(ContextSourcesConfig.NOTIFICATIONS) &&
            !config.isEnabled(ContextSourcesConfig.MESSAGES)
        ) {
            NotificationStore.clear()
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
        val memory = memoryObject()
        val facts = memory.optJSONObject("facts") ?: JSONObject()
        facts.put(k, value.trim())
        memory.put("facts", facts).put("updated_at_ms", System.currentTimeMillis())
        return persistMemory(memory).put("ok", true).put("remembered", k)
    }

    fun forget(key: String): JSONObject {
        val k = key.trim()
        val memory = memoryObject()
        val facts = memory.optJSONObject("facts") ?: JSONObject()
        facts.remove(k)
        memory.put("facts", facts).put("updated_at_ms", System.currentTimeMillis())
        return persistMemory(memory).put("ok", true).put("forgot", k)
    }

    fun memoryObject(): JSONObject {
        if (!memoryFile.exists()) return JSONObject().put("facts", JSONObject())
        return runCatching { JSONObject(memoryFile.readText()) }
            .getOrDefault(JSONObject().put("facts", JSONObject()))
    }

    private fun persistMemory(memory: JSONObject): JSONObject {
        runCatching {
            memoryFile.parentFile?.mkdirs()
            memoryFile.writeText(memory.toString())
        }
        return memory
    }
}

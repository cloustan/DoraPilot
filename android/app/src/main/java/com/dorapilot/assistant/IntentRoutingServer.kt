package com.dorapilot.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import android.provider.AlarmClock
import android.provider.MediaStore
import org.json.JSONObject

class IntentRoutingServer(
    private val context: Context,
    private val deepLinkLauncher: (String, String) -> Result<String>
) {
    /**
     * Generic Android intent launcher. Lets the model express *any* app intent
     * using the standard Android format (action + data uri + mime type + package
     * + categories + extras) instead of needing a bespoke tool per app feature.
     */
    fun startIntent(args: JSONObject): JSONObject {
        val rawAction = args.optString("action", "").trim()
        val data = args.optString("data", args.optString("uri", "")).trim()
        val type = args.optString("mimeType", args.optString("type", "")).trim()
        val pkg = args.optString("package", args.optString("packageName", "")).trim()
        if (rawAction.isEmpty() && data.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "Provide at least an action or a data uri.")
        }

        val action = resolveAction(rawAction)
        val intent = Intent()
        if (action.isNotEmpty()) intent.action = action
        val dataUri = data.takeIf { it.isNotEmpty() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        // For well-known schemes the activity resolves by the data uri; forcing a
        // MIME type alongside it usually breaks resolution, so skip type there.
        val scheme = dataUri?.scheme?.lowercase()
        val schemeResolves = scheme in SCHEME_ONLY
        when {
            dataUri != null && type.isNotEmpty() && !schemeResolves -> intent.setDataAndType(dataUri, type)
            dataUri != null -> intent.data = dataUri
            type.isNotEmpty() -> intent.type = type
        }
        if (pkg.isNotEmpty()) intent.setPackage(pkg)

        args.optJSONArray("categories")?.let { cats ->
            for (i in 0 until cats.length()) {
                cats.optString(i).trim().takeIf { it.isNotEmpty() }?.let { intent.addCategory(resolveCategory(it)) }
            }
        }
        args.optString("category", "").trim().takeIf { it.isNotEmpty() }?.let {
            intent.addCategory(resolveCategory(it))
        }
        args.optJSONObject("extras")?.let { applyExtras(intent, it) }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            JSONObject()
                .put("ok", true)
                .put("spoken", spokenFor(intent.action ?: "", data, pkg))
                .put("action", intent.action ?: "")
                .put("data", data)
        }.recoverCatching { error ->
            // A wrong/absent target package is the most common failure; retry with
            // the system resolving the best app for the same intent.
            if (pkg.isNotEmpty()) {
                intent.setPackage(null)
                context.startActivity(intent)
                JSONObject()
                    .put("ok", true)
                    .put("spoken", spokenFor(intent.action ?: "", data, ""))
                    .put("action", intent.action ?: "")
                    .put("data", data)
            } else {
                throw error
            }
        }.getOrElse { error ->
            JSONObject()
                .put("ok", false)
                .put("error", error.message ?: "No app can handle that intent.")
        }
    }

    private fun resolveAction(raw: String): String {
        if (raw.isEmpty()) return ""
        if (raw.contains('.')) return raw
        return when (raw.uppercase()) {
            "VIEW" -> Intent.ACTION_VIEW
            "EDIT" -> Intent.ACTION_EDIT
            "SEND" -> Intent.ACTION_SEND
            "SEND_MULTIPLE" -> Intent.ACTION_SEND_MULTIPLE
            "SENDTO" -> Intent.ACTION_SENDTO
            "DIAL", "CALL" -> Intent.ACTION_DIAL
            "WEB_SEARCH" -> Intent.ACTION_WEB_SEARCH
            "SEARCH" -> Intent.ACTION_SEARCH
            "INSERT" -> Intent.ACTION_INSERT
            "PICK" -> Intent.ACTION_PICK
            "GET_CONTENT" -> Intent.ACTION_GET_CONTENT
            "SET_ALARM" -> AlarmClock.ACTION_SET_ALARM
            "SET_TIMER" -> AlarmClock.ACTION_SET_TIMER
            "SHOW_ALARMS" -> AlarmClock.ACTION_SHOW_ALARMS
            "SHOW_TIMERS" -> AlarmClock.ACTION_SHOW_TIMERS
            "IMAGE_CAPTURE" -> MediaStore.ACTION_IMAGE_CAPTURE
            "VIDEO_CAPTURE" -> MediaStore.ACTION_VIDEO_CAPTURE
            "SET_WALLPAPER" -> Intent.ACTION_SET_WALLPAPER
            else -> "android.intent.action.$raw"
        }
    }

    private fun resolveCategory(raw: String): String =
        if (raw.contains('.')) raw else "android.intent.category.${raw.uppercase()}"

    private fun applyExtras(intent: Intent, extras: JSONObject) {
        val keys = extras.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val mapped = mapExtraKey(key)
            val value = extras.opt(key)
            when (mapped) {
                AlarmClock.EXTRA_HOUR, AlarmClock.EXTRA_MINUTES -> intent.putExtra(mapped, toInt(value))
                AlarmClock.EXTRA_LENGTH -> intent.putExtra(mapped, normalizeSeconds(value))
                AlarmClock.EXTRA_SKIP_UI -> intent.putExtra(mapped, toBool(value))
                else -> when (value) {
                    is Boolean -> intent.putExtra(mapped, value)
                    is Int -> intent.putExtra(mapped, value)
                    is Long -> intent.putExtra(mapped, value)
                    is Double -> if (value == value.toLong().toDouble()) {
                        intent.putExtra(mapped, value.toInt())
                    } else {
                        intent.putExtra(mapped, value)
                    }
                    is String -> intent.putExtra(mapped, value)
                    else -> intent.putExtra(mapped, value?.toString() ?: "")
                }
            }
        }
    }

    // Tolerant of models that emit Java constant names (EXTRA_HOUR), fully
    // qualified constant values, or plain words. Match on the trailing token.
    private fun mapExtraKey(key: String): String {
        val k = key.lowercase()
        return when {
            k == "hour" || k.endsWith("hour") -> AlarmClock.EXTRA_HOUR
            k == "minute" || k == "minutes" || k.endsWith("minutes") || k.endsWith("minute") -> AlarmClock.EXTRA_MINUTES
            k.endsWith("length") || k == "seconds" || k.endsWith("seconds") ||
                k.endsWith("duration") || k == "duration" -> AlarmClock.EXTRA_LENGTH
            k.endsWith("skip_ui") || k == "skipui" || k.endsWith("skip") -> AlarmClock.EXTRA_SKIP_UI
            k == "message" || k == "label" || k.endsWith(".message") -> AlarmClock.EXTRA_MESSAGE
            k == "subject" || k.endsWith(".subject") -> Intent.EXTRA_SUBJECT
            k == "text" || k == "body" || k.endsWith(".text") -> Intent.EXTRA_TEXT
            k == "email" || k == "to" || k.endsWith(".email") -> Intent.EXTRA_EMAIL
            k == "query" || k.endsWith(".query") -> SearchManager.QUERY
            else -> key
        }
    }

    private fun toInt(v: Any?): Int = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt() ?: 0
        else -> 0
    }

    private fun toBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Int -> v != 0
        is String -> v.equals("true", true) || v == "1"
        else -> false
    }

    /** Timer length in seconds, correcting models that pass milliseconds. */
    private fun normalizeSeconds(v: Any?): Int {
        var n = toInt(v)
        if (n > 86_400) n /= 1000
        return n.coerceAtLeast(1)
    }

    private companion object {
        val SCHEME_ONLY = setOf(
            "http", "https", "tel", "geo", "sms", "smsto", "mailto", "market"
        )
    }

    private fun spokenFor(action: String, data: String, pkg: String): String = when {
        action.endsWith("SET_ALARM") -> "Alarm set."
        action.endsWith("SET_TIMER") -> "Timer started."
        action.endsWith("DIAL") -> "Opening the dialer."
        action.contains("IMAGE_CAPTURE") || action.contains("VIDEO_CAPTURE") -> "Opening the camera."
        action.endsWith("SEND") || action.endsWith("SENDTO") -> "Opening share."
        data.startsWith("geo:") -> "Opening Maps."
        data.startsWith("mailto:") -> "Opening email."
        data.startsWith("sms:") || data.startsWith("smsto:") -> "Opening Messages."
        data.startsWith("http") -> "Opening the link."
        pkg.isNotEmpty() -> "Opening $pkg."
        else -> "Done."
    }

    fun openApp(packageName: String): JSONObject {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "package is required")
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return JSONObject().put("ok", false).put("error", "No launch intent for package: $pkg")

        return runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            JSONObject().put("ok", true).put("package", pkg)
        }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: "failed to open app")
        }
    }

    fun searchWeb(query: String): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("ok", false).put("error", "query is required")
        return runCatching {
            val intent = Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra(SearchManager.QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            JSONObject().put("ok", true).put("query", q)
        }.getOrElse { error ->
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                JSONObject().put("ok", true).put("query", q).put("fallback", "browser")
            }.getOrElse { browserError ->
                JSONObject().put("ok", false).put("error", browserError.message ?: error.message ?: "web search failed")
            }
        }
    }

    fun searchDevice(query: String): JSONObject {
        val q = query.trim()
        val queryForIntent = q.ifBlank { " " }
        val candidates = listOf(
            Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH)
                .putExtra(SearchManager.QUERY, queryForIntent),
            Intent(Intent.ACTION_SEARCH)
                .putExtra(SearchManager.QUERY, queryForIntent),
            Intent(Intent.ACTION_VIEW, Uri.parse("android-app://com.google.android.googlequicksearchbox/search"))
                .putExtra(SearchManager.QUERY, queryForIntent)
        )
        candidates.forEach { candidate ->
            val result = runCatching {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(candidate)
                JSONObject()
                    .put("ok", true)
                    .put("query", q)
                    .put("spoken", if (q.isBlank()) "Opening Android search." else "Opening Android search for $q.")
                    .put("intent_action", candidate.action ?: "view")
            }.getOrNull()
            if (result != null) return result
        }
        return JSONObject()
            .put("ok", false)
            .put("query", q)
            .put(
                "error",
                "This launcher/search provider does not expose a public Android search intent."
            )
    }

    fun openMapsQuery(query: String): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("ok", false).put("error", "query is required")
        return fireAppDeepLink("geo:0,0?q=${Uri.encode(q)}", "com.google.android.apps.maps")
    }

    fun fireAppDeepLink(uri: String, packageName: String): JSONObject {
        if (uri.isBlank()) {
            return JSONObject().put("ok", false).put("error", "uri is required")
        }
        return deepLinkLauncher(uri, packageName).fold(
            onSuccess = { JSONObject().put("ok", true).put("message", it) },
            onFailure = { JSONObject().put("ok", false).put("error", it.message ?: "launch failed") }
        )
    }

    fun launchSystemAction(actionType: String, args: JSONObject): JSONObject {
        val action = actionType.trim().lowercase()
        val intent = when (action) {
            "open_settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
            "open_wifi_settings", "toggle_wifi" -> Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            "open_bluetooth_settings", "toggle_bluetooth" ->
                Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            "open_do_not_disturb", "open_dnd" ->
                Intent("android.settings.ZEN_MODE_SETTINGS")
            "open_airplane_settings" ->
                Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "open_usage_access", "grant_usage_access" ->
                Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "open_weather" -> {
                // Prefer any installed weather app; fallback to web weather search.
                val weatherPackages = listOf(
                    "com.sec.android.daemonapp",
                    "com.google.android.apps.weather"
                )
                val pm = context.packageManager
                val launchIntent = weatherPackages
                    .asSequence()
                    .mapNotNull { pm.getLaunchIntentForPackage(it) }
                    .firstOrNull()
                launchIntent ?: Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=weather")
                )
            }
            "open_app" -> {
                val pkg = args.optString("package", "").trim()
                if (pkg.isEmpty()) return JSONObject().put("ok", false).put("error", "args.package required")
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return JSONObject().put("ok", false).put("error", "No launch intent for package: $pkg")
                launchIntent
            }
            "web_search" -> {
                val query = args.optString("query", "").trim()
                if (query.isEmpty()) return JSONObject().put("ok", false).put("error", "args.query required")
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
            }
            "device_search", "global_search", "system_search" -> {
                return searchDevice(args.optString("query", "").trim())
            }
            "open_maps_query" -> {
                val query = args.optString("query", "").trim()
                if (query.isEmpty()) return JSONObject().put("ok", false).put("error", "args.query required")
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
                    .setPackage("com.google.android.apps.maps")
            }
            "dial_number" -> {
                val phone = args.optString("phone", "").trim()
                if (phone.isEmpty()) return JSONObject().put("ok", false).put("error", "args.phone required")
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            }
            "send_sms" -> {
                val phone = args.optString("phone", "").trim()
                val body = args.optString("body", "")
                if (phone.isEmpty()) return JSONObject().put("ok", false).put("error", "args.phone required")
                Intent(Intent.ACTION_VIEW, Uri.parse("sms:$phone")).putExtra("sms_body", body)
            }
            else -> return JSONObject().put("ok", false).put("error", "Unknown actionType: $actionType")
        }

        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            JSONObject().put("ok", true).put("action", actionType)
        }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: "failed to launch action")
        }
    }
}

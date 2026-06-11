package com.dorapilot.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import org.json.JSONObject

class IntentRoutingServer(
    private val context: Context,
    private val deepLinkLauncher: (String, String) -> Result<String>
) {
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

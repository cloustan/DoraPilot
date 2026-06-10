package com.dorapilot.assistant

import org.json.JSONObject

class ContextTriageScreenServer(
    private val activeScreenProvider: () -> JSONObject,
    private val foregroundPackageProvider: () -> String,
    private val capabilityCatalogProvider: () -> JSONObject
) {
    fun getActiveScreenJson(): JSONObject {
        val screen = activeScreenProvider()
        val pkg = foregroundPackageProvider()
        val catalog = capabilityCatalogProvider()
        val relevant = findPackageEntry(catalog, pkg)

        return JSONObject()
            .put("ok", true)
            .put("foreground_package", pkg)
            .put("active_screen", screen)
            .put("relevant_app_catalog", relevant)
    }

    fun getForegroundPackage(): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("foreground_package", foregroundPackageProvider())
    }

    fun getRelevantToolsForForegroundApp(): JSONObject {
        val pkg = foregroundPackageProvider()
        val bucket = when {
            pkg.contains("launcher", ignoreCase = true) -> listOf(
                "intent_routing_server.open_app",
                "system_capability_scanner.find_tools",
                "context_triage_screen.get_active_screen_json"
            )
            pkg.contains("maps", ignoreCase = true) -> listOf(
                "intent_routing_server.open_maps_query",
                "transaction_monitor.verify_app_state",
                "context_triage_screen.get_active_screen_json"
            )
            pkg.contains("message", ignoreCase = true) || pkg.contains("sms", ignoreCase = true) -> listOf(
                "intent_routing_server.launch_system_action",
                "transaction_monitor.verify_app_state",
                "context_triage_screen.get_active_screen_json"
            )
            else -> listOf(
                "context_triage_screen.get_active_screen_json",
                "intent_routing_server.fire_app_deep_link",
                "transaction_monitor.verify_app_state"
            )
        }

        return JSONObject()
            .put("ok", true)
            .put("foreground_package", pkg)
            .put("relevant_tools", bucket)
    }

    private fun findPackageEntry(catalog: JSONObject, packageName: String): JSONObject {
        val apps = catalog.optJSONArray("apps") ?: return JSONObject()
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            if (app.optString("package") == packageName) {
                return app
            }
        }
        return JSONObject()
    }
}

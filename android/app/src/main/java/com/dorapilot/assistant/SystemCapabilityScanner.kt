package com.dorapilot.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.os.Build
import android.content.pm.ShortcutManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SystemCapabilityScanner(
    private val context: Context
) {
    fun indexAllApps(): JSONObject {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherActivities = pm.queryIntentActivities(launcherIntent, 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val apps = JSONArray()
        launcherActivities.forEach { info ->
            val packageName = info.activityInfo.packageName
            val appJson = JSONObject()
                .put("package", packageName)
                .put("label", info.loadLabel(pm).toString())
                .put("launch_activity", info.activityInfo.name)
                .put("shortcuts", shortcutsForPackage(packageName))
            apps.put(appJson)
        }

        val catalog = JSONObject()
            .put("updated_at_ms", System.currentTimeMillis())
            .put("app_count", apps.length())
            .put("apps", apps)

        writeCatalog(catalog)
        return JSONObject()
            .put("ok", true)
            .put("app_count", apps.length())
            .put("catalog_path", catalogFile().absolutePath)
    }

    fun updateChangedPackage(packageName: String): JSONObject {
        if (packageName.isBlank()) {
            return JSONObject().put("ok", false).put("error", "packageName is required")
        }

        val current = readCatalog()
        val apps = current.optJSONArray("apps") ?: JSONArray()
        val updatedEntry = scanSinglePackage(packageName)

        val rebuilt = JSONArray()
        var replaced = false
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            if (app.optString("package") == packageName) {
                replaced = true
                if (updatedEntry != null) rebuilt.put(updatedEntry)
            } else {
                rebuilt.put(app)
            }
        }
        if (!replaced && updatedEntry != null) {
            rebuilt.put(updatedEntry)
        }

        val updatedCatalog = JSONObject()
            .put("updated_at_ms", System.currentTimeMillis())
            .put("app_count", rebuilt.length())
            .put("apps", rebuilt)
        writeCatalog(updatedCatalog)

        return JSONObject()
            .put("ok", true)
            .put("package", packageName)
            .put("present", updatedEntry != null)
            .put("catalog_path", catalogFile().absolutePath)
    }

    /**
     * Cheap navigation index guarantee: only rebuilds the catalog when it is
     * missing or older than [maxAgeMs]. Returns quickly when the catalog is
     * already fresh, so it is safe to call on every app launch.
     */
    fun ensureFreshIndex(maxAgeMs: Long = DEFAULT_INDEX_TTL_MS): JSONObject {
        val catalog = readCatalog()
        val count = catalog.optJSONArray("apps")?.length() ?: 0
        val ageMs = System.currentTimeMillis() - catalog.optLong("updated_at_ms", 0L)
        val fresh = count > 0 && ageMs in 0 until maxAgeMs
        if (fresh) {
            return JSONObject()
                .put("ok", true)
                .put("reindexed", false)
                .put("app_count", count)
                .put("age_ms", ageMs)
        }
        return indexAllApps().put("reindexed", true)
    }

    fun quickStats(): JSONObject {
        val catalog = readCatalog()
        val count = catalog.optJSONArray("apps")?.length() ?: 0
        val updatedAt = catalog.optLong("updated_at_ms", 0L)
        val ageMs = if (updatedAt > 0) System.currentTimeMillis() - updatedAt else -1L
        return JSONObject()
            .put("ok", true)
            .put("app_count", count)
            .put("updated_at_ms", updatedAt)
            .put("age_ms", ageMs)
            .put("fresh", count > 0 && ageMs in 0 until DEFAULT_INDEX_TTL_MS)
            .put("catalog_path", catalogFile().absolutePath)
    }

    fun readCatalog(): JSONObject {
        val file = catalogFile()
        if (!file.exists()) return JSONObject().put("apps", JSONArray())
        return runCatching { JSONObject(file.readText()) }
            .getOrDefault(JSONObject().put("apps", JSONArray()))
    }

    fun findTools(query: String, limit: Int = 12): JSONObject {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "query is required")
        }

        val catalog = readCatalog()
        val apps = catalog.optJSONArray("apps") ?: JSONArray()
        val results = JSONArray()

        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            val pkg = app.optString("package", "")
            val label = app.optString("label", "")
            val launchActivity = app.optString("launch_activity", "")
            val haystack = "$pkg $label $launchActivity".lowercase()

            if (haystack.contains(normalizedQuery)) {
                results.put(
                    JSONObject()
                        .put("type", "app")
                        .put("label", label)
                        .put("package", pkg)
                        .put("launch_activity", launchActivity)
                )
            }

            val shortcuts = app.optJSONArray("shortcuts") ?: JSONArray()
            for (j in 0 until shortcuts.length()) {
                val shortcut = shortcuts.optJSONObject(j) ?: continue
                val shortLabel = shortcut.optString("short_label", "")
                val longLabel = shortcut.optString("long_label", "")
                val shortcutHaystack = "$shortLabel $longLabel".lowercase()
                if (shortcutHaystack.contains(normalizedQuery)) {
                    results.put(
                        JSONObject()
                            .put("type", "shortcut")
                            .put("package", pkg)
                            .put("app_label", label)
                            .put("id", shortcut.optString("id", ""))
                            .put("short_label", shortLabel)
                            .put("long_label", longLabel)
                    )
                }
            }
        }

        val sliced = JSONArray()
        val max = minOf(limit.coerceAtLeast(1), results.length())
        for (i in 0 until max) {
            sliced.put(results.opt(i))
        }

        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", results.length())
            .put("results", sliced)
    }

    /**
     * Resolve a launchable package from a spoken app name (e.g. "spotify",
     * "you tube"). Queries PackageManager live for reliability, with exact,
     * prefix, then contains matching. Returns null when nothing reasonable matches.
     */
    fun resolvePackageForLabel(name: String): String? {
        val query = name.trim().lowercase().replace(Regex("\\s+"), " ")
        if (query.isEmpty()) return null
        val compactQuery = query.replace(" ", "")

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrNull()
            ?: return null

        val labeled = candidates.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm).toString().lowercase()
            Triple(pkg, label, label.replace(Regex("\\s+"), ""))
        }

        labeled.firstOrNull { it.second == query || it.third == compactQuery }?.let { return it.first }
        labeled.firstOrNull { it.second.startsWith(query) || it.third.startsWith(compactQuery) }?.let { return it.first }
        labeled.firstOrNull {
            it.second.contains(query) || it.third.contains(compactQuery) ||
                query.contains(it.second) || compactQuery.contains(it.third)
        }?.let { return it.first }
        return null
    }

    private fun scanSinglePackage(packageName: String): JSONObject? {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val info = pm.queryIntentActivities(launcherIntent, 0)
            .firstOrNull { it.activityInfo.packageName == packageName } ?: return null

        return JSONObject()
            .put("package", packageName)
            .put("label", info.loadLabel(pm).toString())
            .put("launch_activity", info.activityInfo.name)
            .put("shortcuts", shortcutsForPackage(packageName))
    }

    private fun shortcutsForPackage(packageName: String): JSONArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return JSONArray()
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return JSONArray()

        val merged = LinkedHashMap<String, ShortcutInfo>()
        fun addAll(items: List<ShortcutInfo>) {
            items.filter { it.`package` == packageName }.forEach { info ->
                val key = info.id.ifBlank { "${packageName}:${info.shortLabel}" }
                merged[key] = info
            }
        }

        runCatching { addAll(manager.pinnedShortcuts) }
        runCatching { addAll(manager.dynamicShortcuts) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { addAll(manager.manifestShortcuts) }
        }

        return JSONArray().apply {
            merged.values.forEach { shortcut ->
                put(
                    JSONObject()
                        .put("id", shortcut.id)
                        .put("short_label", shortcut.shortLabel?.toString().orEmpty())
                        .put("long_label", shortcut.longLabel?.toString().orEmpty())
                        .put("rank", shortcut.rank)
                )
            }
        }
    }

    private fun writeCatalog(catalog: JSONObject) {
        val file = catalogFile()
        file.parentFile?.mkdirs()
        file.writeText(catalog.toString())
    }

    private fun catalogFile(): File {
        return File(context.filesDir, "mcp/system_capabilities.json")
    }

    companion object {
        // Treat the navigation catalog as fresh for 30 minutes.
        private const val DEFAULT_INDEX_TTL_MS = 30L * 60 * 1000
    }
}

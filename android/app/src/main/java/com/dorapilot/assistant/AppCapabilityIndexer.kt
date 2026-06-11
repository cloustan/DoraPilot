package com.dorapilot.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a device-verified registry of app capabilities and indexes it into the
 * encrypted SQLite store ([PersonalContextStore]) with lightweight RAG search.
 *
 * Three sources, all verified against this device so we never offer an action no
 * app can fulfil:
 *  1. Installed launchable apps (so "open X" is searchable).
 *  2. Standard Android intents/schemes, kept only if `queryIntentActivities`
 *     finds a handler (dial, sms, email, share, maps, alarm, camera, ...).
 *  3. A curated per-app deep-link template set, kept only when the app is
 *     installed (Spotify search, YouTube, WhatsApp, Maps nav, ...).
 *
 * The model never authors a URI: it searches this registry and fills typed
 * slots into the verified template, which is what makes cross-app control
 * reliable rather than flaky.
 */
class AppCapabilityIndexer(private val context: Context) {

    data class Standard(
        val name: String,
        val action: String,
        val uriTemplate: String,
        val mime: String,
        val slots: List<String>,
        val extras: JSONObject,
        val keywords: String,
        val probeUri: String
    )

    data class Curated(
        val packages: List<String>,
        val defaultLabel: String,
        val name: String,
        val action: String,
        val uriTemplate: String,
        val mime: String,
        val slots: List<String>,
        val extras: JSONObject,
        val keywords: String
    )

    fun ensureFresh(maxAgeMs: Long = DEFAULT_TTL_MS): JSONObject {
        val count = PersonalContextStore.capabilityCount(context)
        val updated = indexUpdatedAt()
        val age = System.currentTimeMillis() - updated
        if (count > 0 && age in 0 until maxAgeMs) {
            return stats(count, updated).put("reindexed", false)
        }
        return reindex()
    }

    fun reindex(): JSONObject {
        val pm = context.packageManager
        PersonalContextStore.clearCapabilities(context)
        var n = 0

        // 1. Installed launchable apps.
        val installed = HashMap<String, String>()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList()).forEach { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@forEach
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            if (installed.put(pkg, label) == null) {
                PersonalContextStore.upsertCapability(
                    context, pkg, label, "app_launch", "Open $label",
                    Intent.ACTION_MAIN, "", "", "[]", "{}",
                    "open launch start app $label $pkg"
                )
                n++
            }
        }

        // 2. Standard intents/schemes, verified by a resolvable handler.
        STANDARD.forEach { s ->
            if (resolves(s.action, s.probeUri, s.mime, null)) {
                PersonalContextStore.upsertCapability(
                    context, "", "", "standard", s.name, s.action,
                    s.uriTemplate, s.mime, JSONArray(s.slots).toString(),
                    s.extras.toString(), s.keywords
                )
                n++
            }
        }

        // 3. Curated per-app deep links, verified by an installed package.
        CURATED.forEach { t ->
            val pkg = t.packages.firstOrNull { installed.containsKey(it) } ?: return@forEach
            val label = installed[pkg] ?: t.defaultLabel
            PersonalContextStore.upsertCapability(
                context, pkg, label, "app_deeplink", t.name, t.action,
                t.uriTemplate, t.mime, JSONArray(t.slots).toString(),
                t.extras.toString(), "${t.keywords} $label"
            )
            n++
        }

        val now = System.currentTimeMillis()
        PersonalContextStore.memorySet(context, INDEX_KEY, now.toString())
        return stats(n, now).put("reindexed", true)
    }

    fun search(query: String, limit: Int = 12): JSONObject {
        val terms = tokenize(query)
        val candidates = PersonalContextStore.searchCapabilities(context, terms, 120)
        val scored = candidates
            .map { it to score(it, terms) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 40))
        val results = JSONArray()
        scored.forEach { (cap, s) -> results.put(JSONObject(cap.toString()).put("score", s)) }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", results.length())
            .put("results", results)
    }

    fun quickStats(): JSONObject {
        val count = PersonalContextStore.capabilityCount(context)
        return stats(count, indexUpdatedAt())
    }

    private fun stats(count: Int, updatedAt: Long): JSONObject {
        val age = if (updatedAt > 0) System.currentTimeMillis() - updatedAt else -1L
        return JSONObject()
            .put("ok", true)
            .put("capability_count", count)
            .put("updated_at_ms", updatedAt)
            .put("age_ms", age)
            .put("fresh", count > 0 && age in 0 until DEFAULT_TTL_MS)
    }

    private fun indexUpdatedAt(): Long =
        PersonalContextStore.memoryAll(context).optString(INDEX_KEY, "0").toLongOrNull() ?: 0L

    private fun resolves(action: String, uri: String, mime: String, pkg: String?): Boolean {
        val intent = Intent()
        if (action.isNotEmpty()) intent.action = action
        val dataUri = uri.takeIf { it.isNotEmpty() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        when {
            dataUri != null && mime.isNotEmpty() -> intent.setDataAndType(dataUri, mime)
            dataUri != null -> intent.data = dataUri
            mime.isNotEmpty() -> intent.type = mime
        }
        if (pkg != null) intent.setPackage(pkg)
        return runCatching {
            intent.resolveActivity(context.packageManager) != null ||
                context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }.getOrDefault(false)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOPWORDS }
            .distinct()

    private fun score(cap: JSONObject, terms: List<String>): Int {
        if (terms.isEmpty()) return 1
        val name = cap.optString("name", "").lowercase()
        val keywords = cap.optString("keywords", "").lowercase()
        val app = cap.optString("app_label", "").lowercase()
        var s = 0
        for (t in terms) {
            if (name.contains(t)) s += 4
            if (app.contains(t)) s += 3
            if (keywords.contains(t)) s += 2
        }
        // Prefer concrete app deep links, then standard actions, over bare launches.
        when (cap.optString("cap_type", "")) {
            "app_deeplink" -> s += 2
            "standard" -> s += 1
        }
        return s
    }

    companion object {
        private const val INDEX_KEY = "cap_index_updated"
        private const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000 // 6 hours

        private val STOPWORDS = setOf(
            "the", "a", "an", "to", "for", "of", "on", "in", "my", "me", "please",
            "and", "with", "open", "go", "can", "you", "i"
        )

        private fun ex(vararg pairs: Pair<String, String>): JSONObject {
            val o = JSONObject()
            pairs.forEach { o.put(it.first, it.second) }
            return o
        }

        private val STANDARD = listOf(
            Standard("Call a phone number", Intent.ACTION_DIAL, "tel:{phone}", "",
                listOf("phone"), JSONObject(), "call dial phone number ring", "tel:0000000"),
            Standard("Send a text message", Intent.ACTION_SENDTO, "smsto:{phone}", "",
                listOf("phone", "message"), ex("text" to "{message}"),
                "text sms message send texting", "smsto:0000000"),
            Standard("Send an email", Intent.ACTION_SENDTO, "mailto:{email}", "",
                listOf("email", "subject", "body"), ex("subject" to "{subject}", "text" to "{body}"),
                "email mail compose send gmail outlook", "mailto:test@example.com"),
            Standard("Share text", Intent.ACTION_SEND, "", "text/plain",
                listOf("text"), ex("text" to "{text}"), "share send forward text", ""),
            Standard("Open a website", Intent.ACTION_VIEW, "{url}", "",
                listOf("url"), JSONObject(), "open website url link browser web page", "https://example.com"),
            Standard("Get directions / find a place", Intent.ACTION_VIEW, "geo:0,0?q={query}", "",
                listOf("query"), JSONObject(),
                "map maps directions navigate route find place location nearby", "geo:0,0?q=test"),
            Standard("Set an alarm", AlarmClock.ACTION_SET_ALARM, "", "",
                listOf("hour", "minutes", "message"),
                ex("hour" to "{hour}", "minutes" to "{minutes}", "message" to "{message}"),
                "alarm wake clock morning", ""),
            Standard("Start a timer", AlarmClock.ACTION_SET_TIMER, "", "",
                listOf("length", "message"), ex("length" to "{length}", "message" to "{message}"),
                "timer countdown minutes seconds", ""),
            Standard("Take a photo", MediaStore.ACTION_IMAGE_CAPTURE, "", "",
                emptyList(), JSONObject(), "camera photo picture selfie shot", ""),
            Standard("Record a video", MediaStore.ACTION_VIDEO_CAPTURE, "", "",
                emptyList(), JSONObject(), "camera video record clip film", ""),
            Standard("Search the web", Intent.ACTION_WEB_SEARCH, "", "",
                listOf("query"), ex("query" to "{query}"), "search google web lookup find", ""),
            Standard("Install an app", Intent.ACTION_VIEW, "market://search?q={query}", "",
                listOf("query"), JSONObject(),
                "play store install download app update market", "market://search?q=test")
        )

        private val CURATED = listOf(
            Curated(listOf("com.spotify.music"), "Spotify", "Search and play on Spotify",
                Intent.ACTION_VIEW, "spotify:search:{query}", "", listOf("query"), JSONObject(),
                "spotify music song play listen artist album playlist"),
            Curated(listOf("com.google.android.youtube"), "YouTube", "Search YouTube",
                Intent.ACTION_VIEW, "https://www.youtube.com/results?search_query={query}", "",
                listOf("query"), JSONObject(), "youtube video watch search clip"),
            Curated(listOf("com.google.android.apps.youtube.music"), "YouTube Music",
                "Search YouTube Music", Intent.ACTION_VIEW,
                "https://music.youtube.com/search?q={query}", "", listOf("query"), JSONObject(),
                "youtube music song play listen"),
            Curated(listOf("com.google.android.apps.maps"), "Maps", "Navigate with Google Maps",
                Intent.ACTION_VIEW, "google.navigation:q={query}", "", listOf("query"), JSONObject(),
                "maps navigate directions drive route to"),
            Curated(listOf("com.whatsapp"), "WhatsApp", "Message on WhatsApp",
                Intent.ACTION_VIEW, "https://wa.me/{phone}?text={text}", "",
                listOf("phone", "text"), JSONObject(), "whatsapp message chat send text"),
            Curated(listOf("org.telegram.messenger"), "Telegram", "Share to Telegram",
                Intent.ACTION_VIEW, "tg://msg?text={text}", "", listOf("text"), JSONObject(),
                "telegram message chat send"),
            Curated(listOf("com.netflix.mediaclient"), "Netflix", "Search Netflix",
                Intent.ACTION_VIEW, "https://www.netflix.com/search?q={query}", "",
                listOf("query"), JSONObject(), "netflix movie show watch search stream"),
            Curated(listOf("com.twitter.android", "com.x.android"), "X", "Post on X",
                Intent.ACTION_VIEW, "https://twitter.com/intent/tweet?text={text}", "",
                listOf("text"), JSONObject(), "twitter x tweet post share"),
            Curated(listOf("com.spotify.music"), "Spotify", "Open Spotify playlist by URL",
                Intent.ACTION_VIEW, "{url}", "", listOf("url"), JSONObject(),
                "spotify open playlist link"),
            Curated(listOf("com.amazon.mShop.android.shopping"), "Amazon", "Search Amazon",
                Intent.ACTION_VIEW, "https://www.amazon.com/s?k={query}", "",
                listOf("query"), JSONObject(), "amazon shop buy order search product")
        )
    }
}

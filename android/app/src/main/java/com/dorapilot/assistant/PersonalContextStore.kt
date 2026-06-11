package com.dorapilot.assistant

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject

/**
 * Encrypted, persistent on-device store for personal context, backed by
 * SQLCipher (AES-256). The DB passphrase is held in the Android Keystore via
 * [SecureContextKey], so the data is unreadable without the device unlocked.
 *
 * Holds the searchable corpus (messages/notifications captured by the listener)
 * plus durable memory facts. Calendar/contacts are still read live from the OS
 * and never copied here.
 */
object PersonalContextStore {
    private const val DB_NAME = "dora_context.db"
    private const val MAX_ROWS = 5000

    @Volatile
    private var database: SQLiteDatabase? = null

    @Synchronized
    private fun db(context: Context): SQLiteDatabase {
        database?.let { if (it.isOpen) return it }
        val appCtx = context.applicationContext
        System.loadLibrary("sqlcipher")
        val file = appCtx.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val password = SecureContextKey.getPassphraseString(appCtx)
        val opened = SQLiteDatabase.openOrCreateDatabase(file, password, null, null)
        opened.execSQL(
            "CREATE TABLE IF NOT EXISTS items(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT, app TEXT, title TEXT, " +
                "body TEXT, ts INTEGER, is_message INTEGER, dkey TEXT UNIQUE)"
        )
        opened.execSQL("CREATE INDEX IF NOT EXISTS idx_items_ts ON items(ts)")
        opened.execSQL(
            "CREATE TABLE IF NOT EXISTS memory(k TEXT PRIMARY KEY, v TEXT, updated INTEGER)"
        )
        opened.execSQL(
            "CREATE TABLE IF NOT EXISTS capabilities(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, package TEXT, app_label TEXT, " +
                "cap_type TEXT, name TEXT, action TEXT, uri_template TEXT, mime TEXT, " +
                "slots TEXT, extras TEXT, keywords TEXT, verified INTEGER, updated INTEGER, " +
                "UNIQUE(package, name))"
        )
        opened.execSQL(
            "CREATE TABLE IF NOT EXISTS jobs(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, goal TEXT, " +
                "trigger_type TEXT, interval_min INTEGER, at_minutes INTEGER, event TEXT, " +
                "enabled INTEGER, last_run INTEGER, last_result TEXT, created INTEGER)"
        )
        opened.execSQL(
            "CREATE TABLE IF NOT EXISTS skills(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, description TEXT, " +
                "instructions TEXT, tools TEXT, trigger_type TEXT, interval_min INTEGER, " +
                "at_minutes INTEGER, event TEXT, source TEXT, enabled INTEGER, " +
                "last_run INTEGER, last_result TEXT, created INTEGER)"
        )
        database = opened
        return opened
    }

    // ---- skills (ClawHub-style reusable agent skills) ----------------------

    fun upsertSkill(
        context: Context,
        name: String,
        description: String,
        instructions: String,
        tools: String,
        triggerType: String,
        intervalMin: Int,
        atMinutes: Int,
        event: String,
        source: String
    ): Long = runCatching {
        val values = ContentValues().apply {
            put("name", name)
            put("description", description)
            put("instructions", instructions)
            put("tools", tools)
            put("trigger_type", triggerType)
            put("interval_min", intervalMin)
            put("at_minutes", atMinutes)
            put("event", event)
            put("source", source)
            put("enabled", 1)
            put("last_run", 0L)
            put("last_result", "")
            put("created", System.currentTimeMillis())
        }
        db(context).insertWithOnConflict("skills", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }.getOrDefault(-1L)

    fun listSkills(context: Context): List<JSONObject> = skillQuery(
        context,
        "SELECT id,name,description,instructions,tools,trigger_type,interval_min,at_minutes,event,source,enabled,last_run,last_result FROM skills ORDER BY id",
        emptyArray()
    )

    fun skillById(context: Context, id: Long): JSONObject? = skillQuery(
        context,
        "SELECT id,name,description,instructions,tools,trigger_type,interval_min,at_minutes,event,source,enabled,last_run,last_result FROM skills WHERE id=?",
        arrayOf(id.toString())
    ).firstOrNull()

    fun deleteSkill(context: Context, id: Long) {
        runCatching { db(context).delete("skills", "id=?", arrayOf(id.toString())) }
    }

    fun setSkillEnabled(context: Context, id: Long, enabled: Boolean) {
        runCatching {
            val v = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
            db(context).update("skills", v, "id=?", arrayOf(id.toString()))
        }
    }

    fun markSkillRun(context: Context, id: Long, result: String) {
        runCatching {
            val v = ContentValues().apply {
                put("last_run", System.currentTimeMillis())
                put("last_result", result.take(2000))
            }
            db(context).update("skills", v, "id=?", arrayOf(id.toString()))
        }
    }

    private fun skillQuery(context: Context, sql: String, args: Array<String>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        runCatching {
            db(context).rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    out += JSONObject()
                        .put("id", c.getLong(0))
                        .put("name", c.getString(1))
                        .put("description", c.getString(2))
                        .put("instructions", c.getString(3))
                        .put("tools", c.getString(4))
                        .put("trigger_type", c.getString(5))
                        .put("interval_min", c.getInt(6))
                        .put("at_minutes", c.getInt(7))
                        .put("event", c.getString(8))
                        .put("source", c.getString(9))
                        .put("enabled", c.getInt(10) == 1)
                        .put("last_run", c.getLong(11))
                        .put("last_result", c.getString(12))
                }
            }
        }
        return out
    }

    // ---- automation jobs ---------------------------------------------------

    fun addJob(
        context: Context,
        title: String,
        goal: String,
        triggerType: String,
        intervalMin: Int,
        atMinutes: Int,
        event: String
    ): Long = runCatching {
        val values = ContentValues().apply {
            put("title", title)
            put("goal", goal)
            put("trigger_type", triggerType)
            put("interval_min", intervalMin)
            put("at_minutes", atMinutes)
            put("event", event)
            put("enabled", 1)
            put("last_run", 0L)
            put("last_result", "")
            put("created", System.currentTimeMillis())
        }
        db(context).insert("jobs", null, values)
    }.getOrDefault(-1L)

    fun listJobs(context: Context): List<JSONObject> = jobQuery(
        context, "SELECT id,title,goal,trigger_type,interval_min,at_minutes,event,enabled,last_run,last_result FROM jobs ORDER BY id", emptyArray()
    )

    fun jobById(context: Context, id: Long): JSONObject? = jobQuery(
        context, "SELECT id,title,goal,trigger_type,interval_min,at_minutes,event,enabled,last_run,last_result FROM jobs WHERE id=?", arrayOf(id.toString())
    ).firstOrNull()

    fun deleteJob(context: Context, id: Long) {
        runCatching { db(context).delete("jobs", "id=?", arrayOf(id.toString())) }
    }

    fun setJobEnabled(context: Context, id: Long, enabled: Boolean) {
        runCatching {
            val v = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
            db(context).update("jobs", v, "id=?", arrayOf(id.toString()))
        }
    }

    fun markJobRun(context: Context, id: Long, result: String) {
        runCatching {
            val v = ContentValues().apply {
                put("last_run", System.currentTimeMillis())
                put("last_result", result.take(2000))
            }
            db(context).update("jobs", v, "id=?", arrayOf(id.toString()))
        }
    }

    private fun jobQuery(context: Context, sql: String, args: Array<String>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        runCatching {
            db(context).rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    out += JSONObject()
                        .put("id", c.getLong(0))
                        .put("title", c.getString(1))
                        .put("goal", c.getString(2))
                        .put("trigger_type", c.getString(3))
                        .put("interval_min", c.getInt(4))
                        .put("at_minutes", c.getInt(5))
                        .put("event", c.getString(6))
                        .put("enabled", c.getInt(7) == 1)
                        .put("last_run", c.getLong(8))
                        .put("last_result", c.getString(9))
                }
            }
        }
        return out
    }

    // ---- app capability registry ------------------------------------------

    fun upsertCapability(
        context: Context,
        packageName: String,
        appLabel: String,
        capType: String,
        name: String,
        action: String,
        uriTemplate: String,
        mime: String,
        slots: String,
        extras: String,
        keywords: String
    ) {
        runCatching {
            val values = ContentValues().apply {
                put("package", packageName)
                put("app_label", appLabel)
                put("cap_type", capType)
                put("name", name)
                put("action", action)
                put("uri_template", uriTemplate)
                put("mime", mime)
                put("slots", slots)
                put("extras", extras)
                put("keywords", keywords.lowercase())
                put("verified", 1)
                put("updated", System.currentTimeMillis())
            }
            db(context).insertWithOnConflict("capabilities", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun clearCapabilities(context: Context) {
        runCatching { db(context).delete("capabilities", null, null) }
    }

    fun capabilityCount(context: Context): Int = runCatching {
        db(context).rawQuery("SELECT COUNT(*) FROM capabilities", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.getOrDefault(0)

    /** Rows whose name/keywords/app_label match ANY query term (LIKE). Indexer scores them. */
    fun searchCapabilities(context: Context, terms: List<String>, limit: Int = 80): List<JSONObject> {
        if (terms.isEmpty()) return allCapabilities(context, limit)
        val safe = terms.take(8)
        val clause = safe.joinToString(" OR ") {
            "(name LIKE ? OR keywords LIKE ? OR app_label LIKE ? OR package LIKE ?)"
        }
        val args = mutableListOf<String>()
        safe.forEach { t -> val like = "%$t%"; repeat(4) { args.add(like) } }
        args.add(limit.coerceIn(1, 400).toString())
        return capabilityQuery(
            context,
            "SELECT package,app_label,cap_type,name,action,uri_template,mime,slots,extras,keywords " +
                "FROM capabilities WHERE $clause LIMIT ?",
            args.toTypedArray()
        )
    }

    fun allCapabilities(context: Context, limit: Int = 200): List<JSONObject> = capabilityQuery(
        context,
        "SELECT package,app_label,cap_type,name,action,uri_template,mime,slots,extras,keywords " +
            "FROM capabilities LIMIT ?",
        arrayOf(limit.coerceIn(1, 1000).toString())
    )

    private fun capabilityQuery(context: Context, sql: String, args: Array<String>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        runCatching {
            db(context).rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    out += JSONObject()
                        .put("package", c.getString(0))
                        .put("app_label", c.getString(1))
                        .put("cap_type", c.getString(2))
                        .put("name", c.getString(3))
                        .put("action", c.getString(4))
                        .put("uri_template", c.getString(5))
                        .put("mime", c.getString(6))
                        .put("slots", c.getString(7))
                        .put("extras", c.getString(8))
                        .put("keywords", c.getString(9))
                }
            }
        }
        return out
    }

    // ---- corpus ------------------------------------------------------------

    fun insertItem(
        context: Context,
        source: String,
        app: String,
        title: String,
        body: String,
        ts: Long,
        isMessage: Boolean
    ) {
        runCatching {
            val values = ContentValues().apply {
                put("source", source)
                put("app", app)
                put("title", title)
                put("body", body)
                put("ts", ts)
                put("is_message", if (isMessage) 1 else 0)
                put("dkey", "$source|$app|$title|$body")
            }
            db(context).insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            prune(context)
        }
    }

    fun recent(context: Context, limit: Int, messagesOnly: Boolean): List<JSONObject> {
        val where = if (messagesOnly) "WHERE is_message=1" else ""
        return query(context, "SELECT source,app,title,body,ts,is_message FROM items $where ORDER BY ts DESC LIMIT ?", arrayOf(limit.coerceIn(1, 200).toString()))
    }

    /** Candidate rows matching ANY query term (LIKE), most recent first. Engine scores them. */
    fun searchCandidates(context: Context, terms: List<String>, limit: Int = 200): List<JSONObject> {
        if (terms.isEmpty()) return emptyList()
        val safe = terms.take(8)
        val clause = safe.joinToString(" OR ") { "(title LIKE ? OR body LIKE ? OR app LIKE ?)" }
        val args = mutableListOf<String>()
        safe.forEach { t -> val like = "%$t%"; args.add(like); args.add(like); args.add(like) }
        args.add(limit.coerceIn(1, 500).toString())
        return query(
            context,
            "SELECT source,app,title,body,ts,is_message FROM items WHERE $clause ORDER BY ts DESC LIMIT ?",
            args.toTypedArray()
        )
    }

    fun count(context: Context): Int = runCatching {
        db(context).rawQuery("SELECT COUNT(*) FROM items", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.getOrDefault(0)

    fun clearCorpus(context: Context, messagesOnly: Boolean = false) {
        runCatching {
            if (messagesOnly) db(context).delete("items", "is_message=1", null)
            else db(context).delete("items", null, null)
        }
    }

    private fun prune(context: Context) {
        runCatching {
            db(context).execSQL(
                "DELETE FROM items WHERE id NOT IN (SELECT id FROM items ORDER BY ts DESC LIMIT $MAX_ROWS)"
            )
        }
    }

    private fun query(context: Context, sql: String, args: Array<String>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        runCatching {
            db(context).rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    out += JSONObject()
                        .put("source", c.getString(0))
                        .put("app", c.getString(1))
                        .put("title", c.getString(2))
                        .put("text", c.getString(3))
                        .put("time", c.getLong(4))
                        .put("is_message", c.getInt(5) == 1)
                }
            }
        }
        return out
    }

    // ---- memory ------------------------------------------------------------

    fun memorySet(context: Context, key: String, value: String) {
        runCatching {
            val values = ContentValues().apply {
                put("k", key)
                put("v", value)
                put("updated", System.currentTimeMillis())
            }
            db(context).insertWithOnConflict("memory", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun memoryDelete(context: Context, key: String) {
        runCatching { db(context).delete("memory", "k=?", arrayOf(key)) }
    }

    fun memoryAll(context: Context): JSONObject {
        val facts = JSONObject()
        runCatching {
            db(context).rawQuery("SELECT k,v FROM memory ORDER BY updated DESC", null).use { c ->
                while (c.moveToNext()) facts.put(c.getString(0), c.getString(1))
            }
        }
        return facts
    }
}

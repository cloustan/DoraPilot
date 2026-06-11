package com.dorapilot.assistant

import android.content.ContentValues
import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
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
        SQLiteDatabase.loadLibs(appCtx)
        val file = appCtx.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val password = SecureContextKey.getPassphraseString(appCtx)
        val opened = SQLiteDatabase.openOrCreateDatabase(file, password, null)
        opened.execSQL(
            "CREATE TABLE IF NOT EXISTS items(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT, app TEXT, title TEXT, " +
                "body TEXT, ts INTEGER, is_message INTEGER, dkey TEXT UNIQUE)"
        )
        opened.execSQL("CREATE INDEX IF NOT EXISTS idx_items_ts ON items(ts)")
        opened.execSQL(
            "CREATE TABLE IF NOT EXISTS memory(k TEXT PRIMARY KEY, v TEXT, updated INTEGER)"
        )
        database = opened
        return opened
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

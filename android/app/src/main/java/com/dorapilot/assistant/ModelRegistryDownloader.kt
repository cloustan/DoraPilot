package com.dorapilot.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads on-device model bundles from the dora-pilot-model-registry Worker
 * (R2-backed) into the app's private model directory, where
 * [LocalOnnxRuntimeEngine] resolves them.
 *
 * Progress is reported as JSON strings on the "model_registry" terminal
 * channel via the supplied emitter:
 *   {"state":"checking|downloading|verifying|ready|error", ...}
 */
class ModelRegistryDownloader(
    private val context: Context,
    private val config: BackendConfig
) {
    private val userAgent = "dora-pilot-android/1.0"

    @Volatile
    private var downloadInProgress = false

    private fun defaultModelId(): String =
        config.localGenAiModelFilesDir.trim().trimEnd('/').substringAfterLast('/')

    private fun activeModelId(): String =
        LocalModelSelection.activeDir(context, config.localGenAiModelFilesDir)
            .trim().trimEnd('/').substringAfterLast('/')

    private fun modelDir(modelId: String): String =
        if (modelId == defaultModelId()) config.localGenAiModelFilesDir else "models/$modelId"

    private fun targetDir(modelId: String): File = File(context.filesDir, modelDir(modelId))

    fun setActiveModel(modelId: String): JSONObject {
        val check = localState(modelId) ?: return JSONObject()
            .put("ok", false)
            .put("error", "Model '$modelId' not found in registry.")
        if (!check.optBoolean("complete", false)) {
            return JSONObject().put("ok", false).put("error", "Model '$modelId' is not fully downloaded.")
        }
        LocalModelSelection.setActiveDir(context, modelDir(modelId))
        return JSONObject().put("ok", true).put("active_id", modelId)
    }

    /**
     * Full registry catalog annotated with local download state, the active
     * model, and a device-RAM based recommendation.
     */
    fun catalog(): JSONObject {
        val endpoint = config.modelRegistryEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Model registry endpoint not configured.")
        }
        val manifest = runCatching { fetchManifest() }.getOrElse { error ->
            return JSONObject().put("ok", false).put("error", error.message ?: "Registry unreachable.")
        }
        val ramGb = deviceRamGb()
        val activeId = activeModelId()
        val out = JSONArray()
        var recommendedIdx = -1
        var recommendedSize = -1L
        for (i in 0 until manifest.length()) {
            val model = manifest.getJSONObject(i)
            val id = model.optString("id")
            val entry = JSONObject()
                .put("id", id)
                .put("display_name", model.optString("display_name", id))
                .put("params", model.optString("params", ""))
                .put("description", model.optString("description", ""))
                .put("min_ram_gb", model.optDouble("min_ram_gb", 0.0))
                .put("total_size", model.optLong("total_size"))
                .put("active", id == activeId)
            annotateLocalState(entry, model)
            val suitable = entry.optDouble("min_ram_gb", 0.0) <= ramGb
            entry.put("suitable", suitable)
            if (suitable && model.optLong("total_size") > recommendedSize) {
                recommendedSize = model.optLong("total_size")
                recommendedIdx = i
            }
            out.put(entry)
        }
        if (recommendedIdx >= 0) out.getJSONObject(recommendedIdx).put("recommended", true)
        return JSONObject()
            .put("ok", true)
            .put("device_ram_gb", ramGb)
            .put("active_id", activeId)
            .put("in_progress", downloadInProgress)
            .put("models", out)
    }

    private fun deviceRamGb(): Double {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            Math.round(info.totalMem / (1024.0 * 1024 * 1024) * 10) / 10.0
        }.getOrDefault(0.0)
    }

    /** Fills complete / files_missing / local_bytes for a manifest model. */
    private fun annotateLocalState(entry: JSONObject, manifestModel: JSONObject) {
        val files = manifestModel.optJSONArray("files") ?: JSONArray()
        val dir = targetDir(manifestModel.optString("id"))
        var missing = 0
        var localBytes = 0L
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val local = File(dir, file.getString("name"))
            if (!local.exists() || local.length() != file.getLong("size")) {
                missing += 1
            } else {
                localBytes += local.length()
            }
        }
        entry.put("file_count", files.length())
            .put("files_missing", missing)
            .put("local_bytes", localBytes)
            .put("complete", files.length() > 0 && missing == 0)
    }

    private fun localState(modelId: String): JSONObject? {
        val manifest = fetchManifestEntry(modelId) ?: return null
        return JSONObject().put("id", modelId).also { annotateLocalState(it, manifest) }
    }

    /** Compares local files against the registry manifest. */
    fun status(modelId: String = activeModelId()): JSONObject {
        val endpoint = config.modelRegistryEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Model registry endpoint not configured.")
        }
        val manifest = fetchManifestEntry(modelId)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "Model '$modelId' not found in registry.")

        val state = JSONObject().also { annotateLocalState(it, manifest) }
        return JSONObject()
            .put("ok", true)
            .put("model_id", modelId)
            .put("total_size", manifest.getLong("total_size"))
            .put("file_count", state.optInt("file_count"))
            .put("files_missing", state.optInt("files_missing"))
            .put("local_bytes", state.optLong("local_bytes"))
            .put("complete", state.optBoolean("complete"))
            .put("in_progress", downloadInProgress)
    }

    /** Downloads all missing/partial files. Safe to re-run; resumes partial files. */
    fun download(modelId: String = activeModelId(), emit: (String) -> Unit): JSONObject {
        if (downloadInProgress) {
            return JSONObject().put("ok", false).put("error", "Download already in progress.")
        }
        downloadInProgress = true
        try {
            emit(progressJson("checking", JSONObject().put("model_id", modelId)))
            val manifest = fetchManifestEntry(modelId)
                ?: return fail(emit, modelId, "Model '$modelId' not found in registry.")

            val files = manifest.getJSONArray("files")
            val totalSize = manifest.getLong("total_size")
            val dir = targetDir(modelId).apply { mkdirs() }

            var doneBytes = 0L
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                val name = entry.getString("name")
                val size = entry.getLong("size")
                val local = File(dir, name)
                if (local.exists() && local.length() == size) {
                    doneBytes += size
                    continue
                }
                downloadFile(modelId, name, size, local) { fileBytes ->
                    emit(
                        progressJson(
                            "downloading",
                            JSONObject()
                                .put("model_id", modelId)
                                .put("file", name)
                                .put("done_bytes", doneBytes + fileBytes)
                                .put("total_bytes", totalSize)
                                .put("pct", ((doneBytes + fileBytes) * 100 / totalSize).toInt())
                        )
                    )
                }
                doneBytes += size
            }

            emit(progressJson("verifying", JSONObject().put("model_id", modelId)))
            val verified = status(modelId)
            return if (verified.optBoolean("complete", false)) {
                emit(
                    progressJson(
                        "ready",
                        JSONObject().put("model_id", modelId).put("total_bytes", totalSize)
                    )
                )
                JSONObject().put("ok", true).put("model_id", modelId).put("total_bytes", totalSize)
            } else {
                fail(emit, modelId, "Verification failed: ${verified.optInt("files_missing")} file(s) incomplete.")
            }
        } catch (error: Exception) {
            return fail(emit, modelId, error.message ?: error.javaClass.simpleName)
        } finally {
            downloadInProgress = false
        }
    }

    private fun fail(emit: (String) -> Unit, modelId: String, message: String): JSONObject {
        emit(progressJson("error", JSONObject().put("model_id", modelId).put("message", message)))
        return JSONObject().put("ok", false).put("error", message)
    }

    private fun progressJson(state: String, fields: JSONObject): String {
        fields.put("state", state)
        return fields.toString()
    }

    private fun fetchManifest(): JSONArray {
        val endpoint = config.modelRegistryEndpoint.trim().trimEnd('/')
        val body = httpGetText("$endpoint/v1/models")
        return JSONObject(body).optJSONArray("models") ?: JSONArray()
    }

    private fun fetchManifestEntry(modelId: String): JSONObject? {
        val models = runCatching { fetchManifest() }.getOrNull() ?: return null
        for (i in 0 until models.length()) {
            val model = models.getJSONObject(i)
            if (model.optString("id") == modelId) return model
        }
        return null
    }

    private fun httpGetText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode} for $url")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(
        modelId: String,
        name: String,
        expectedSize: Long,
        target: File,
        onProgress: (Long) -> Unit
    ) {
        val endpoint = config.modelRegistryEndpoint.trim().trimEnd('/')
        val resumeFrom = if (target.exists() && target.length() < expectedSize) target.length() else 0L
        if (target.exists() && resumeFrom == 0L) target.delete()

        val url = "$endpoint/v1/models/$modelId/$name"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", userAgent)
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 206) {
                error("HTTP $code downloading $name")
            }
            val append = resumeFrom > 0 && code == 206
            var written = if (append) resumeFrom else 0L
            var lastEmit = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastEmit >= 16L * 1024 * 1024) {
                            lastEmit = written
                            onProgress(written)
                        }
                    }
                }
            }
            if (written != expectedSize) {
                error("$name incomplete: $written of $expectedSize bytes")
            }
            onProgress(written)
        } finally {
            connection.disconnect()
        }
    }
}

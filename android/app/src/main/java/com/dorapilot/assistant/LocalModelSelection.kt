package com.dorapilot.assistant

import android.content.Context

/**
 * Runtime-selected local model. The build config supplies a default model dir;
 * once the user picks a model from the registry catalog, the choice is stored
 * here and both [LocalOnnxRuntimeEngine] and [ModelRegistryDownloader] follow it.
 */
object LocalModelSelection {
    private const val PREFS = "dora_local_models"
    private const val KEY_ACTIVE_DIR = "active_model_dir"

    fun activeDir(context: Context, fallback: String): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_DIR, null)
            ?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    fun setActiveDir(context: Context, dir: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_DIR, dir.trim())
            .apply()
    }
}

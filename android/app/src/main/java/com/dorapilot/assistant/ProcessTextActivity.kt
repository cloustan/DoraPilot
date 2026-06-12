package com.dorapilot.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dorapilot.ContextSheet
import com.dorapilot.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Native Android selected-text actions for the long-press text menu.
 *
 * Apps that support ACTION_PROCESS_TEXT show a single "Dora" entry beside
 * Copy/Paste; tapping it opens the Material bottom sheet with one tile per
 * action. Editable fields can receive replacement text via EXTRA_PROCESS_TEXT
 * result; read-only selections stay safe by showing and copying the result.
 */
open class ProcessTextActivity : Activity() {
    /** Preset action for subclasses; null shows the action chooser sheet. */
    protected open val doraAction: DoraTextAction? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val backendClient = MainBackendClient()
    private val backendConfig by lazy { BackendConfig.load() }
    private val textIntelligence by lazy {
        TextIntelligenceServer(
            context = this,
            backendClient = backendClient,
            configProvider = { backendConfig }
        )
    }

    private lateinit var statusText: TextView
    private lateinit var bodyText: TextView
    private lateinit var copyButton: Button
    private lateinit var replaceButton: Button

    private var selectedText: String = ""
    private var readOnly: Boolean = true
    private var latestOutput: String = ""
    private var activeAction: DoraTextAction = DoraTextAction.SUMMARIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(doraAction?.titleRes ?: R.string.process_text_dora)
        selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

        setContentView(buildContentView())
        if (selectedText.isBlank()) {
            showFinal(
                ok = false,
                message = "Select some text first, then choose a Dora action.",
                output = ""
            )
            return
        }

        bodyText.text = selectedText.take(600)
        val preset = doraAction
        if (preset != null) {
            startAction(preset)
        } else {
            showActionChooser()
        }
    }

    /** One tile per Dora action; picking one runs it, dismissing cancels. */
    private fun showActionChooser() {
        statusText.text = "Choose a Dora action"
        val items = JSONArray()
        DoraTextAction.entries.forEach { action ->
            items.put(
                JSONObject()
                    .put("id", action.name)
                    .put("title", getString(action.titleRes).removePrefix("Dora "))
                    .put("subtitle", action.subtitle)
            )
        }
        ContextSheet.show(this, "Dora", items.toString(), onCancel = { finish() }) { id ->
            val action = DoraTextAction.entries.firstOrNull { it.name == id }
            if (action == null) {
                finish()
            } else {
                startAction(action)
            }
        }
    }

    private fun startAction(action: DoraTextAction) {
        activeAction = action
        title = getString(action.titleRes)
        statusText.text = "Dora is working…"
        executor.execute {
            val result = runCatching { runSelectedTextAction(action, selectedText) }.getOrElse { error ->
                JSONObject()
                    .put("ok", false)
                    .put("output", error.message ?: "Dora text action failed.")
            }
            val ok = result.optBoolean("ok", false)
            val output = result.optString("output", result.optString("error", "")).trim()
            runOnUiThread {
                if (ok && output.isNotBlank() && !readOnly && action.returnsReplacement) {
                    returnReplacement(output)
                } else {
                    showFinal(
                        ok = ok,
                        message = if (ok) "Done" else "Dora could not complete this action.",
                        output = output.ifBlank { result.optString("error", "No output returned.") }
                    )
                    if (ok && output.isNotBlank()) {
                        copyToClipboard(output)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun runSelectedTextAction(action: DoraTextAction, text: String): JSONObject {
        return when (action) {
            DoraTextAction.SUMMARIZE -> textIntelligence.transform(TextIntelligenceServer.Mode.SUMMARIZE, text)
            DoraTextAction.KEY_POINTS -> textIntelligence.transform(TextIntelligenceServer.Mode.KEY_POINTS, text)
            DoraTextAction.PROOFREAD -> textIntelligence.transform(TextIntelligenceServer.Mode.PROOFREAD, text)
            DoraTextAction.TRANSLATE -> textIntelligence.transform(
                TextIntelligenceServer.Mode.TRANSLATE,
                text,
                Locale.getDefault().displayLanguage.ifBlank { "English" }
            )
            DoraTextAction.REPLY_COACH -> textIntelligence.transform(
                TextIntelligenceServer.Mode.COMPOSE,
                "Draft one concise, friendly reply to this message. Reply with only the message text.\n\nMessage:\n$text"
            )
        }
    }

    private fun returnReplacement(output: String) {
        val data = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, output)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun showFinal(ok: Boolean, message: String, output: String) {
        latestOutput = output
        statusText.text = message
        statusText.setTextColor(if (ok) Color.rgb(22, 101, 52) else Color.rgb(185, 28, 28))
        bodyText.text = output.ifBlank { selectedText }
        copyButton.visibility = if (output.isNotBlank()) View.VISIBLE else View.GONE
        replaceButton.visibility = if (!readOnly && output.isNotBlank() && activeAction.returnsReplacement) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(248, 251, 255))
        }

        statusText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(22, 35, 63))
            text = getString(doraAction?.titleRes ?: R.string.process_text_dora)
            setPadding(0, 0, 0, 14)
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        bodyText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(31, 41, 55))
            setLineSpacing(0f, 1.12f)
        }
        scroll.addView(bodyText)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        copyButton = Button(this).apply {
            text = "Copy result"
            visibility = View.GONE
            setOnClickListener {
                copyToClipboard(latestOutput)
                Toast.makeText(this@ProcessTextActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copyButton, LinearLayout.LayoutParams(-1, -2))

        replaceButton = Button(this).apply {
            text = "Replace selected text"
            visibility = View.GONE
            setOnClickListener { returnReplacement(latestOutput) }
        }
        root.addView(replaceButton, LinearLayout.LayoutParams(-1, -2))

        val closeButton = Button(this).apply {
            text = "Close"
            gravity = Gravity.CENTER
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        root.addView(closeButton, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Dora result", text))
    }
}

enum class DoraTextAction(
    val titleRes: Int,
    val returnsReplacement: Boolean,
    val subtitle: String
) {
    SUMMARIZE(R.string.process_text_summarize, true, "Condense the selection"),
    PROOFREAD(R.string.process_text_proofread, true, "Fix grammar and clarity"),
    TRANSLATE(R.string.process_text_translate, true, "Translate to your language"),
    KEY_POINTS(R.string.process_text_key_points, true, "Bullet the main ideas"),
    REPLY_COACH(R.string.process_text_reply_coach, true, "Draft a reply")
}

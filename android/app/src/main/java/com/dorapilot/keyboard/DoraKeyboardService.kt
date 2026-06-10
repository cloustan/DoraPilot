package com.dorapilot.keyboard

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dorapilot.assistant.BackendConfig
import com.dorapilot.assistant.MainBackendClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class DoraKeyboardService : InputMethodService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backendConfig by lazy { BackendConfig.load() }
    private val backendClient = MainBackendClient()
    private lateinit var statusText: TextView

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 16)
            background = glassBackground()
        }

        statusText = TextView(this).apply {
            text = "Dora Writing Tools"
            setTextColor(Color.rgb(245, 249, 255))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 10)
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        root.addView(toolRow("Fix grammar", "Polite", "Shorter"))
        root.addView(toolRow("Rewrite", "Professional", "Bullets"))
        root.addView(keyRow("qwertyuiop"))
        root.addView(keyRow("asdfghjkl"))
        root.addView(keyRow("zxcvbnm"))
        root.addView(bottomRow())
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (::statusText.isInitialized) {
            statusText.text = "Dora Writing Tools"
        }
    }

    private fun toolRow(vararg labels: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEach { label ->
                addView(toolButton(label), LinearLayout.LayoutParams(0, 44, 1f).apply {
                    setMargins(4, 4, 4, 4)
                })
            }
        }
    }

    private fun keyRow(keys: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEach { char ->
                addView(keyButton(char.toString()), LinearLayout.LayoutParams(0, 48, 1f).apply {
                    setMargins(3, 4, 3, 4)
                })
            }
        }
    }

    private fun bottomRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(keyButton("space") { commit(" ") }, LinearLayout.LayoutParams(0, 50, 3f).apply {
                setMargins(3, 4, 3, 4)
            })
            addView(keyButton("⌫") { currentInputConnection?.deleteSurroundingText(1, 0) }, LinearLayout.LayoutParams(0, 50, 1f).apply {
                setMargins(3, 4, 3, 4)
            })
            addView(keyButton("↵") { currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)) }, LinearLayout.LayoutParams(0, 50, 1f).apply {
                setMargins(3, 4, 3, 4)
            })
        }
    }

    private fun keyButton(label: String, action: (() -> Unit)? = null): Button {
        return Button(this).apply {
            text = label
            textSize = if (label.length == 1) 18f else 14f
            setTextColor(Color.rgb(22, 32, 54))
            background = keyBackground()
            setOnClickListener {
                if (action != null) {
                    action()
                } else {
                    commit(label)
                }
            }
        }
    }

    private fun toolButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            background = toolBackground()
            setOnClickListener { applyWritingTool(label) }
        }
    }

    private fun applyWritingTool(label: String) {
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        val before = connection.getTextBeforeCursor(1200, 0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(1200, 0)?.toString().orEmpty()
        val source = selected.ifBlank { before.takeLast(700) + after.take(500) }.trim()
        if (source.isBlank()) {
            statusText.text = "Select or type text first."
            return
        }

        statusText.text = "$label..."
        executor.execute {
            val result = runCatching { requestRewrite(label, source) }.getOrElse { error ->
                JSONObject().put("ok", false).put("error", error.message ?: "Rewrite failed")
            }
            val output = result.optString("output", "").trim()
            mainHandler.post {
                if (result.optBoolean("ok", false) && output.isNotBlank()) {
                    if (selected.isNotBlank()) {
                        connection.commitText(output, 1)
                    } else {
                        connection.deleteSurroundingText(before.length, after.length)
                        connection.commitText(output, 1)
                    }
                    statusText.text = "Done"
                } else {
                    statusText.text = result.optString("error", "Could not rewrite")
                }
            }
        }
    }

    private fun requestRewrite(label: String, text: String): JSONObject {
        val prompt = """
            Rewrite the text using this instruction: $label.
            Return only the rewritten text. Keep meaning intact.

            Text:
            $text
        """.trimIndent()

        return backendClient.infer(
            JSONObject()
                .put("prompt", prompt)
                .put("system", "You are Dora's writing tool. Return only the transformed text, no preface.")
                .put("history", JSONArray())
                .put("endpoint", backendConfig.endpoint)
                .put("model", backendConfig.model)
                .put("api_key", backendConfig.apiKey)
                .put("headers", backendConfig.headers)
        )
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun glassBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(236, 235, 244, 255), Color.argb(222, 205, 224, 255))
        ).apply {
            cornerRadius = 32f
        }
    }

    private fun keyBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.argb(235, 255, 255, 255))
            cornerRadius = 18f
            setStroke(1, Color.argb(110, 255, 255, 255))
        }
    }

    private fun toolBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(0, 85, 255), Color.rgb(112, 164, 255))
        ).apply {
            cornerRadius = 22f
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}

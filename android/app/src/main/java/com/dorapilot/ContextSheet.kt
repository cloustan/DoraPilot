package com.dorapilot

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Material bottom sheet for context menus: rounded sheet with a light
 * blue -> white gradient, rounded tiles, and a drag handle. Items are supplied
 * as JSON ([{id,title,subtitle}]); tapping a tile invokes [onPick] with the id.
 *
 * (Chosen over Jetpack Compose's ModalBottomSheet to avoid pulling the Compose
 * toolchain into this WebView/AppCompat app; the result is equivalently native
 * and uses the already-present Material3 dependency + theme.)
 */
object ContextSheet {

    fun show(
        activity: Activity,
        title: String,
        itemsJson: String,
        onCancel: (() -> Unit)? = null,
        onPick: (String) -> Unit
    ) {
        val items = runCatching { JSONArray(itemsJson) }.getOrNull() ?: return
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_context_sheet)
            setPadding(px(16), px(10), px(16), px(20))
        }

        // Drag handle.
        root.addView(View(activity).apply {
            setBackgroundResource(R.drawable.bg_sheet_handle)
            layoutParams = LinearLayout.LayoutParams(px(36), px(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = px(14)
            }
        })

        if (title.isNotBlank()) {
            root.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.parseColor("#0F2350"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(px(6), 0, px(6), px(10))
            })
        }

        val dialog = BottomSheetDialog(activity)
        var picked = false

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            root.addView(buildTile(activity, item, ::px) {
                picked = true
                dialog.dismiss()
                onPick(id)
            })
        }

        dialog.setContentView(root)
        // Let the gradient run to the sheet edges.
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.setOnDismissListener { if (!picked) onCancel?.invoke() }
        dialog.show()
    }

    private fun buildTile(
        activity: Activity,
        item: JSONObject,
        px: (Int) -> Int,
        onClick: () -> Unit
    ): View {
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_context_tile)
            setPadding(px(16), px(14), px(16), px(14))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(10) }
            setOnClickListener { onClick() }
        }
        tile.addView(TextView(activity).apply {
            text = item.optString("title")
            setTextColor(Color.parseColor("#0F2350"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        })
        val sub = item.optString("subtitle")
        if (sub.isNotBlank()) {
            tile.addView(TextView(activity).apply {
                text = sub
                setTextColor(Color.parseColor("#5A6B8C"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, px(2), 0, 0)
            })
        }
        return tile
    }
}

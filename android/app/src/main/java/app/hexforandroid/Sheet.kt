package app.hexforandroid

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Hex's modal sheet, docked to the bottom on a phone: a raised surface with a
 * title, a few lines of explanation, its content, and the actions at the foot
 * (desktop Hex's Vocabulary modal, tests/golden/rune-vocabulary-budget-*.png).
 */
class Sheet(context: Context, private val ui: Ui, title: String, lines: List<String> = emptyList()) : Dialog(context) {
    val body: LinearLayout = ui.vertical()
    val footer: LinearLayout = ui.horizontal()
    private val explanation = ui.vertical()

    init {
        val t = ui.t
        val panel = ui.vertical {
            background = GradientDrawable().apply {
                setColor(t.surface)
                val r = ui.px(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(1, t.border)
            }
            setPadding(ui.px(18), ui.px(10), ui.px(18), ui.px(18))
            // The grab handle.
            addView(View(context).apply { background = ui.rounded(t.border, 3f) }, LinearLayout.LayoutParams(ui.px(36), ui.px(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = ui.px(14)
            })
            addView(ui.text(title, 17f, ui.semibold))
            addView(explanation.apply { setPadding(0, ui.px(6), 0, ui.px(12)) })
            addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer.apply { setPadding(0, ui.px(14), 0, 0) })
        }
        lines.forEach { explain(it) }
        val bottom = panel.paddingBottom
        panel.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom + bars.bottom)
            insets
        }
        setContentView(panel)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // Keep the sheet below 85% of the screen so its list scrolls.
        panel.post {
            val max = (context.resources.displayMetrics.heightPixels * 0.85f).toInt()
            if (panel.height > max) window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, max)
        }
    }

    fun explain(line: String) = ui.description(line).also {
        it.setPadding(0, 0, 0, ui.px(3))
        explanation.addView(it)
    }

    fun explainView(view: View) = explanation.addView(view)

    /** Puts [left] actions at the start of the footer and [right] at the end. */
    fun actions(left: List<View>, right: List<View>) {
        left.forEach { footer.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.px(8) }) }
        footer.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        right.forEach { footer.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.px(8) }) }
    }
}

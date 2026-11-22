package app.hexforandroid

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hex's desktop look on Android: the tokens of `rune-theme-opencode` as
 * Hex's golden renders draw them (tests/golden/rune-mac-settings-*.png),
 * Inter and IBM Plex Mono from Rune, and the pieces Hex's settings pages are
 * built from (docs/design-system.md): cards of rows with a leading line icon,
 * a title, a one-phrase description and one control.
 */
class Tokens(val dark: Boolean) {
    val window = if (dark) 0xFF080808.toInt() else 0xFFFAFAFA.toInt()
    val surface = if (dark) 0xFF161616.toInt() else 0xFFFFFFFF.toInt()
    val raised = if (dark) 0xFF1F1F1F.toInt() else 0xFFF6F6F6.toInt()
    val border = if (dark) 0xFF343434.toInt() else 0xFFDBDBDB.toInt()
    val divider = if (dark) 0xFF282828.toInt() else 0xFFEBEBEB.toInt()
    val selected = if (dark) 0xFF2E2E2E.toInt() else 0xFFF2F2F2.toInt()
    val text = if (dark) 0xFFFAFAFA.toInt() else 0xFF161616.toInt()
    val muted = if (dark) 0xFFAEAEAE.toInt() else 0xFF5C5C5C.toInt()
    val faint = if (dark) 0xFF6F6F6F.toInt() else 0xFF9A9A9A.toInt()
    val switchOff = if (dark) 0xFF6B6B6B.toInt() else 0xFFDEDEDE.toInt()
    val knob = 0xFFFAFAFA.toInt()
    val accent = 0xFF4464F6.toInt()
    val success = 0xFF30A46C.toInt()
    val danger = 0xFFFF3B30.toInt()
    /** The kit's contrast button: light on dark, dark on light. */
    val contrast = text
    val onContrast = if (dark) 0xFF080808.toInt() else 0xFFFAFAFA.toInt()

    companion object {
        fun of(context: Context) = Tokens(
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        )
    }
}

class Ui(val context: Context) {
    val t = Tokens.of(context)
    private val density = context.resources.displayMetrics.density
    val regular: Typeface = context.resources.getFont(R.font.inter_regular)
    val medium: Typeface = context.resources.getFont(R.font.inter_medium)
    val semibold: Typeface = context.resources.getFont(R.font.inter_semibold)
    val mono: Typeface = context.resources.getFont(R.font.plex_mono)

    fun px(dp: Float) = (dp * density).toInt()
    fun px(dp: Int) = px(dp.toFloat())

    /** The folded cover screen: rows put wide controls under their text. */
    val narrow = context.resources.configuration.screenWidthDp < 520

    fun text(value: CharSequence, size: Float = 15f, face: Typeface = regular, color: Int = t.text) = TextView(context).apply {
        text = value
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }

    fun title(value: CharSequence) = text(value, 15f, medium)
    fun description(value: CharSequence) = text(value, 13f, regular, t.muted)

    fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius * density
        if (stroke != null) setStroke(px(1).coerceAtLeast(1), stroke)
    }

    /** A pressable background that never paints a hover fill on a settings row, only on the control. */
    fun pressable(fill: Int, radius: Float, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(t.selected), rounded(fill, radius, stroke), rounded(Color.WHITE, radius))

    fun icon(resource: Int, size: Int = 20, tint: Int = t.text) = ImageView(context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(px(size), px(size))
    }

    fun vertical(block: LinearLayout.() -> Unit = {}) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        block()
    }

    fun horizontal(block: LinearLayout.() -> Unit = {}) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

    fun space(dp: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, px(dp)) }

    /** A section label over a card, with an optional caption on the right ("Runs top to bottom"). */
    fun section(label: String, caption: String? = null) = horizontal {
        setPadding(px(4), px(24), px(4), px(8))
        addView(text(label, 15f, medium, t.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (caption != null) addView(text(caption, 13f, regular, t.faint))
    }

    /** Hex's settings card: rows on a raised surface with a hairline border, divided by hairlines. */
    inner class Card : LinearLayout(context) {
        init {
            orientation = VERTICAL
            background = rounded(t.surface, 10f, t.border)
            clipToOutline = true
        }

        fun row(view: View): View {
            if (childCount > 0) {
                addView(View(context).apply { setBackgroundColor(t.divider) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = px(16)
                })
            }
            addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return view
        }
    }

    fun card() = Card()

    /**
     * A settings row: leading icon (or a numbered step), title, optional
     * description, and one trailing control. [onPress] makes the whole row
     * one target, as `pressable_row` does.
     */
    fun row(
        title: String,
        description: String? = null,
        icon: Int? = null,
        step: Int? = null,
        trailing: View? = null,
        /** A wide control (segmented) that goes under the text on a narrow screen. */
        below: View? = null,
        onPress: (() -> Unit)? = null,
    ): Row = Row(title, description, icon, step, trailing, below, onPress)

    inner class Row(
        title: String,
        description: String?,
        icon: Int?,
        step: Int?,
        trailing: View?,
        below: View?,
        onPress: (() -> Unit)?,
    ) : LinearLayout(context) {
        val titleView: TextView = title(title)
        val descriptionView: TextView = description(description.orEmpty())

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(52)
            setPadding(px(16), px(11), px(14), px(11))
            if (icon != null) {
                addView(icon(icon).apply { alpha = 0.92f })
                addView(View(context), LayoutParams(px(14), 1))
            } else if (step != null) {
                addView(stepBadge(step))
                addView(View(context), LayoutParams(px(14), 1))
            }
            val texts = vertical {
                addView(titleView)
                if (description != null) {
                    addView(space(3))
                    addView(descriptionView)
                }
                if (below != null && narrow) {
                    addView(space(10))
                    // Under the text a segmented control spans the row, its options sharing it equally.
                    if (below is LinearLayout) {
                        for (i in 0 until below.childCount) {
                            val child = below.getChildAt(i)
                            child.layoutParams = LayoutParams(0, child.layoutParams?.height?.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                    }
                    val width = if (below is LinearLayout) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
                    addView(below, LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
            addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val side = trailing ?: below?.takeIf { !narrow }
            if (side != null) {
                addView(View(context), LayoutParams(px(12), 1))
                addView(side)
            }
            if (onPress != null) {
                background = RippleDrawable(ColorStateList.valueOf(t.selected), null, rounded(Color.WHITE, 0f))
                setOnClickListener { onPress() }
            }
        }

        fun describe(value: String?) {
            descriptionView.text = value.orEmpty()
            descriptionView.visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
        }
    }

    private fun stepBadge(n: Int) = text(n.toString(), 12f, medium, t.muted).apply {
        gravity = Gravity.CENTER
        background = rounded(t.selected, 12f)
        layoutParams = LinearLayout.LayoutParams(px(24), px(24))
    }

    /** A value whose choices open in a sheet: the value in semibold and a chevron. */
    fun sheetValue(value: String) = horizontal {
        addView(text(value, 15f, semibold).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = px(if (narrow) 120 else 220)
        })
        addView(View(context), LinearLayout.LayoutParams(px(4), 1))
        addView(icon(R.drawable.hex_chevron, 16, t.muted))
    }

    fun button(label: String, primary: Boolean = false, small: Boolean = true, onClick: () -> Unit) = text(label, if (small) 14f else 15f, medium, if (primary) t.onContrast else t.text).apply {
        gravity = Gravity.CENTER
        setPadding(px(if (small) 12 else 18), px(if (small) 7 else 11), px(if (small) 12 else 18), px(if (small) 7 else 11))
        background = if (primary) pressable(t.contrast, 7f) else pressable(t.raised, 7f, t.border)
        setOnClickListener { onClick() }
    }

    fun badge(label: String, color: Int = t.muted) = text(label, 12f, medium, color).apply {
        setPadding(px(7), px(3), px(7), px(3))
        background = rounded(t.selected, 5f)
    }

    fun statusDot(color: Int) = View(context).apply {
        background = rounded(color, 4f)
        layoutParams = LinearLayout.LayoutParams(px(8), px(8))
    }

    /** A few exclusive choices, as the kit's segmented control. */
    fun segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): LinearLayout {
        val group = horizontal {
            background = rounded(t.selected, 8f)
            setPadding(px(2), px(2), px(2), px(2))
        }
        fun paint(value: String) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) as TextView
                val on = child.tag == value
                child.background = if (on) rounded(t.surface, 6f, t.border) else null
                child.setTextColor(if (on) t.text else t.muted)
            }
        }
        options.forEach { (value, label) ->
            group.addView(text(label, 13f, medium, t.muted).apply {
                tag = value
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(px(10), px(6), px(10), px(6))
                setOnClickListener {
                    paint(value)
                    onSelect(value)
                }
            })
        }
        paint(selected)
        return group
    }

    /** A segmented control of icons, for choices that are easier seen than named. */
    fun iconSegmented(options: List<Triple<String, Int, String>>, selected: String, onSelect: (String) -> Unit): LinearLayout {
        val group = horizontal {
            background = rounded(t.selected, 8f)
            setPadding(px(2), px(2), px(2), px(2))
        }
        fun paint(value: String) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) as ImageView
                val on = child.tag == value
                child.background = if (on) rounded(t.surface, 6f, t.border) else null
                child.imageTintList = ColorStateList.valueOf(if (on) t.text else t.muted)
            }
        }
        options.forEach { (value, icon, label) ->
            group.addView(ImageView(context).apply {
                setImageResource(icon)
                tag = value
                contentDescription = label
                setPadding(px(7), px(6), px(7), px(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34))
                setOnClickListener {
                    paint(value)
                    onSelect(value)
                }
            })
        }
        paint(selected)
        return group
    }

    fun field(hint: String, value: String = "", mono: Boolean = false) = EditText(context).apply {
        setText(value)
        this.hint = hint
        typeface = if (mono) this@Ui.mono else regular
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(t.text)
        setHintTextColor(t.faint)
        background = rounded(t.window, 7f, t.border)
        setPadding(px(12), px(10), px(12), px(10))
        includeFontPadding = false
    }

    fun switch(on: Boolean, onChange: (Boolean) -> Unit) = HexSwitch(context, t, on, onChange)
}

/** The kit's switch as Hex draws it: a pill track, grey off and blue on, with a light knob. */
class HexSwitch(
    context: Context,
    private val t: Tokens,
    on: Boolean,
    private val onChange: (Boolean) -> Unit,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private var position = if (on) 1f else 0f
    var checked = on
        private set

    init {
        layoutParams = ViewGroup.LayoutParams((38 * density).toInt(), (22 * density).toInt())
        isClickable = true
        setOnClickListener { set(!checked, animate = true, notify = true) }
        contentDescription = "Switch"
    }

    fun set(value: Boolean, animate: Boolean = false, notify: Boolean = false) {
        checked = value
        if (animate) {
            ValueAnimator.ofFloat(position, if (value) 1f else 0f).apply {
                duration = 140
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            position = if (value) 1f else 0f
            invalidate()
        }
        if (notify) onChange(value)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        track.set(0f, 0f, w, h)
        paint.color = blend(t.switchOff, t.accent, position)
        canvas.drawRoundRect(track, h / 2, h / 2, paint)
        val inset = 2.5f * density
        val radius = h / 2 - inset
        val x = inset + radius + (w - 2 * (inset + radius)) * position
        paint.color = t.knob
        canvas.drawCircle(x, h / 2, radius, paint)
    }

    private fun blend(a: Int, b: Int, f: Float): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )
}

package app.hexforandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The bubble's outline. Pill is desktop Hex's HUD; the others suit a phone's edge. */
enum class BubbleShape(val label: String, val icon: Int) {
    Circle("Circle", R.drawable.hex_shape_circle),
    Square("Square", R.drawable.hex_shape_square),
    Hexagon("Hexagon", R.drawable.hex_shape_hexagon),
    Pill("Pill", R.drawable.hex_shape_pill);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Circle
    }
}

/** Bubble sizes in dp: the round shapes' diameter, and the pill's height. */
enum class BubbleSize(val label: String, val round: Float, val pill: Float) {
    Small("S", 40f, 28f), Medium("M", 52f, 34f), Large("L", 68f, 42f);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Medium
    }
}

/**
 * Dot scope, desktop Hex's dictation HUD (`src/rune_hud/dot_scope.rs`): a
 * plate of dots lit by a braided pair of traces whose amplitude follows the
 * voice and travels out from the middle. Warm pearl while recording, agent
 * blue while transcribing, coral on failure, with the Tuning defaults for the
 * spring, release, travel speed and processing pass.
 *
 * The plate takes the bubble's [shape]; dots outside it stay dark. A pill
 * widens while recording and narrows while transcribing, as the desktop HUD
 * does; the round shapes keep their size.
 */
class DotScope(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density

    var shape = BubbleShape.Circle
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    var size = BubbleSize.Medium
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    /** Scales the whole bubble, dots included, while a press holds it. */
    var magnify = 1f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** The pill's current width in dp; [Bubble] animates it. */
    var pillWidth = REST_WIDTH
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** 0..1: how far the recording rim has come in. */
    private var live = 0f
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plate = RectF()
    private val outline = Path()

    // Dot scope's palette (rune_hud/dot_scope.rs `palette`), set by [applyTheme].
    private var you = FloatArray(3)
    private var youCore = FloatArray(3)
    private var agent = FloatArray(3)
    private var agentCore = FloatArray(3)
    private var coral = FloatArray(3)

    init {
        applyTheme(Tokens.of(context).dark)
    }

    /**
     * Follows the system's light or dark theme. The rim is stronger than the
     * desktop HUD's so the bubble stays visible on a black or white app.
     */
    fun applyTheme(dark: Boolean) {
        platePaint.color = if (dark) Color.argb(242, 22, 22, 26) else Color.argb(246, 252, 251, 249)
        platePaint.setShadowLayer(4 * density, 0f, 1.5f * density, if (dark) Color.argb(140, 0, 0, 0) else Color.argb(56, 20, 24, 40))
        rimPaint.strokeWidth = 3f * density // half of it shows, inside the clip
        rimPaint.color = if (dark) Color.argb(72, 255, 255, 255) else Color.argb(40, 0, 0, 0)
        you = if (dark) floatArrayOf(0.86f, 0.83f, 0.77f) else floatArrayOf(0.62f, 0.53f, 0.40f)
        youCore = if (dark) floatArrayOf(0.98f, 0.96f, 0.92f) else floatArrayOf(0.47f, 0.38f, 0.26f)
        agent = if (dark) floatArrayOf(0.70f, 0.78f, 0.90f) else floatArrayOf(0.40f, 0.47f, 0.58f)
        agentCore = if (dark) floatArrayOf(0.92f, 0.95f, 1.0f) else floatArrayOf(0.24f, 0.31f, 0.43f)
        coral = if (dark) floatArrayOf(1.0f, 0.38f, 0.31f) else floatArrayOf(0.9f, 0.27f, 0.21f)
        invalidate()
    }

    private var phase = Dictation.Phase.Off
    private var rawLevel = 0f
    private var level = 0f
    private var velocity = 0f
    private var peak = 0.35f
    private var braid = 0f
    private var cool = 0f
    private var fail = 0f
    private var lastFrame = 0L
    private var processingSince = 0L
    /** Keep animating until the afterglow and colours settle after a change. */
    private var settleUntil = 0L
    private val history = FloatArray(HISTORY)
    private var historyHead = 0
    private var dots = FloatArray(0)

    /** The plate's size in dp for the current shape, size and pill width. */
    fun plateDp(): Pair<Float, Float> = when (shape) {
        BubbleShape.Pill -> pillWidth * size.pill / BubbleSize.Medium.pill * magnify to size.pill * magnify
        else -> size.round * magnify to size.round * magnify
    }

    /** The view's size in px: the plate plus room for its shadow. */
    fun viewPx(): Pair<Int, Int> {
        val (w, h) = plateDp()
        return ((w + 2 * PAD) * density).toInt() to ((h + 2 * PAD) * density).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = viewPx()
        setMeasuredDimension(w, h)
    }

    /** A preview in settings: show Hex's ready look even while Hex is off. */
    var showsReadyWhenOff = false

    fun update(state: Dictation.State) {
        val previous = phase
        phase = if (showsReadyWhenOff && state.phase == Dictation.Phase.Off) Dictation.Phase.Ready else state.phase
        rawLevel = state.level
        val now = SystemClock.uptimeMillis()
        if (phase == Dictation.Phase.Transcribing && previous != phase) processingSince = now
        if (state.message != null && previous == Dictation.Phase.Transcribing) fail = 1f
        if (previous != phase) settleUntil = now + 1500
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1 / 60f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        step(dt)

        val pad = PAD * density
        plate.set(pad, pad, width - pad, height - pad)
        buildOutline()
        canvas.drawPath(outline, platePaint)
        canvas.save()
        canvas.clipPath(outline)
        canvas.drawPath(outline, rimPaint)
        if (live > 0.01f) {
            // Recording: a coral rim that breathes, so a tap shows it took even in silence.
            val pulse = 0.75f + 0.25f * sin(now / 1000f * 2 * PI.toFloat() * 1.1f)
            livePaint.strokeWidth = 5f * density * magnify
            livePaint.color = Color.argb((255 * live * pulse).toInt(), (coral[0] * 255).toInt(), (coral[1] * 255).toInt(), (coral[2] * 255).toInt())
            canvas.drawPath(outline, livePaint)
        }
        canvas.restore()

        // A fixed dot pitch, as on the desktop: a bigger bubble shows more dots,
        // not bigger ones. Magnifying while held scales the dots too.
        val pitch = PITCH * density * magnify
        val inset = pitch * 1.1f
        // The field spans the plate's inner box; rows are odd so a trace has a middle row.
        val innerW = plate.width() - 2 * (if (shape == BubbleShape.Pill) plate.height() * 0.35f else inset)
        val innerH = plate.height() - 2 * inset
        val columns = max(3, (innerW / pitch).toInt() + 1)
        val rows = ((innerH / pitch).toInt() + 1).let { if (it % 2 == 0) it - 1 else it }.coerceAtLeast(3)
        if (dots.size != columns * rows) dots = FloatArray(columns * rows)
        val x0 = plate.centerX() - (columns - 1) * pitch / 2
        val y0 = plate.centerY() - (rows - 1) * pitch / 2
        val decay = exp(-dt / PHOSPHOR)
        val processing = phase == Dictation.Phase.Transcribing || phase == Dictation.Phase.Loading
        val beamAt = if (processing) ((now - processingSince) / 1000f / SHIMMER_PASS) % 1f else -1f
        val dotRadius = DOT * density * magnify
        val processingAmplitude = if (processing) 0.32f else 0f
        val travel = plate.width() / 2 / density
        // The trace's width in rows stays the same whatever the bubble's size.
        val sigma = TRACE_ROWS * 2f / (rows - 1)

        for (c in 0 until columns) {
            val u = (c / (columns - 1f)) * 2 - 1 // -1..1
            // The voice travels out from the middle at history_speed.
            val amplitude = max(sampleHistory(abs(u) * travel / HISTORY_SPEED) * 0.82f, processingAmplitude)
            val a = amplitude * sin(PI.toFloat() * HELIX * u + braid)
            val flow = if (beamAt >= 0) exp(-((u + 1) / 2 - beamAt).let { it * it } / (2 * 0.1f * 0.1f)) else 0f
            for (r in 0 until rows) {
                val x = x0 + c * pitch
                val y = y0 + r * pitch
                if (!inside(x, y, inset * 0.55f)) continue
                val v = (r / (rows - 1f)) * 2 - 1 // -1..1
                val l1 = exp(-((v - a) * (v - a)) / (2 * sigma * sigma))
                val l2 = exp(-((v + a) * (v + a)) / (2 * sigma * sigma))
                var power = 1 - (1 - l1) * (1 - l2)
                if (processing) power = min(1f, power * (0.3f + 0.9f * flow))
                val n = c * rows + r
                dots[n] = max(power, dots[n] * decay)
                drawDot(canvas, x, y, dots[n], dotRadius)
            }
        }
        val busy = phase == Dictation.Phase.Recording || processing
        if (busy || fail > 0.01f || level > 0.01f || live > 0.01f || now < settleUntil) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun buildOutline() {
        outline.reset()
        val cx = plate.centerX()
        val cy = plate.centerY()
        when (shape) {
            BubbleShape.Pill -> outline.addRoundRect(plate, plate.height() / 2, plate.height() / 2, Path.Direction.CW)
            BubbleShape.Circle -> outline.addCircle(cx, cy, plate.width() / 2, Path.Direction.CW)
            BubbleShape.Square -> plate.width().let { outline.addRoundRect(plate, it * 0.26f, it * 0.26f, Path.Direction.CW) }
            BubbleShape.Hexagon -> {
                // Pointy-top, like Hex's mark, with softened corners.
                val r = plate.height() / 2
                for (i in 0 until 6) {
                    val angle = (-90.0 + 60.0 * i) * PI / 180
                    val x = cx + r * cos(angle).toFloat()
                    val y = cy + r * sin(angle).toFloat()
                    if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
                }
                outline.close()
                platePaint.pathEffect = android.graphics.CornerPathEffect(r * 0.22f)
                rimPaint.pathEffect = platePaint.pathEffect
                return
            }
        }
        platePaint.pathEffect = null
        rimPaint.pathEffect = null
    }

    /** Whether a dot at (x, y) sits inside the plate, [margin] in from its edge. */
    private fun inside(x: Float, y: Float, margin: Float): Boolean {
        val dx = x - plate.centerX()
        val dy = y - plate.centerY()
        return when (shape) {
            BubbleShape.Pill, BubbleShape.Square -> true
            BubbleShape.Circle -> hypot(dx, dy) <= plate.width() / 2 - margin
            BubbleShape.Hexagon -> {
                val r = plate.height() / 2 - margin * 1.3f
                abs(dx) <= r * sqrt(3f) / 2 && abs(dy) + abs(dx) / sqrt(3f) <= r
            }
        }
    }

    /** Maps RMS to 0..1 against the speaker's recent peak (auto level), above a −60 dB floor. */
    private fun normalized(rms: Float, dt: Float): Float {
        val db = 20 * log10(max(rms, 1e-6f))
        val above = ((db + 60f) / 60f).coerceIn(0f, 1f)
        peak = max(above, peak * exp(-dt / 3.0f)).coerceAtLeast(0.35f)
        return (above / peak).coerceIn(0f, 1f).let { it * it }
    }

    private fun step(dt: Float) {
        val target = if (phase == Dictation.Phase.Recording) normalized(rawLevel, dt) else 0f
        // The amplitude spring (response 6.5 Hz), with a 0.15 s release.
        if (target >= level) {
            val w = 2 * PI.toFloat() * RESPONSE
            velocity += (w * w * (target - level) - 2 * w * velocity) * dt
            level += velocity * dt
        } else {
            velocity = 0f
            level += (target - level) * (1 - exp(-dt / RELEASE))
        }
        level = level.coerceIn(0f, 1f)
        history[historyHead] = level
        historyHead = (historyHead + 1) % HISTORY
        val speed = when (phase) {
            Dictation.Phase.Recording -> 1f
            Dictation.Phase.Transcribing, Dictation.Phase.Loading -> 0.7f
            else -> 0f
        }
        braid += dt * speed * 2 * PI.toFloat() * 0.45f
        val coolTarget = if (phase == Dictation.Phase.Transcribing || phase == Dictation.Phase.Loading) 1f else 0f
        cool += (coolTarget - cool) * (1 - exp(-dt / 0.25f))
        fail *= exp(-dt / 0.6f)
        val liveTarget = if (phase == Dictation.Phase.Recording) 1f else 0f
        live += (liveTarget - live) * (1 - exp(-dt / 0.12f))
    }

    /** The level [age] seconds ago, one sample per frame (about 60 Hz). */
    private fun sampleHistory(age: Float): Float {
        val back = (age * 60f).toInt().coerceIn(0, HISTORY - 1)
        return history[(historyHead - 1 - back + 2 * HISTORY) % HISTORY]
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, power: Float, radius: Float) {
        val dim = when (phase) {
            Dictation.Phase.Off -> 0.35f
            Dictation.Phase.Ready -> 0.7f
            else -> 1f
        }
        val p = power * dim
        val alpha = ((0.07f + 0.93f * p).coerceIn(0f, 1f) * 255).toInt()
        dot.color = Color.argb(alpha, channel(0, p), channel(1, p), channel(2, p))
        canvas.drawCircle(x, y, radius * (0.8f + 0.4f * p), dot)
    }

    /** One colour channel, 0..255: warm pearl cooling to agent blue, coral on failure, brighter toward the core. */
    private fun channel(i: Int, p: Float): Int {
        val base = mix(mix(you[i], agent[i], cool), coral[i], fail)
        val core = mix(mix(youCore[i], agentCore[i], cool), coral[i], fail)
        return (mix(base, core, p) * 255).toInt()
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun performClick(): Boolean = super.performClick()

    companion object {
        // The pill's widths in dp at Medium: desktop Hex's 112 pt recording and
        // 76 pt processing widths, scaled for a finger.
        const val REST_WIDTH = 58f
        const val RECORDING_WIDTH = 136f
        const val PROCESSING_WIDTH = 96f
        /** Room around the plate for its shadow, dp. */
        const val PAD = 6f
        private const val PITCH = 3.1f
        private const val DOT = 0.9f

        // Dot scope's Tuning defaults (rune_hud/dot_scope.rs).
        private const val HELIX = 1.6f
        private const val RESPONSE = 6.5f
        private const val RELEASE = 0.15f
        private const val HISTORY_SPEED = 150f
        // Desktop's 0.6 s phosphor suits its denser 43×9 field; on this one it
        // smears the braid into a block, so the tail is shorter here.
        private const val PHOSPHOR = 0.16f
        private const val SHIMMER_PASS = 0.55f
        /** A trace's half-width, in dot rows. */
        private const val TRACE_ROWS = 0.48f
        private const val HISTORY = 120
    }
}

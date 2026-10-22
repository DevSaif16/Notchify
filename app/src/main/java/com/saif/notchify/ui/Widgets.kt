package com.saif.notchify.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.saif.notchify.Geometry
import com.saif.notchify.Island
import com.saif.notchify.Palette
import com.saif.notchify.R
import com.saif.notchify.Trailing
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Damped spring with unit mass, integrated in fixed sub-steps so it stays stable at any refresh rate. */
class Spring(start: Float, private val rest: Float) {
    var value = start
    var target = start
    var velocity = 0f
    private var stiffness = 300f
    private var damping = 2f * 0.8f * sqrt(300f)

    fun tune(stiffness: Float, dampingRatio: Float) {
        this.stiffness = stiffness
        damping = 2f * dampingRatio * sqrt(stiffness)
    }

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    /** Advances [dt] seconds; true while still moving. */
    fun step(dt: Float): Boolean {
        if (value == target && velocity == 0f) return false
        var left = dt
        while (left > 0f) {
            val h = min(left, 1f / 240f)
            velocity += (-stiffness * (value - target) - damping * velocity) * h
            value += velocity * h
            left -= h
        }
        if (abs(value - target) < rest && abs(velocity) < rest * 20f) {
            snap(target)
            return false
        }
        return true
    }
}

interface Bindable {
    fun bind(island: Island, geo: Geometry)
}

internal fun View.dp(v: Float): Float = v * resources.displayMetrics.density
internal fun View.dpi(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
internal fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(max(0, px), View.MeasureSpec.EXACTLY)
internal fun atMost(px: Int) = View.MeasureSpec.makeMeasureSpec(max(0, px), View.MeasureSpec.AT_MOST)
internal val UNSPECIFIED = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
internal fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

private val OVAL = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
}
private val SQUIRCLE = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) =
        outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) * 0.24f)
}

/** Shows [d] in its own drawable instance (one drawable can't serve two views of different sizes). */
internal fun ImageView.show(d: Drawable?, shape: Island.Shape, tint: Int?) {
    setImageDrawable(d?.constantState?.newDrawable(resources)?.mutate() ?: d)
    imageTintList = tint?.let { ColorStateList.valueOf(it) }
    scaleType = if (shape == Island.Shape.NONE) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
    outlineProvider = when (shape) {
        Island.Shape.CIRCLE -> OVAL
        Island.Shape.ROUNDED -> SQUIRCLE
        Island.Shape.NONE -> ViewOutlineProvider.BACKGROUND
    }
    clipToOutline = shape != Island.Shape.NONE
}

internal fun TextView.style(sp: Float, color: Int, weight: Int = 400) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    includeFontPadding = false
}

/** A quick pop, used when something on the island starts meaning something new. */
internal fun View.pop() {
    animate().cancel()
    scaleX = 0.55f
    scaleY = 0.55f
    alpha = 0f
    animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(OvershootInterpolator(2.2f)).start()
}

/** Bouncing bars. Redraws at ~24 fps, and only while visible and playing. */
class EqualizerView(context: Context, private val bars: Int = 4) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val level = FloatArray(bars) { 0.2f }
    private var playing = false
    private var shown = false
    private var color = Palette.WHITE

    fun set(playing: Boolean, color: Int) {
        this.playing = playing
        if (this.color != color) {
            this.color = color
            paint.shader = null
        }
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (paint.shader == null && height > 0) {
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), color, withAlpha(color, 0.55f), Shader.TileMode.CLAMP)
        }
        val t = SystemClock.uptimeMillis() / 1000f
        val bar = width / (bars * 2f - 1f)
        var settling = false
        for (i in 0 until bars) {
            val goal = when {
                playing && animated -> 0.22f + 0.78f * abs(sin(t * SPEED[i % SPEED.size] + i * 1.4f) * (0.6f + 0.4f * sin(t * 1.7f + i)))
                playing -> STATIC[i % STATIC.size]
                else -> 0.15f
            }
            level[i] += (goal - level[i]) * 0.5f
            if (abs(goal - level[i]) > 0.01f) settling = true
            val h = max(bar, height * level[i])
            val x = i * bar * 2f
            val top = (height - h) / 2f
            canvas.drawRoundRect(x, top, x + bar, top + h, bar / 2f, bar / 2f, paint)
        }
        if (shown && ((playing && animated) || settling)) postInvalidateDelayed(FRAME_MS)
    }

    companion object {
        private const val FRAME_MS = 42L
        private val SPEED = floatArrayOf(5.3f, 7.9f, 6.4f, 9.1f, 4.7f, 8.3f)
        private val STATIC = floatArrayOf(0.5f, 0.9f, 0.65f, 0.4f, 0.75f, 0.55f)

        /** Mirrors the "Animated equaliser" preference. */
        @JvmStatic
        var animated = true
    }
}

class RingView(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
    private val oval = RectF()
    private var drawn = 0f
    private var animator: ValueAnimator? = null

    /** Text drawn in the middle (e.g. "Now" at a turn). */
    var center: String? = null
        set(v) {
            field = v
            invalidate()
        }

    fun set(fraction: Float, color: Int) {
        track.color = withAlpha(color, 0.25f)
        arc.color = color
        label.color = color
        val goal = fraction.coerceIn(0f, 1f)
        if (goal == drawn) return invalidate()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(drawn, goal).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = width * 0.13f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        oval.set(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f)
        canvas.drawArc(oval, 0f, 360f, false, track)
        canvas.drawArc(oval, -90f, 360f * drawn, false, arc)
        center?.let {
            label.textSize = width * 0.26f
            canvas.drawText(it, width / 2f, height / 2f - (label.ascent() + label.descent()) / 2f, label)
        }
    }
}

/** Battery outline whose fill sweeps up to the level, with a bolt. */
class BatteryView(context: Context) : View(context) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = 0f
    private var animator: ValueAnimator? = null

    fun set(level: Int, color: Int) {
        outline.color = withAlpha(color, 0.6f)
        fill.color = color
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, level / 100f).apply {
            duration = 900
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = height * 0.1f
        outline.strokeWidth = stroke
        val nub = width * 0.08f
        val r = height * 0.22f
        rect.set(stroke / 2f, stroke / 2f, width - nub - stroke / 2f, height - stroke / 2f)
        canvas.drawRoundRect(rect, r, r, outline)
        canvas.drawRoundRect(width - nub, height * 0.32f, width.toFloat(), height * 0.68f, nub / 2f, nub / 2f, outline)
        val inset = stroke * 1.6f
        val innerW = rect.width() - 2 * inset
        rect.set(rect.left + inset, rect.top + inset, rect.left + inset + innerW * shown, rect.bottom - inset)
        canvas.drawRoundRect(rect, r * 0.6f, r * 0.6f, fill)
    }
}

/**
 * The torch: glyph, halo and light rays that burst out when it is switched on. In [beam] mode
 * (expanded) a cone of light rises from it, as bright as the current strength.
 */
class TorchView(context: Context) : View(context) {
    private val glyph = context.getDrawable(R.drawable.ic_flashlight)!!.mutate().apply { setTint(Palette.YELLOW) }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.YELLOW
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cone = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var burst = 1f
    private var animator: ValueAnimator? = null

    var beam = false
    var intensity = 1f
        set(v) {
            if (field == v) return
            val from = field
            field = v
            ValueAnimator.ofFloat(from, v).apply {
                duration = 300
                addUpdateListener {
                    drawnIntensity = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    private var drawnIntensity = 1f

    fun ignite() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                burst = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = if (beam) min(w, h) * 0.42f else min(w, h) * 0.66f
        val gx = (w - s) / 2f
        val gy = if (beam) h - s - h * 0.04f else (h - s) / 2f
        val headX = w / 2f
        val headY = gy + s * 0.12f

        if (beam) {
            path.reset()
            path.moveTo(headX - s * 0.3f, headY)
            path.lineTo(headX + s * 0.3f, headY)
            path.lineTo(w * 0.98f, 0f)
            path.lineTo(w * 0.02f, 0f)
            path.close()
            cone.shader = LinearGradient(0f, headY, 0f, 0f, withAlpha(Palette.YELLOW, 0.55f * drawnIntensity), 0, Shader.TileMode.CLAMP)
            canvas.drawPath(path, cone)
        }

        val haloR = s * (0.55f + 0.7f * burst)
        val haloA = 0.45f * (1f - burst) + 0.18f * drawnIntensity
        halo.shader = RadialGradient(headX, headY, haloR, withAlpha(Palette.YELLOW, haloA), 0, Shader.TileMode.CLAMP)
        canvas.drawCircle(headX, headY, haloR, halo)

        if (burst < 1f) {
            val reach = s * (0.25f + 0.55f * burst)
            rayPaint.strokeWidth = s * 0.07f
            rayPaint.alpha = ((1f - burst) * 255).roundToInt()
            for (i in 0 until 5) {
                val a = Math.toRadians(-90.0 + (i - 2) * 30.0)
                val r0 = s * 0.42f
                canvas.drawLine(
                    headX + (cos(a) * r0).toFloat(), headY + (sin(a) * r0).toFloat(),
                    headX + (cos(a) * (r0 + reach)).toFloat(), headY + (sin(a) * (r0 + reach)).toFloat(), rayPaint,
                )
            }
        }

        glyph.setBounds(gx.roundToInt(), gy.roundToInt(), (gx + s).roundToInt(), (gy + s).roundToInt())
        glyph.draw(canvas)
    }
}

/** A step tracker for deliveries and rides: dots joined by a line that fills up to the current step. */
class StagesView(context: Context) : View(context) {
    private var labels: List<String> = emptyList()
    private var current = 0
    private var drawn = 0f
    private var color = Palette.GREEN
    private var animator: ValueAnimator? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
    }

    fun set(labels: List<String>, current: Int, color: Int) {
        val changed = labels != this.labels || current != this.current
        this.labels = labels
        this.current = current
        this.color = color
        if (!changed) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(drawn, current.toFloat()).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                drawn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dpi(46f))
    }

    override fun onDraw(canvas: Canvas) {
        val n = labels.size
        if (n < 2) return
        val pad = dp(26f)
        val step = (width - 2 * pad) / (n - 1)
        val cy = dp(10f)
        line.strokeWidth = dp(3f)
        line.color = withAlpha(Palette.WHITE, 0.18f)
        canvas.drawLine(pad, cy, width - pad, cy, line)
        line.color = color
        canvas.drawLine(pad, cy, pad + step * drawn, cy, line)
        for (i in 0 until n) {
            val x = pad + step * i
            val done = i <= drawn + 0.01f
            dot.color = if (done) color else 0xFF3A3A3C.toInt()
            val r = if (i == current) dp(7f) else dp(5f)
            if (i == current) {
                dot.color = withAlpha(color, 0.3f)
                canvas.drawCircle(x, cy, dp(11f), dot)
                dot.color = color
            }
            canvas.drawCircle(x, cy, r, dot)
            text.color = if (i == current) Palette.WHITE else Palette.TEXT2
            text.typeface = Typeface.create(Typeface.DEFAULT, if (i == current) 600 else 400, false)
            canvas.drawText(labels[i], x.coerceIn(dp(22f), width - dp(22f)), height - dp(4f), text)
        }
    }
}

/** Android 16 ProgressStyle drawn as is: coloured segments, milestone points and a tracker icon. */
class SegmentBarView(context: Context) : View(context) {
    private var parts: List<Pair<Float, Int>> = emptyList()
    private var points: List<Pair<Float, Int>> = emptyList()
    private var tracker: Drawable? = null
    private var progress = 0f
    private var drawn = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(parts: List<Pair<Float, Int>>, points: List<Pair<Float, Int>>, progress: Float, tracker: Drawable?) {
        this.parts = parts
        this.points = points
        this.tracker = tracker?.constantState?.newDrawable(resources)?.mutate() ?: tracker
        if (progress == this.progress) return invalidate()
        this.progress = progress
        ValueAnimator.ofFloat(drawn, progress).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dpi(30f))
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(12f)
        val track = width - 2 * pad
        val cy = height / 2f
        val r = dp(3f)
        var x = pad
        val gap = dp(2f)
        for ((len, color) in parts.ifEmpty { listOf(1f to Palette.BLUE) }) {
            val end = x + track * len
            val c = if (color == 0) Palette.BLUE else color
            paint.color = withAlpha(c, 0.3f)
            canvas.drawRoundRect(x, cy - r, end - gap, cy + r, r, r, paint)
            val doneEnd = min(end - gap, pad + track * drawn)
            if (doneEnd > x) {
                paint.color = c
                canvas.drawRoundRect(x, cy - r, doneEnd, cy + r, r, r, paint)
            }
            x = end
        }
        for ((pos, color) in points) {
            val px = pad + track * pos
            paint.color = Color.BLACK
            canvas.drawCircle(px, cy, dp(6f), paint)
            paint.color = if (color == 0) Palette.WHITE else color
            canvas.drawCircle(px, cy, dp(4f), paint)
        }
        val tx = pad + track * drawn
        val t = tracker
        val ts = dp(22f)
        paint.color = Palette.WHITE
        canvas.drawCircle(tx, cy, ts / 2f + dp(2f), paint)
        if (t != null) {
            t.setTint(Color.BLACK)
            t.setBounds((tx - ts / 2.6f).roundToInt(), (cy - ts / 2.6f).roundToInt(), (tx + ts / 2.6f).roundToInt(), (cy + ts / 2.6f).roundToInt())
            t.draw(canvas)
        }
    }
}

/**
 * Static text (numbers in it roll to their new value, so a distance counts down rather than
 * flickering), or a chronometer that ticks once per second only while on screen.
 */
class ChronoText(context: Context) : TextView(context) {
    private var base = 0L
    private var countDown = false
    private var ticking = false
    private var shown = false
    private var roll: ValueAnimator? = null
    private val tick = Runnable { refresh() }

    init {
        fontFeatureSettings = "tnum"
        isSingleLine = true
    }

    fun static(value: CharSequence) {
        ticking = false
        removeCallbacks(tick)
        roll?.cancel()
        val old = NUMBER.matchEntire(text?.toString().orEmpty())
        val new = NUMBER.matchEntire(value.toString())
        if (shown && old != null && new != null && old.value != new.value &&
            old.groupValues[1] == new.groupValues[1] && old.groupValues[3] == new.groupValues[3]
        ) {
            val from = old.groupValues[2].toFloat()
            val to = new.groupValues[2].toFloat()
            val decimals = new.groupValues[2].substringAfter('.', "").length
            roll = ValueAnimator.ofFloat(from, to).apply {
                duration = 450
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val v = it.animatedValue as Float
                    text = new.groupValues[1] + "%.${decimals}f".format(v) + new.groupValues[3]
                }
                start()
            }
            return
        }
        text = value
    }

    fun chrono(base: Long, countDown: Boolean) {
        this.base = base
        this.countDown = countDown
        ticking = true
        refresh()
    }

    private fun refresh() {
        removeCallbacks(tick)
        if (!ticking) return
        val ms = if (countDown) base - System.currentTimeMillis() else System.currentTimeMillis() - base
        text = format(if (countDown) -Math.floorDiv(-ms, 1000L) else Math.floorDiv(ms, 1000L))
        if (shown) postDelayed(tick, if (countDown) Math.floorMod(ms, 1000L) + 5 else 1000 - Math.floorMod(ms, 1000L) + 5)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (isVisible) refresh() else removeCallbacks(tick)
    }

    companion object {
        private val NUMBER = Regex("""^(\D*?)(\d+(?:\.\d+)?)(\D*)$""")

        fun format(seconds: Long): String {
            val s = abs(seconds)
            val sign = if (seconds < 0) "-" else ""
            return if (s >= 3600) "%s%d:%02d:%02d".format(sign, s / 3600, s / 60 % 60, s % 60)
            else "%s%d:%02d".format(sign, s / 60, s % 60)
        }
    }
}

/** Media position bar; thickens under the finger and seeks on release. */
class ScrubBar(context: Context) : View(context) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var interactive = false
    var dragging = false
        private set
    var onDrag: ((Float) -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null
    var color = Palette.WHITE
        set(v) {
            field = v
            fill.color = v
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3DFFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WHITE }
    private var thickness = dp(5f)
    private var thicknessAnim: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        val r = thickness / 2f
        val cy = height / 2f
        canvas.drawRoundRect(0f, cy - r, width.toFloat(), cy + r, r, r, track)
        canvas.drawRoundRect(0f, cy - r, max(thickness, width * fraction), cy + r, r, r, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                dragging = true
                grow(true)
                fraction = event.x / width
                onDrag?.invoke(fraction)
            }
            MotionEvent.ACTION_MOVE -> {
                fraction = event.x / width
                onDrag?.invoke(fraction)
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                grow(false)
                onSeek?.invoke(fraction)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                grow(false)
            }
        }
        return true
    }

    private fun grow(big: Boolean) {
        thicknessAnim?.cancel()
        thicknessAnim = ValueAnimator.ofFloat(thickness, dp(if (big) 10f else 5f)).apply {
            duration = 180
            addUpdateListener {
                thickness = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}

/** Hosts whichever [Trailing] widget an island asks for. */
class TrailingView(context: Context) : FrameLayout(context) {
    private val text = ChronoText(context)
    private val equalizer = EqualizerView(context)
    private val ring = RingView(context)
    private val glyph = ImageView(context)
    private val battery = BatteryView(context)

    init {
        for (v in listOf(text, equalizer, ring, glyph, battery)) {
            v.visibility = GONE
            addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    fun bind(trailing: Trailing, box: Int, big: Boolean) {
        for (i in 0 until childCount) getChildAt(i).visibility = GONE
        if (trailing !is Trailing.Text && trailing !is Trailing.Chrono) text.static("")
        when (trailing) {
            is Trailing.Text -> {
                textLook(trailing.color, big)
                text.static(trailing.text)
            }
            is Trailing.Chrono -> {
                textLook(trailing.color, big)
                text.chrono(trailing.base, trailing.countDown)
            }
            is Trailing.Equalizer -> {
                equalizer.set(trailing.playing, trailing.color)
                size(equalizer, if (big) dpi(34f) else (box * 0.62f).roundToInt(), if (big) dpi(30f) else (box * 0.5f).roundToInt())
            }
            is Trailing.Ring -> {
                ring.set(trailing.fraction, trailing.color)
                val s = if (big) dpi(44f) else (box * 0.72f).roundToInt()
                size(ring, s, s)
            }
            is Trailing.Glyph -> {
                glyph.show(trailing.drawable, Island.Shape.NONE, trailing.tint)
                val s = if (big) dpi(32f) else (box * 0.72f).roundToInt()
                size(glyph, s, s)
            }
            is Trailing.Battery -> {
                size(battery, if (big) dpi(44f) else (box * 0.95f).roundToInt(), if (big) dpi(22f) else (box * 0.48f).roundToInt())
                battery.set(trailing.level, trailing.color)
            }
            Trailing.None -> Unit
        }
    }

    private fun textLook(color: Int, big: Boolean) {
        if (big) text.style(30f, color, 500) else text.style(15f, color, 600)
        text.visibility = VISIBLE
    }

    private fun size(v: View, w: Int, h: Int) {
        val lp = v.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            v.layoutParams = lp
        }
        v.visibility = VISIBLE
    }
}

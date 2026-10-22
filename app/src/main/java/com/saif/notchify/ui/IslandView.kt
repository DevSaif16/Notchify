package com.saif.notchify.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.saif.notchify.Geometry
import com.saif.notchify.Island
import com.saif.notchify.IslandAction
import com.saif.notchify.Prefs
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The whole island in one small overlay window around the camera. The black shape is a view
 * whose bounds are driven every frame by three springs (width, height, and how far it reaches left
 * of the camera); content lives inside it, clipped by its outline, and crossfades (with a short
 * blur) between two instances per template. Nothing runs between animations.
 */
@SuppressLint("ViewConstructor")
class IslandView(
    context: Context,
    private val host: Host,
    geometry: Geometry,
    private val prefs: Prefs,
) : ViewGroup(context), Choreographer.FrameCallback {

    interface Host {
        /** Resize the overlay to [left] / [right] pixels either side of the camera and [height] from the top. */
        fun onWindow(left: Float, right: Float, height: Int, touchable: Boolean)
        fun onTap()
        fun onLongPress()
        fun onSwipe(down: Boolean)
        fun onOutside()
        fun onBubbleTap()
        fun onSelect(key: String)
        fun onAction(action: IslandAction)
        fun onInteraction()
    }

    enum class Style { IDLE, COMPACT, EXPANDED }

    var geo = geometry
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val shape = GradientDrawable().apply { setColor(Color.BLACK) }
    private val surface = Surface(context)
    private val bubble = Bubble(context)
    private val compacts = Array(2) { CompactView(context) }
    private val expandeds = Array(2) { ExpandedView(context, { host.onAction(it) }, { host.onSelect(it) }) }
    private val fades = HashMap<View, Animator>()
    private val calibration = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FF3B30.toInt() }
    private val location = IntArray(2)

    private val springW = Spring(geo.idleW, 0.5f)
    private val springH = Spring(geo.idleH, 0.5f)
    /** Distance from the camera centre to the shape's left edge. */
    private val springL = Spring(geo.idleW / 2f, 0.5f)
    private val press = Spring(1f, 0.001f)
    private val bubbleIn = Spring(0f, 0.002f)
    private var baseW = geo.idleW
    private var baseH = geo.idleH
    private var stretch = 0f
    private var dragging = false

    private var style = Style.IDLE
    private var active: View? = null
    private var activeKey: String? = null
    private var bubbleShown = false
    private var hidden = false

    private var animating = false
    private var lastFrame = 0L
    private var winLeft = 0f
    private var winRight = 0f
    private var winHeight = 0
    private var winTouchable = false

    /**
     * The window moves as it resizes; re-place the shape against wherever the frame being drawn is.
     * Only when it actually moved: re-applying unconditionally invalidates, which schedules another
     * frame, which runs this again (a 120 fps loop that burned a quarter of a core while idle).
     */
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (originX() != appliedOrigin) applyFrame()
        true
    }
    private var appliedOrigin = Float.NaN
    private var appliedRadius = -1f
    private var appliedW = -1
    private var appliedH = -1

    init {
        addView(surface)
        addView(bubble)
        for (v in listOf<View>(*compacts, *expandeds)) {
            v.visibility = GONE
            surface.addView(v)
        }
        bubble.setOnClickListener {
            host.onInteraction()
            host.onBubbleTap()
        }
        press.tune(700f, 0.6f)
        bubbleIn.tune(320f, 0.62f)
        springs(growing = true)
    }

    // ---- public API -------------------------------------------------------------------------

    fun show(newStyle: Style, island: Island?, secondary: Island?, others: List<Island> = emptyList()) {
        val current = active
        if (island != null && current != null && newStyle == style && island.key == activeKey) {
            bindTo(current, island, others)
        } else {
            val next: View? = when (newStyle) {
                Style.IDLE -> null
                Style.COMPACT -> spare(compacts)
                Style.EXPANDED -> spare(expandeds)
            }
            if (next != null && island != null) bindTo(next, island, others)
            val growing = newStyle.ordinal > style.ordinal || (newStyle == style && newStyle != Style.IDLE)
            current?.let(::fadeOut)
            next?.let(::fadeIn)
            style = newStyle
            active = next
            activeKey = island?.key
            springs(growing)
        }
        bubbleShown = newStyle == Style.COMPACT && secondary != null
        if (secondary != null && bubbleShown) bubble.bind(secondary)
        bubbleIn.target = if (bubbleShown) 1f else 0f
        retarget()
    }

    private fun bindTo(v: View, island: Island, others: List<Island>) {
        if (v is ExpandedView) v.bind(island, geo, others) else (v as Bindable).bind(island, geo)
    }

    fun setGeometry(g: Geometry) {
        geo = g
        requestLayout()
        retarget()
        invalidate()
    }

    fun setHidden(hide: Boolean) {
        if (hidden == hide) return
        hidden = hide
        visibility = if (hide) INVISIBLE else VISIBLE
        if (hide) setWindow(geo.idleW / 2f, geo.idleW / 2f, (geo.cy + geo.idleH / 2f).roundToInt()) else growWindow()
    }

    fun haptic() {
        if (!prefs.on(Prefs.HAPTICS)) return
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else HapticFeedbackConstants.CONTEXT_CLICK,
        )
    }

    // ---- targets & springs ------------------------------------------------------------------

    private fun <T : View> spare(pair: Array<T>): T {
        val v = if (pair[0] === active) pair[1] else pair[0]
        fades.remove(v)?.cancel()
        return v
    }

    /** Expanding is softer and bouncier; collapsing is quicker and better damped, like iOS. */
    private fun springs(growing: Boolean) {
        if (growing) {
            springW.tune(230f, 0.70f)
            springH.tune(200f, 0.76f)
            springL.tune(230f, 0.74f)
        } else {
            springW.tune(380f, 0.84f)
            springH.tune(420f, 0.88f)
            springL.tune(380f, 0.86f)
        }
    }

    private fun measureContent(v: View) {
        if (v is ExpandedView) {
            v.measure(exactly(geo.expandedW.roundToInt()), atMost((geo.screenH * 0.7f).roundToInt()))
        } else {
            v.measure(UNSPECIFIED, UNSPECIFIED)
        }
    }

    private fun retarget() {
        val a = active
        if (style == Style.IDLE || a == null) {
            baseW = geo.idleW
            baseH = geo.idleH
        } else {
            measureContent(a)
            baseW = a.measuredWidth.toFloat()
            baseH = a.measuredHeight.toFloat()
        }
        applyTargets()
    }

    private fun leftFor(w: Float) = if (style == Style.COMPACT && geo.rightWeighted) geo.capLeft else w / 2f

    /** Rubber-band the shape toward the finger while it is dragged. */
    private fun applyTargets() {
        val pull = rubber(abs(stretch)) * if (stretch < 0) -1f else 1f
        var w = baseW
        var h = baseH
        if (pull > 0 && style != Style.EXPANDED) {
            h += pull * 0.55f
            w += pull * 0.35f
        } else if (pull < 0 && style == Style.EXPANDED) {
            h += pull * 0.6f
            w += pull * 0.15f
        } else if (pull < 0) {
            w += pull * 0.2f
        }
        springW.target = w
        springH.target = h
        springL.target = leftFor(w)
        growWindow()
        kick()
    }

    private fun rubber(x: Float): Float {
        val limit = dp(44f)
        return limit * (1f - exp(-x / limit))
    }

    // ---- frame loop ---------------------------------------------------------------------------

    private fun kick() {
        if (animating) return
        animating = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrame == 0L) 1f / 120f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 1f / 30f)
        lastFrame = frameTimeNanos
        val moving = springW.step(dt) or springH.step(dt) or springL.step(dt) or press.step(dt) or bubbleIn.step(dt)
        applyFrame()
        if (moving) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            animating = false
            if (!dragging) setWindow(neededLeft(false), neededRight(false), neededHeight(false))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        Choreographer.getInstance().removeFrameCallback(this)
        animating = false
        super.onDetachedFromWindow()
    }

    /** The camera's x inside this window, whatever the window's current position. */
    private fun originX(): Float {
        getLocationOnScreen(location)
        return geo.cx - location[0]
    }

    private fun applyFrame() {
        if (getWidth() == 0) return
        val w = max(1f, springW.value)
        val h = max(1f, springH.value)
        val band = min(h, geo.compactH)
        val top = geo.cy - band / 2f
        val origin = originX()
        appliedOrigin = origin
        val left = origin - springL.value
        surface.setLeftTopRightBottom(left.roundToInt(), top.roundToInt(), (left + w).roundToInt(), (top + h).roundToInt())
        // GradientDrawable and the outline invalidate even when nothing changed; only touch them on change.
        val radius = min(h / 2f, geo.expandedR)
        val sizeChanged = surface.width != appliedW || surface.height != appliedH
        val radiusChanged = radius != appliedRadius
        if (radiusChanged) {
            shape.cornerRadius = radius
            appliedRadius = radius
        }
        if (sizeChanged || radiusChanged) {
            appliedW = surface.width
            appliedH = surface.height
            surface.invalidateOutline()
        }
        surface.pivotX = springL.value
        surface.pivotY = band / 2f
        surface.scaleX = press.value
        surface.scaleY = press.value

        val sw = surface.width.toFloat()
        val sh = min(surface.height.toFloat(), geo.compactH)
        for (i in 0 until surface.childCount) {
            val c = surface.getChildAt(i)
            if (c.visibility == GONE) continue
            val cw = c.measuredWidth.toFloat()
            val ch = min(c.measuredHeight.toFloat(), geo.compactH)
            val s = if (cw > 0f) (w / cw).coerceIn(0.7f, 1f) else 1f
            c.pivotX = cw / 2f
            c.pivotY = ch / 2f
            c.scaleX = s
            c.scaleY = s
            c.translationX = (sw - cw) / 2f
            c.translationY = (sh - ch) / 2f
        }

        val d = bubble.measuredWidth.toFloat()
        val b = bubbleIn.value
        bubble.translationX = left + w + dp(7f) - (1f - b) * dp(18f)
        bubble.translationY = geo.cy - d / 2f
        bubble.scaleX = max(0f, b)
        bubble.scaleY = max(0f, b)
        bubble.alpha = b.coerceIn(0f, 1f)
        bubble.visibility = if (b > 0.01f) VISIBLE else INVISIBLE
    }

    // ---- window -------------------------------------------------------------------------------

    /** Idle, the camera itself is the button that opens the control centre. */
    var idleTouchable = false

    private fun touchable() = !hidden && (style != Style.IDLE || idleTouchable)

    private fun growWindow() {
        if (hidden) return
        val l = neededLeft(true)
        val r = neededRight(true)
        val h = neededHeight(true)
        if (l > winLeft || r > winRight || h > winHeight || touchable() != winTouchable) {
            setWindow(max(l, winLeft), max(r, winRight), max(h, winHeight))
        }
    }

    private fun neededLeft(grow: Boolean): Float {
        val l = if (grow) max(springL.value, springL.target) * 1.1f + dp(6f) else springL.value + dp(2f)
        return min(l, geo.cx)
    }

    private fun neededRight(grow: Boolean): Float {
        var r = if (grow) {
            max(springW.value - springL.value, springW.target - springL.target) * 1.1f + dp(6f)
        } else {
            springW.value - springL.value + dp(2f)
        }
        if (bubbleShown || bubbleIn.value > 0.01f) {
            r = max(r, max(springW.value - springL.value, springW.target - springL.target) + dp(7f) + geo.compactH + dp(12f))
        }
        return min(r, geo.screenW - geo.cx)
    }

    private fun neededHeight(grow: Boolean): Int {
        val h = if (grow) max(springH.value, springH.target) * 1.1f + dp(12f) else springH.value + dp(4f)
        return (geo.cy - min(h, geo.compactH) / 2f + h).roundToInt()
    }

    private fun setWindow(left: Float, right: Float, h: Int) {
        val t = touchable()
        if (left == winLeft && right == winRight && h == winHeight && t == winTouchable) return
        winLeft = left
        winRight = right
        winHeight = h
        winTouchable = t
        host.onWindow(left, right, h, t)
    }

    // ---- content transitions ------------------------------------------------------------------

    private fun fadeIn(v: View) {
        fades.remove(v)?.cancel()
        v.visibility = VISIBLE
        v.alpha = 0f
        val blur = dp(10f)
        fades[v] = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            startDelay = 70
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val f = it.animatedValue as Float
                v.alpha = f
                v.blur((1f - f) * blur)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.alpha = 1f
                    v.blur(0f)
                    fades.remove(v)
                }
            })
            start()
        }
    }

    private fun fadeOut(v: View) {
        fades.remove(v)?.cancel()
        val blur = dp(10f)
        fades[v] = ValueAnimator.ofFloat(v.alpha, 0f).apply {
            duration = 150
            addUpdateListener {
                val f = it.animatedValue as Float
                v.alpha = f
                v.blur((1f - f) * blur)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.visibility = GONE
                    v.alpha = 1f
                    v.blur(0f)
                    fades.remove(v)
                }
            })
            start()
        }
    }

    private fun View.blur(radius: Float) {
        setRenderEffect(if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
    }

    // ---- layout -------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        for (i in 0 until surface.childCount) {
            val c = surface.getChildAt(i)
            if (c.visibility != GONE) measureContent(c)
        }
        surface.measure(exactly(springW.value.roundToInt()), exactly(springH.value.roundToInt()))
        val d = geo.compactH.roundToInt()
        bubble.measure(exactly(d), exactly(d))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
        bubble.layout(0, 0, bubble.measuredWidth, bubble.measuredHeight)
        applyFrame()
        val a = active
        if (a != null && (a.measuredWidth.toFloat() != baseW || a.measuredHeight.toFloat() != baseH)) {
            // Content changed size by itself (new text, a ticking clock): follow it with the springs.
            post { retarget() }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (prefs.on(Prefs.CALIBRATE)) {
            // An even red ring around the lens means the island sits exactly on the camera.
            canvas.drawCircle(originX(), geo.cy, geo.holeW / 2f + dp(3f), calibration)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN) {
            host.onOutside()
        }
        return true
    }

    private fun pressed(down: Boolean) {
        press.target = if (down) 0.96f else 1f
        kick()
    }

    // ---- the black shape ----------------------------------------------------------------------

    private inner class Surface(context: Context) : ViewGroup(context) {
        private var downX = 0f
        private var downY = 0f
        private var longFired = false
        private val longPress = Runnable {
            longFired = true
            pressed(false)
            haptic()
            host.onLongPress()
        }

        init {
            background = shape
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility != GONE) c.layout(0, 0, c.measuredWidth, c.measuredHeight)
            }
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    host.onInteraction()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (abs(dy) > touchSlop && abs(dy) > abs(event.rawX - downX)) {
                        beginDrag()
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    longFired = false
                    host.onInteraction()
                    pressed(true)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (!dragging && abs(dy) > touchSlop) beginDrag()
                    if (dragging) {
                        stretch = dy
                        applyTargets()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    pressed(false)
                    if (dragging) endDrag(event.rawY - downY) else if (!longFired) host.onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    pressed(false)
                    if (dragging) endDrag(0f)
                }
            }
            return true
        }

        private fun beginDrag() {
            dragging = true
            removeCallbacks(longPress)
            pressed(false)
            springW.tune(900f, 1f)
            springH.tune(900f, 1f)
            springL.tune(900f, 1f)
        }

        private fun endDrag(dy: Float) {
            dragging = false
            stretch = 0f
            springs(growing = true)
            val threshold = dp(26f)
            when {
                dy > threshold -> host.onSwipe(down = true)
                dy < -threshold -> host.onSwipe(down = false)
            }
            applyTargets()
        }
    }

    /** The second activity, split off to the right of the pill. */
    private class Bubble(context: Context) : FrameLayout(context) {
        private val image = ImageView(context)

        init {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
            }
            addView(image)
        }

        fun bind(island: Island) {
            image.show(island.leading, island.leadingShape, island.leadingTint)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val d = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(d, d)
            val inner = (d * 0.62f).roundToInt()
            image.measure(exactly(inner), exactly(inner))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val d = r - l
            val s = image.measuredWidth
            image.layout((d - s) / 2, (d - s) / 2, (d + s) / 2, (d + s) / 2)
        }
    }
}

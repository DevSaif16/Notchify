package com.saif.notchify.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.saif.notchify.Geometry
import com.saif.notchify.Info
import com.saif.notchify.Island
import com.saif.notchify.IslandAction
import com.saif.notchify.Kind
import com.saif.notchify.Palette
import com.saif.notchify.Progress
import com.saif.notchify.R
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The expanded island: a top row that flanks the camera (app + status), a body with art, text and
 * a large trailing widget (or the driving HUD), an infographic panel, a progress row, actions, and
 * a strip with the other live activities.
 */
class ExpandedView(
    context: Context,
    private val onAction: (IslandAction) -> Unit,
    onSelect: (String) -> Unit,
) : LinearLayout(context), Bindable {
    private val top = HoleRow(context)
    private val body = LinearLayout(context)
    private val art = ImageView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val big = TrailingView(context)
    private val nav = NavHudView(context)
    private val stages = StagesView(context)
    private val segments = SegmentBarView(context)
    private val call = CallControlsView(context)
    private val torch = TorchControlsView(context)
    private val controls = ControlsView(context)
    private val progress = ProgressRow(context)
    private val actions = LinearLayout(context)
    private val others = OthersStrip(context, onSelect)

    private var shownKey: String? = null
    private var shownLeadingKey: String? = null
    private var glowColor = 0
    private var glowing = false
    private var shown = false
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(dpi(20f), 0, dpi(20f), dpi(18f))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        body.orientation = HORIZONTAL
        body.gravity = Gravity.CENTER_VERTICAL
        body.addView(art, LayoutParams(dpi(58f), dpi(58f)).apply { marginEnd = dpi(14f) })
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        title.style(17f, Palette.WHITE, 600)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        subtitle.style(14f, Palette.TEXT2)
        subtitle.ellipsize = TextUtils.TruncateAt.END
        subtitle.setLineSpacing(0f, 1.1f)
        texts.addView(title)
        texts.addView(subtitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(3f) })
        body.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        body.addView(big, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(10f) })
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(2f) })

        addView(nav, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4f) })
        for (v in listOf(stages, segments, call, torch, controls)) {
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })
        }
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })

        actions.orientation = HORIZONTAL
        actions.gravity = Gravity.CENTER
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
        addView(others, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })
    }

    override fun bind(island: Island, geo: Geometry) = bind(island, geo, emptyList())

    fun bind(island: Island, geo: Geometry, otherIslands: List<Island>) {
        val sameIsland = island.key == shownKey
        top.bind(island, geo)

        val info = island.info
        val isNav = info is Info.Nav
        body.visibility = if (isNav || info is Info.Controls) GONE else VISIBLE
        controls.visibility = if (info is Info.Controls) VISIBLE else GONE
        if (info is Info.Controls) controls.bind(info.controls, fresh = !sameIsland)
        nav.visibility = if (isNav) VISIBLE else GONE
        if (info is Info.Nav) nav.bind(island, info)

        art.visibility = if (island.art == null) GONE else VISIBLE
        art.show(island.art, island.artShape, island.artTint)
        if (island.kind == Kind.MEDIA) {
            if (sameIsland && island.leadingKey != shownLeadingKey) {
                // New track: the cover flips over.
                art.rotationY = 90f
                art.animate().rotationY(0f).setDuration(420).setInterpolator(OvershootInterpolator(1.4f)).start()
            }
            val s = if (island.playing) 1f else 0.84f
            art.animate().scaleX(s).scaleY(s).setDuration(450).setInterpolator(OvershootInterpolator(1.8f)).start()
        } else if (!sameIsland) {
            art.scaleX = 1f
            art.scaleY = 1f
            art.rotationY = 0f
        }

        title.text = island.title
        title.style(if (island.titleLarge) 26f else 17f, Palette.WHITE, if (island.titleLarge) 700 else 600)
        title.visibility = if (island.title.isNullOrBlank()) GONE else VISIBLE
        subtitle.text = island.subtitle
        subtitle.maxLines = if (island.kind == Kind.NOTIFICATION) 4 else 2
        subtitle.visibility = if (island.subtitle.isNullOrBlank()) GONE else VISIBLE

        if (island.bigTrailing) {
            big.visibility = VISIBLE
            big.bind(island.trailing, dpi(48f), big = true)
        } else {
            big.visibility = GONE
        }

        stages.visibility = if (info is Info.Stages) VISIBLE else GONE
        if (info is Info.Stages) stages.set(info.labels, info.current, island.accent)
        segments.visibility = if (info is Info.Segments) VISIBLE else GONE
        if (info is Info.Segments) segments.set(info.parts, info.points, info.progress, info.tracker)
        call.visibility = if (info is Info.Call) VISIBLE else GONE
        if (info is Info.Call) call.bind(info)
        torch.visibility = if (info is Info.Torch) VISIBLE else GONE
        if (info is Info.Torch) torch.bind(info, fresh = !sameIsland)

        val p = island.progress
        progress.visibility = if (p == null) GONE else VISIBLE
        if (p != null) progress.bind(p, if (island.kind == Kind.MEDIA) island.accent else Palette.WHITE)

        bindActions(island.actions)
        others.bind(otherIslands, withControls = info !is Info.Controls)

        glowColor = island.accent
        glowing = island.kind == Kind.MEDIA
        glow.shader = null
        shownKey = island.key
        shownLeadingKey = island.leadingKey
        playing = island.playing
        invalidate()
    }

    private var playing = false

    /** A soft light in the cover's colour behind the music; it breathes while playing (20 fps, expanded only). */
    override fun onDraw(canvas: Canvas) {
        if (glowing && width > 0) {
            val t = SystemClock.uptimeMillis() / 1000f
            val breathe = if (playing) 0.5f + 0.5f * sin(t * 2.2f) else 0f
            val cx = art.left + body.left + art.width / 2f
            val cy = art.top + body.top + art.height / 2f
            val r = width * (0.55f + 0.08f * breathe)
            glow.shader = RadialGradient(cx, cy, r, withAlpha(glowColor, 0.30f + 0.12f * breathe), 0, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
            if (playing && shown) postInvalidateDelayed(50)
        }
        super.onDraw(canvas)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (isVisible) invalidate()
    }

    /** Same buttons as before: update them in place, so play ↔ pause morphs instead of blinking. */
    private fun bindActions(list: List<IslandAction>) {
        actions.visibility = if (list.isEmpty()) GONE else VISIBLE
        val sameShape = actions.childCount == list.size &&
            list.indices.all { (actions.getChildAt(it).getTag(TAG_STYLE) as? IslandAction.Style) == list[it].style }
        if (!sameShape) {
            actions.removeAllViews()
            list.forEach { actions.addView(button(it)) }
            return
        }
        list.forEachIndexed { i, a ->
            val v = actions.getChildAt(i)
            v.setOnClickListener { click(it, a) }
            if (v.getTag(TAG_ID) == a.id) return@forEachIndexed
            v.setTag(TAG_ID, a.id)
            v.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.4f).setDuration(110).withEndAction {
                if (v is ImageView) v.show(a.icon, Island.Shape.NONE, Palette.WHITE) else (v as TextView).text = a.label
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.5f)).start()
            }.start()
        }
    }

    private fun click(v: View, action: IslandAction) {
        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
        onAction(action)
    }

    private fun button(action: IslandAction): View {
        val v: View = when (action.style) {
            IslandAction.Style.MEDIA, IslandAction.Style.MEDIA_MAIN -> {
                val main = action.style == IslandAction.Style.MEDIA_MAIN
                val size = dpi(if (main) 60f else 50f)
                ImageView(context).apply {
                    show(action.icon, Island.Shape.NONE, Palette.WHITE)
                    val pad = dpi(if (main) 12f else 12f)
                    setPadding(pad, pad, pad, pad)
                    background = if (main) {
                        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), pill(Palette.CHIP), null)
                    } else {
                        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ShapeDrawable(OvalShape()))
                    }
                    layoutParams = LayoutParams(size, size).apply { marginStart = dpi(18f); marginEnd = dpi(18f) }
                }
            }
            IslandAction.Style.ACCEPT, IslandAction.Style.DECLINE -> {
                val size = dpi(60f)
                val fill = if (action.style == IslandAction.Style.ACCEPT) Palette.GREEN else Palette.RED
                ImageView(context).apply {
                    show(action.icon, Island.Shape.NONE, Palette.WHITE)
                    val pad = dpi(16f)
                    setPadding(pad, pad, pad, pad)
                    val disc = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(fill)
                    }
                    background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), disc, null)
                    layoutParams = LayoutParams(size, size).apply { marginStart = dpi(34f); marginEnd = dpi(34f) }
                }
            }
            IslandAction.Style.CHIP -> TextView(context).apply {
                text = action.label
                style(14f, Palette.WHITE, 500)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(12f), 0, dpi(12f), 0)
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), pill(Palette.CHIP), null)
                layoutParams = LayoutParams(0, dpi(42f), 1f).apply { marginStart = dpi(4f); marginEnd = dpi(4f) }
            }
        }
        v.setTag(TAG_STYLE, action.style)
        v.setTag(TAG_ID, action.id)
        v.setOnClickListener { click(it, action) }
        return v
    }

    companion object {
        // setTag(key, …) only accepts real resource ids; generated view ids crash it.
        private val TAG_STYLE = R.id.tag_action_style
        private val TAG_ID = R.id.tag_action_id
    }
}

/** First row of the expanded island: app on the left of the camera, status on the right, never under it. */
class HoleRow(context: Context) : ViewGroup(context) {
    private var geo: Geometry? = null
    private val icon = ImageView(context)
    private val label = TextView(context).apply {
        style(13f, Palette.TEXT2, 500)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val right = TextView(context).apply {
        style(13f, Palette.TEXT2, 500)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        addView(icon)
        addView(label)
        addView(right)
    }

    fun bind(island: Island, geo: Geometry) {
        this.geo = geo
        icon.show(island.appIcon, Island.Shape.ROUNDED, null)
        icon.visibility = if (island.appIcon == null) GONE else VISIBLE
        label.text = island.appLabel
        right.text = island.topRight
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val g = geo ?: return setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val half = (w / 2f - g.holeW / 2f - dp(12f)).roundToInt()
        val iconSize = dpi(18f)
        icon.measure(exactly(iconSize), exactly(iconSize))
        val iconSpace = if (icon.visibility == GONE) 0 else iconSize + dpi(7f)
        label.measure(atMost(max(0, half - iconSpace)), UNSPECIFIED)
        right.measure(atMost(half), UNSPECIFIED)
        setMeasuredDimension(w, g.compactH.roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        var x = 0
        if (icon.visibility != GONE) {
            val s = icon.measuredWidth
            icon.layout(0, (h - s) / 2, s, (h + s) / 2)
            x = s + dpi(7f)
        }
        label.layout(x, (h - label.measuredHeight) / 2, x + label.measuredWidth, (h + label.measuredHeight) / 2)
        val rw = right.measuredWidth
        right.layout(r - l - rw, (h - right.measuredHeight) / 2, r - l, (h + right.measuredHeight) / 2)
    }
}

/** Elapsed / bar / remaining. Media ticks once a second while visible and playing. */
class ProgressRow(context: Context) : LinearLayout(context) {
    private val elapsed = TextView(context)
    private val bar = ScrubBar(context)
    private val remaining = TextView(context)
    private var progress: Progress? = null
    private var shown = false
    private val tick = Runnable { refresh() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (t in listOf(elapsed, remaining)) {
            t.style(12f, Palette.TEXT2, 500)
            t.fontFeatureSettings = "tnum"
        }
        addView(elapsed)
        addView(bar, LayoutParams(0, dpi(24f), 1f).apply { marginStart = dpi(10f); marginEnd = dpi(10f) })
        addView(remaining)
    }

    fun bind(p: Progress, color: Int) {
        progress = p
        bar.color = color
        when (p) {
            is Progress.Fixed -> {
                elapsed.visibility = GONE
                remaining.visibility = GONE
                bar.interactive = false
                bar.fraction = p.fraction
            }
            is Progress.Media -> {
                elapsed.visibility = VISIBLE
                remaining.visibility = VISIBLE
                bar.interactive = p.seek != null
                bar.onDrag = { f -> label((f * p.duration).toLong(), p.duration) }
                bar.onSeek = { f -> p.seek?.invoke((f * p.duration).toLong()) }
            }
        }
        refresh()
    }

    private fun refresh() {
        removeCallbacks(tick)
        val p = progress as? Progress.Media ?: return
        if (p.duration <= 0) return
        val pos = p.position().coerceIn(0, p.duration)
        if (!bar.dragging) {
            bar.fraction = pos.toFloat() / p.duration
            label(pos, p.duration)
        }
        if (shown && p.playing) postDelayed(tick, 1000 - pos % 1000)
    }

    private fun label(pos: Long, duration: Long) {
        elapsed.text = ChronoText.format(pos / 1000)
        remaining.text = ChronoText.format(-((duration - pos) / 1000))
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (isVisible) refresh() else removeCallbacks(tick)
    }
}

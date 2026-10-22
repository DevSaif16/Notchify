package com.saif.notchify.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.saif.notchify.Island
import com.saif.notchify.Palette
import com.saif.notchify.QuickControls
import com.saif.notchify.R
import kotlin.math.roundToInt

/**
 * The control centre: a 4 × 2 grid of rounded tiles with their state written inside, two drag
 * sliders (brightness, media volume) and, only when something is live, a row showing which
 * sensors are in use. Tap toggles; long-press opens the full system page.
 */
class ControlsView(context: Context) : LinearLayout(context) {
    private val sensors = LinearLayout(context)
    private val grid = LinearLayout(context)
    private val wifi = Tile(context)
    private val data = Tile(context)
    private val bt = Tile(context)
    private val location = Tile(context)
    private val torch = Tile(context)
    private val glyph = Tile(context)
    private val dnd = Tile(context)
    private val shot = Tile(context)
    private val brightness = SliderPill(context, R.drawable.ic_brightness)
    private val volume = SliderPill(context, R.drawable.ic_speaker)
    private var controls: QuickControls? = null
    private val onChange: () -> Unit = { refresh() }

    init {
        orientation = VERTICAL
        sensors.orientation = HORIZONTAL
        sensors.gravity = Gravity.CENTER_VERTICAL
        addView(sensors, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(8f) })
        grid.orientation = VERTICAL
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        row(wifi, data, bt, location)
        row(torch, glyph, dnd, shot)
        val sliders = LinearLayout(context).apply { orientation = HORIZONTAL }
        sliders.addView(brightness, LayoutParams(0, dpi(48f), 1f).apply { marginEnd = dpi(4f) })
        sliders.addView(volume, LayoutParams(0, dpi(48f), 1f).apply { marginStart = dpi(4f) })
        addView(sliders, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(8f)
            marginStart = dpi(3f)
            marginEnd = dpi(3f)
        })
    }

    private fun row(vararg tiles: Tile) {
        val r = LinearLayout(context).apply { orientation = HORIZONTAL }
        tiles.forEach { r.addView(it, LayoutParams(0, dpi(78f), 1f).apply { setMargins(dpi(3f), dpi(3f), dpi(3f), dpi(3f)) }) }
        grid.addView(r, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(c: QuickControls, fresh: Boolean) {
        if (controls !== c) {
            controls?.unlisten(onChange)
            controls = c
            if (isAttachedToWindow) c.listen(onChange)
            wifi.on(c::tapWifi) { c.more("wifi") }
            data.on(c::tapData) { c.more("data") }
            bt.on(c::tapBluetooth) { c.more("bt") }
            location.on(c::tapLocation) { c.more("location") }
            torch.on(c::tapTorch, null)
            glyph.on({ c.glyph.toggle() }, null)
            dnd.on(c::tapDnd) { c.more("dnd") }
            shot.on(c::tapScreenshot, null)
            brightness.onChange = { f -> c.setBrightness(f) }
            volume.onChange = { f -> c.setVolume(f); true }
        }
        brightness.set(c.brightness)
        volume.set(c.volume)
        refresh()
        if (fresh) cascade()
    }

    /** Tiles ripple in one after another as the panel opens (transforms only: no layout work). */
    private fun cascade() {
        listOf(wifi, data, bt, location, torch, glyph, dnd, shot, brightness, volume).forEachIndexed { i, t ->
            t.alpha = 0f
            t.scaleX = 0.82f
            t.scaleY = 0.82f
            t.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(50L + i * 22L).setDuration(360)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
        }
    }

    private fun refresh() {
        val c = controls ?: return
        wifi.set(R.drawable.ic_wifi, "Wi-Fi", if (c.wifiOn) c.ssid ?: "On" else "Off", c.wifiOn, Palette.BLUE)
        data.set(
            R.drawable.ic_cellular, "Data",
            if (c.dataOn) listOfNotNull(c.netType, c.carrier).joinToString(" · ").ifBlank { "On" } else "Off",
            c.dataOn, Palette.GREEN,
        )
        bt.set(
            R.drawable.ic_bluetooth, "Bluetooth",
            if (!c.btOn) "Off" else c.btDevices.joinToString(", ").ifBlank { "On" },
            c.btOn, Palette.BLUE,
        )
        location.set(R.drawable.ic_location, "Location", if (c.locationOn) "On" else "Off", c.locationOn, Palette.BLUE)
        torch.set(R.drawable.ic_flashlight, "Torch", if (c.torch) "On" else "Off", c.torch, Palette.YELLOW)
        glyph.visibility = if (c.glyph.available) VISIBLE else INVISIBLE
        glyph.set(R.drawable.ic_glyph, "Glyph", if (c.glyph.on) "On" else "Off", c.glyph.on, Palette.WHITE)
        dnd.set(R.drawable.ic_dnd, "Focus", if (c.dndOn) "Do not disturb" else "Off", c.dndOn, 0xFF5E5CE6.toInt())
        shot.set(R.drawable.ic_screenshot, "Capture", "Screenshot", false, Palette.WHITE)

        sensors.removeAllViews()
        val inUse = listOfNotNull(
            if (c.cameraInUse) Triple(R.drawable.ic_camera, "Camera", Palette.GREEN) else null,
            if (c.micInUse) Triple(R.drawable.ic_mic, "Mic", Palette.ORANGE) else null,
            if (c.gpsInUse) Triple(R.drawable.ic_location, "Location", Palette.BLUE) else null,
        )
        sensors.visibility = if (inUse.isEmpty()) GONE else VISIBLE
        for ((icon, label, color) in inUse) {
            val chip = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(8f), dpi(4f), dpi(10f), dpi(4f))
                background = pill(withAlpha(color, 0.18f))
            }
            chip.addView(ImageView(context).apply { show(context.getDrawable(icon), Island.Shape.NONE, color) }, LayoutParams(dpi(14f), dpi(14f)))
            chip.addView(TextView(context).apply {
                text = label
                style(12f, color, 600)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(5f) })
            sensors.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(6f) })
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controls?.listen(onChange)
    }

    override fun onDetachedFromWindow() {
        controls?.unlisten(onChange)
        super.onDetachedFromWindow()
    }

    /** A rounded square: icon, name and state inside it, all aligned to one left edge. */
    private class Tile(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val title = TextView(context)
        private val state = TextView(context)
        private var active: Boolean? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dpi(11f), dpi(9f), dpi(8f), dpi(9f))
            addView(icon, LayoutParams(dpi(22f), dpi(22f)))
            for (t in listOf(title, state)) {
                t.isSingleLine = true
                t.ellipsize = TextUtils.TruncateAt.END
            }
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(7f) })
            addView(state, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(1f) })
        }

        fun on(tap: () -> Unit, long: (() -> Unit)?) {
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
                tap()
            }
            setOnLongClickListener(long?.let { l -> OnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                l()
                true
            } })
        }

        fun set(iconRes: Int, name: String, value: String, on: Boolean, color: Int) {
            val dark = 0xFF000000.toInt()
            icon.show(context.getDrawable(iconRes), Island.Shape.NONE, if (on) dark else color)
            title.text = name
            title.style(12.5f, if (on) dark else Palette.WHITE, 600)
            state.text = value
            state.style(11f, if (on) 0xB3000000.toInt() else Palette.TEXT2, 500)
            if (active != on) {
                val was = active
                active = on
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), pill(if (on) color else 0xFF1C1C1E.toInt(), 20f), null)
                if (was != null && on) {
                    icon.scaleX = 0.55f
                    icon.scaleY = 0.55f
                    icon.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
                }
            }
        }
    }

    /**
     * A drag-anywhere slider pill, like iOS: drag moves the level by the distance travelled (not
     * to where the finger is), the fill trails the finger with a short spring, and every 10 %
     * ticks the haptics.
     */
    private class SliderPill(context: Context, iconRes: Int) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1E.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WHITE }
        private val glyph = context.getDrawable(iconRes)!!.mutate()
        private val rect = RectF()
        private var value = 0.5f
        private var drawn = 0.5f
        private var lastX = 0f
        private var lastTick = -1
        var onChange: ((Float) -> Boolean)? = null

        fun set(v: Float) {
            value = v.coerceIn(0f, 1f)
            drawn = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, track)
            canvas.save()
            canvas.clipRect(0f, 0f, width * drawn, height.toFloat())
            canvas.drawRoundRect(rect, r, r, fill)
            canvas.restore()
            val s = (height * 0.46f).roundToInt()
            val x = (height - s) / 2
            glyph.setBounds(x + dpi(4f), (height - s) / 2, x + dpi(4f) + s, (height + s) / 2)
            glyph.setTint(if (width * drawn > x + s) 0xFF000000.toInt() else Palette.WHITE)
            glyph.draw(canvas)
            // Ease the drawn fill toward the value: smooth at any refresh rate, stops when settled.
            val d = value - drawn
            if (kotlin.math.abs(d) > 0.002f) {
                drawn += d * 0.45f
                postInvalidateOnAnimation()
            } else {
                drawn = value
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    lastX = event.x
                    lastTick = (value * 10).toInt()
                    animate().scaleY(1.08f).setDuration(120).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    value = (value + (event.x - lastX) / width).coerceIn(0f, 1f)
                    lastX = event.x
                    val tick = (value * 10).toInt()
                    if (tick != lastTick) {
                        lastTick = tick
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (onChange?.invoke(value) == false) {
                        parent.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animate().scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            return true
        }
    }
}

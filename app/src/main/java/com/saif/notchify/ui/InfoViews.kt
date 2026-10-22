package com.saif.notchify.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.saif.notchify.Info
import com.saif.notchify.Island
import com.saif.notchify.Palette
import com.saif.notchify.R
import com.saif.notchify.Telemetry
import com.saif.notchify.Trailing
import kotlin.math.roundToInt

internal fun View.pill(color: Int, radiusDp: Float = 100f) = GradientDrawable().apply {
    cornerRadius = dp(radiusDp)
    setColor(color)
}

internal fun View.ripple(content: GradientDrawable?) =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, content ?: pill(Palette.WHITE))

/**
 * Turn-by-turn HUD, readable at a glance on a mount: the maneuver on a coloured tile, the
 * distance in big rolling digits, an approach ring that fills as the turn nears, trip progress
 * and facts (ETA, distance left, speed).
 */
class NavHudView(context: Context) : LinearLayout(context) {
    private val tile = FrameLayout(context)
    private val arrow = ImageView(context)
    private val distance = ChronoText(context)
    private val street = TextView(context)
    private val approach = RingView(context)
    private val segments = SegmentBarView(context)
    private val chips = LinearLayout(context)
    private val speed = TextView(context)
    private var maneuverKey: String? = null
    private var pulsedFor: String? = null
    private val onSpeed: () -> Unit = { showSpeed() }

    init {
        orientation = VERTICAL
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tile.addView(arrow, FrameLayout.LayoutParams(dpi(50f), dpi(50f), Gravity.CENTER))
        row.addView(tile, LayoutParams(dpi(76f), dpi(76f)))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        distance.style(38f, Palette.WHITE, 700)
        street.style(16f, Palette.TEXT2, 500)
        street.maxLines = 2
        street.ellipsize = TextUtils.TruncateAt.END
        texts.addView(distance)
        texts.addView(street, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(2f) })
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(16f); marginEnd = dpi(10f) })
        row.addView(approach, LayoutParams(dpi(54f), dpi(54f)))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(segments, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
        chips.orientation = HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        addView(chips, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
        speed.style(13f, Palette.WHITE, 600)
        speed.setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f))
        speed.background = pill(Palette.CHIP)
    }

    fun bind(island: Island, nav: Info.Nav) {
        tile.background = pill(island.accent, 20f)
        arrow.show(island.art, Island.Shape.NONE, Palette.WHITE)
        if (maneuverKey != null && island.leadingKey != maneuverKey) {
            tile.pop()
            arrow.rotation = -40f
            arrow.animate().rotation(0f).setDuration(500).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
        maneuverKey = island.leadingKey
        distance.static(nav.distance ?: "")
        street.text = nav.street
        street.visibility = if (nav.street.isNullOrBlank()) GONE else VISIBLE

        val near = (nav.approach ?: 0f) >= 0.9f
        approach.visibility = if (nav.approach == null) GONE else VISIBLE
        approach.set(nav.approach ?: 0f, island.accent)
        approach.center = if (near) "Now" else null
        if (near && pulsedFor != maneuverKey) {
            pulsedFor = maneuverKey
            tile.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).withEndAction {
                tile.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }.start()
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }

        val s = nav.segments
        segments.visibility = if (s == null) GONE else VISIBLE
        if (s != null) segments.set(s.parts, s.points, s.progress, s.tracker)

        chips.removeAllViews()
        nav.trip.take(3).forEach { chips.addView(chip(it)) }
        (speed.parent as? LinearLayout)?.removeView(speed)
        chips.addView(speed, chipParams())
        showSpeed()
    }

    private fun chip(text: CharSequence) = TextView(context).apply {
        this.text = text
        style(13f, Palette.WHITE, 500)
        isSingleLine = true
        setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f))
        background = pill(Palette.CHIP)
        layoutParams = chipParams()
    }

    private fun chipParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(6f) }

    private fun showSpeed() {
        val kmh = Telemetry.speedKmh
        speed.visibility = if (kmh == null) GONE else VISIBLE
        if (kmh != null) speed.text = "${kmh.roundToInt()} km/h"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Telemetry.listen(onSpeed)
    }

    override fun onDetachedFromWindow() {
        Telemetry.unlisten(onSpeed)
        super.onDetachedFromWindow()
    }
}

/** Round button with a label underneath, as in the iOS call screen. Icon and label stay centred together. */
private class ControlButton(context: Context) : LinearLayout(context) {
    val circle = ImageView(context)
    val label = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val s = dpi(56f)
        val pad = dpi(16f)
        circle.setPadding(pad, pad, pad, pad)
        addView(circle, LayoutParams(s, s))
        label.style(11.5f, Palette.TEXT2, 500)
        label.isSingleLine = true
        label.gravity = Gravity.CENTER
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
    }

    fun set(icon: Int, text: String, active: Boolean, fill: Int? = null) {
        val bgColor = fill ?: if (active) Palette.WHITE else Palette.CHIP
        val fg = if (fill == null && active) 0xFF000000.toInt() else Palette.WHITE
        circle.show(context.getDrawable(icon), Island.Shape.NONE, fg)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
        }
        circle.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
        label.text = text
    }
}

/**
 * Controls for the call in progress, in one row: mute, audio route (tap cycles phone → speaker →
 * Bluetooth → headset), keypad and end, with a slim call-volume bar under it. Works for cellular
 * and VoIP calls alike, through the system audio manager rather than any one dialer.
 */
class CallControlsView(context: Context) : LinearLayout(context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val mute = ControlButton(context)
    private val route = ControlButton(context)
    private val keypad = ControlButton(context)
    private val end = ControlButton(context)
    private val volume = ScrubBar(context)
    private var info: Info.Call? = null

    init {
        orientation = VERTICAL
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (b in listOf(mute, route, keypad, end)) row.addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val vol = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        vol.addView(volumeButton(R.drawable.ic_volume_down, AudioManager.ADJUST_LOWER), LayoutParams(dpi(36f), dpi(36f)))
        vol.addView(volume, LayoutParams(0, dpi(22f), 1f).apply { marginStart = dpi(6f); marginEnd = dpi(6f) })
        vol.addView(volumeButton(R.drawable.ic_speaker, AudioManager.ADJUST_RAISE), LayoutParams(dpi(36f), dpi(36f)))
        addView(vol, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })

        mute.circle.setOnClickListener { press(it) { am.isMicrophoneMute = !am.isMicrophoneMute } }
        route.circle.setOnClickListener { press(it) { nextRoute() } }
        keypad.circle.setOnClickListener { press(it) { info?.openCallScreen?.invoke() } }
        end.circle.setOnClickListener { press(it) { info?.hangUp?.invoke() } }
    }

    fun bind(call: Info.Call) {
        info = call
        refresh()
    }

    private fun press(v: View, run: () -> Unit) {
        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }.start()
        run()
        // Route and mute changes land asynchronously; read them back a beat later.
        postDelayed({ refresh() }, 180)
    }

    private fun volumeButton(icon: Int, direction: Int) = ImageView(context).apply {
        show(context.getDrawable(icon), Island.Shape.NONE, Palette.TEXT2)
        val p = dpi(8f)
        setPadding(p, p, p, p)
        background = ripple(null)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, 0)
            refresh()
        }
    }

    private class Route(val device: AudioDeviceInfo, val label: String, val icon: Int)

    private fun routes(): List<Route> {
        val seen = HashSet<String>()
        return am.availableCommunicationDevices.mapNotNull { d ->
            val (label, icon) = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone" to R.drawable.ic_call
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker" to R.drawable.ic_speaker
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth" to R.drawable.ic_bluetooth
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> "Headset" to R.drawable.ic_headset
                else -> return@mapNotNull null
            }
            if (seen.add(label)) Route(d, label, icon) else null
        }
    }

    private fun nextRoute() {
        val all = routes()
        if (all.isEmpty()) return
        val i = all.indexOfFirst { it.device.id == am.communicationDevice?.id }
        am.setCommunicationDevice(all[(i + 1) % all.size].device)
    }

    private fun refresh() {
        val muted = am.isMicrophoneMute
        mute.set(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic, if (muted) "Muted" else "Mute", muted)
        val current = routes().firstOrNull { it.device.id == am.communicationDevice?.id }
        route.set(current?.icon ?: R.drawable.ic_speaker, current?.label ?: "Audio", current != null && current.label != "Phone")
        keypad.visibility = if (info?.openCallScreen == null) GONE else VISIBLE
        keypad.set(R.drawable.ic_dialpad, "Keypad", false)
        end.visibility = if (info?.hangUp == null) GONE else VISIBLE
        end.set(R.drawable.ic_call_end, "End", false, fill = Palette.RED)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        volume.fraction = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL).toFloat() / max
    }
}

/** Torch strength as a row of rising bars you can tap, under a beam that brightens with it. */
class TorchControlsView(context: Context) : LinearLayout(context) {
    private val beam = TorchView(context).apply { beam = true }
    private val bars = LevelBars(context)

    init {
        orientation = VERTICAL
        addView(beam, LayoutParams(LayoutParams.MATCH_PARENT, dpi(92f)))
        addView(bars, LayoutParams(LayoutParams.MATCH_PARENT, dpi(44f)).apply { topMargin = dpi(10f) })
    }

    fun bind(info: Info.Torch, fresh: Boolean) {
        beam.intensity = info.level.toFloat() / info.max.coerceAtLeast(1)
        if (fresh) beam.ignite()
        bars.visibility = if (info.max > 1) VISIBLE else GONE
        bars.set(info.level, info.max, info.setLevel)
    }

    private class LevelBars(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var level = 1
        private var max = 1
        private var onLevel: (Int) -> Unit = {}

        fun set(level: Int, max: Int, onLevel: (Int) -> Unit) {
            this.level = level
            this.max = max
            this.onLevel = onLevel
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val gap = dp(6f)
            val w = (width - gap * (max - 1)) / max
            for (i in 0 until max) {
                val h = height * (0.3f + 0.7f * (i + 1) / max)
                val x = i * (w + gap)
                paint.color = if (i < level) Palette.YELLOW else 0xFF3A3A3C.toInt()
                canvas.drawRoundRect(x, height - h, x + w, height.toFloat(), dp(5f), dp(5f), paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                val l = ((event.x / width) * max).toInt().coerceIn(0, max - 1) + 1
                if (l != level) {
                    level = l
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onLevel(l)
                    invalidate()
                }
            }
            return true
        }
    }
}

const val CONTROLS_KEY = "controls"

/** The other live activities, as small pills under the expanded island; tap one to switch to it. */
class OthersStrip(context: Context, private val onSelect: (String) -> Unit) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun bind(others: List<Island>, withControls: Boolean) {
        removeAllViews()
        if (withControls) {
            val c = TextView(context).apply {
                text = "Controls"
                style(13f, Palette.WHITE, 600)
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    context.getDrawable(R.drawable.ic_glyph)?.mutate()?.apply {
                        setTint(Palette.WHITE)
                        setBounds(0, 0, dpi(16f), dpi(16f))
                    }, null, null, null,
                )
                compoundDrawablePadding = dpi(6f)
                setPadding(dpi(10f), dpi(7f), dpi(12f), dpi(7f))
                background = ripple(pill(Palette.CHIP))
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onSelect(CONTROLS_KEY)
                }
            }
            addView(c, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8f) })
        }
        for (o in others.take(3)) {
            val item = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(8f), dpi(6f), dpi(12f), dpi(6f))
                background = ripple(pill(Palette.CHIP))
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onSelect(o.key)
                }
            }
            val icon = ImageView(context)
            icon.show(o.leading, o.leadingShape, o.leadingTint)
            item.addView(icon, LayoutParams(dpi(22f), dpi(22f)))
            val text = ChronoText(context).apply {
                style(13f, o.accent, 600)
                when (val t = o.trailing) {
                    is Trailing.Chrono -> chrono(t.base, t.countDown)
                    is Trailing.Text -> static(t.text)
                    else -> static(o.title?.toString()?.take(14) ?: "")
                }
            }
            item.addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(7f) })
            addView(item, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8f) })
        }
    }
}

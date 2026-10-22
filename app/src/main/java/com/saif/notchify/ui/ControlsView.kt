package com.saif.notchify.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.saif.notchify.Island
import com.saif.notchify.Palette
import com.saif.notchify.QuickControls
import com.saif.notchify.R

/**
 * The control centre: rounded-square tiles with the state written inside them (network name, SIM
 * and generation, connected Bluetooth devices) and a row showing which sensors are in use right now.
 * Tap toggles; long-press opens the full settings page.
 */
class ControlsView(context: Context) : LinearLayout(context) {
    private val sensors = LinearLayout(context)
    private val rows = LinearLayout(context)
    private val wifi = Tile(context)
    private val data = Tile(context)
    private val bt = Tile(context)
    private val location = Tile(context)
    private val torch = Tile(context)
    private val glyph = Tile(context)
    private var controls: QuickControls? = null
    private val onChange: () -> Unit = { refresh() }

    init {
        orientation = VERTICAL
        sensors.orientation = HORIZONTAL
        sensors.gravity = Gravity.CENTER_VERTICAL
        addView(sensors, LayoutParams(LayoutParams.MATCH_PARENT, dpi(26f)))
        rows.orientation = VERTICAL
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        row(wifi, data, bt)
        row(location, torch, glyph)
    }

    private fun row(vararg tiles: Tile) {
        val r = LinearLayout(context).apply { orientation = HORIZONTAL }
        tiles.forEach { r.addView(it, LayoutParams(0, dpi(96f), 1f).apply { setMargins(dpi(4f), dpi(4f), dpi(4f), dpi(4f)) }) }
        rows.addView(r, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(c: QuickControls, fresh: Boolean) {
        if (controls !== c) {
            controls?.unlisten(onChange)
            controls = c
            if (isAttachedToWindow) c.listen(onChange)
            wifi.setOnClickListener { tap(it) { c.toggleWifi() } }
            data.setOnClickListener { tap(it) { c.toggleData() } }
            bt.setOnClickListener { tap(it) { c.toggleBluetooth() } }
            location.setOnClickListener { tap(it) { c.toggleLocation() } }
            torch.setOnClickListener { tap(it) { c.toggleTorch() } }
            glyph.setOnClickListener { tap(it) { c.glyph.toggle() } }
            wifi.setOnLongClickListener { c.openSettings("wifi"); true }
            data.setOnLongClickListener { c.openSettings("data"); true }
            bt.setOnLongClickListener { c.openSettings("bt"); true }
            location.setOnLongClickListener { c.openSettings("location"); true }
        }
        refresh()
        if (fresh) cascade()
    }

    private fun tap(v: android.view.View, run: () -> Unit) {
        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
        run()
    }

    /** Tiles ripple in one after another as the panel opens. */
    private fun cascade() {
        listOf(wifi, data, bt, location, torch, glyph).forEachIndexed { i, t ->
            t.alpha = 0f
            t.scaleX = 0.8f
            t.scaleY = 0.8f
            t.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L + i * 35L).setDuration(380)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        }
    }

    private fun refresh() {
        val c = controls ?: return
        wifi.set(R.drawable.ic_wifi, "Wi-Fi", if (c.wifiOn) c.ssid ?: "On" else "Off", c.wifiOn, Palette.BLUE)
        data.set(
            R.drawable.ic_cellular, "Mobile data",
            listOfNotNull(c.carrier, c.netType).joinToString(" · ").ifBlank { if (c.dataOn) "On" else "Off" },
            c.dataOn, Palette.GREEN,
        )
        bt.set(
            R.drawable.ic_bluetooth, "Bluetooth",
            if (!c.btOn) "Off" else c.btDevices.joinToString(", ").ifBlank { "Not connected" },
            c.btOn, Palette.BLUE,
        )
        location.set(R.drawable.ic_location, "Location", if (c.locationOn) "On" else "Off", c.locationOn, Palette.BLUE)
        torch.set(R.drawable.ic_flashlight, "Flashlight", if (c.torch) "On" else "Off", c.torch, Palette.YELLOW)
        glyph.visibility = if (c.glyph.available) VISIBLE else INVISIBLE
        glyph.set(R.drawable.ic_glyph, "Glyph light", if (c.glyph.on) "Matrix on" else "Off", c.glyph.on, Palette.WHITE)

        sensors.removeAllViews()
        val inUse = listOfNotNull(
            if (c.cameraInUse) Triple(R.drawable.ic_camera, "Camera", Palette.GREEN) else null,
            if (c.micInUse) Triple(R.drawable.ic_mic, "Mic", Palette.ORANGE) else null,
            if (c.gpsInUse) Triple(R.drawable.ic_location, "Location", Palette.BLUE) else null,
        )
        if (inUse.isEmpty()) {
            sensors.addView(TextView(context).apply {
                text = "No camera, mic or location in use"
                style(12f, Palette.TEXT2, 500)
            })
        }
        for ((icon, label, color) in inUse) {
            val chip = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(8f), dpi(3f), dpi(10f), dpi(3f))
                background = pill(withAlpha(color, 0.2f))
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

    /** A rounded square with its icon, name and state inside it. */
    private class Tile(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val title = TextView(context)
        private val state = TextView(context)
        private var active: Boolean? = null

        init {
            orientation = VERTICAL
            setPadding(dpi(12f), dpi(12f), dpi(10f), dpi(10f))
            addView(icon, LayoutParams(dpi(24f), dpi(24f)))
            title.isSingleLine = true
            title.ellipsize = TextUtils.TruncateAt.END
            state.maxLines = 2
            state.ellipsize = TextUtils.TruncateAt.END
            addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
            addView(state, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(2f) })
            isHapticFeedbackEnabled = true
        }

        fun set(iconRes: Int, name: String, value: String, on: Boolean, color: Int) {
            val fg = if (on) 0xFF000000.toInt() else Palette.WHITE
            icon.show(context.getDrawable(iconRes), Island.Shape.NONE, if (on) 0xFF000000.toInt() else color)
            title.text = name
            title.style(13f, fg, 600)
            state.text = value
            state.style(11.5f, if (on) 0xCC000000.toInt() else Palette.TEXT2, 500)
            if (active != on) {
                active = on
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), pill(if (on) color else 0xFF2C2C2E.toInt(), 22f), null)
                if (on) {
                    // Switching on lights the tile with a little bounce.
                    icon.scaleX = 0.6f
                    icon.scaleY = 0.6f
                    icon.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
                }
            }
        }
    }
}

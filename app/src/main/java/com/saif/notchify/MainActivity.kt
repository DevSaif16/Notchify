package com.saif.notchify

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Setup, feature switches and camera-fit calibration. Plain framework views keep the APK tiny. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val refreshers = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(36))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(list)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    override fun onStop() {
        super.onStop()
        if (prefs.on(Prefs.CALIBRATE)) prefs.set(Prefs.CALIBRATE, false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SPEED && granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            // Background ("Allow all the time") has to be asked for separately, after foreground.
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
        }
        refreshers.forEach { it() }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun build() {
        list.addView(text("Notchify", 40f, Color.WHITE, 700))
        list.addView(text("Your camera, now a dynamic island", 15f, Palette.TEXT2).apply { setPadding(0, dp(4), 0, dp(18)) })

        card("Setup") {
            step("Notification access", "Music, calls, maps, deliveries, timers and alerts", ::listenerOn, "Grant") { openListener() }
            step("Notch overlay", "Accessibility service that draws the island. It reads no screen content.", ::overlayOn, "Enable") { openOverlay() }
            note("Installed from an APK? If Android greys a switch out as a restricted setting, open App info → ⋮ → Allow restricted settings, then try again.")
            link("Open App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            step("No duplicate pop-ups", "Hides Android's heads-up banners while the island shows them, and lets the control centre switch location. One-time grant from a computer:", { HeadsUp.granted(this@MainActivity) }, "Copy") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", HeadsUp.GRANT_COMMAND))
                toast("adb command copied")
            }
            add(text(HeadsUp.GRANT_COMMAND, 12f, Palette.TEXT2).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, dp(2), 0, dp(4))
            })
            step("Control centre details", "Wi-Fi name, SIM and 5G/4G, Bluetooth devices, and the GPS-in-use indicator", {
                granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.READ_PHONE_STATE) &&
                    granted(Manifest.permission.ACCESS_FINE_LOCATION)
            }, "Allow") {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION),
                    REQ_CONTROLS,
                )
            }
        }

        card("On the island") {
            toggle("Navigation (top priority while routing)", Prefs.NAVIGATION)
            toggle("Calls & alarms", Prefs.CALLS)
            toggle("Deliveries & rides", Prefs.DELIVERY)
            toggle("Music & media", Prefs.MEDIA)
            toggle("Flashlight", Prefs.TORCH)
            toggle("Timers & stopwatches", Prefs.TIMERS)
            toggle("Live progress (downloads, uploads)", Prefs.LIVE)
            toggle("Notifications", Prefs.NOTIFICATIONS)
            toggle("Charging", Prefs.CHARGING)
            toggle("Silent / vibrate", Prefs.RINGER)
            toggle("Control centre (tap the camera)", Prefs.CONTROLS)
        }

        card("Driving") {
            note("Ride on the notch alone: the next turn, a live distance count-down and an approach ring, trip facts, and Maps' own buttons.")
            toggle("Open the HUD as each turn approaches", Prefs.DRIVE_PEEK)
            toggle("Keep the HUD open while navigating", Prefs.DRIVE_ALWAYS)
            toggle("Show speed (reuses Maps' location fixes)", Prefs.SPEED) { on ->
                if (on && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_SPEED)
                } else if (on && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
                }
            }
        }

        card("Behaviour") {
            toggle("Hide Android's own pop-ups", Prefs.HEADS_UP, enabled = { HeadsUp.granted(this@MainActivity) })
            toggle("Show message text on the lock screen", Prefs.LOCK_PREVIEWS)
            toggle("In full-screen apps, only calls & navigation", Prefs.HIDE_FULLSCREEN)
            toggle("Centre the pill on the camera (covers status icons)", Prefs.SYMMETRIC)
            toggle("Animated equaliser", Prefs.EQUALIZER)
            toggle("Haptics", Prefs.HAPTICS)
        }

        card("Fit to camera") {
            note("Read from the display's cutout, so it should already be exact. Turn on the ring: an even red halo around the lens means a perfect fit.")
            toggle("Show alignment ring", Prefs.CALIBRATE)
            slider("Horizontal", Prefs.OFFSET_X, 40)
            slider("Vertical", Prefs.OFFSET_Y, 40)
            slider("Size", Prefs.SIZE, 24)
            link("Reset") {
                prefs.reset(Prefs.OFFSET_X)
                prefs.reset(Prefs.OFFSET_Y)
                prefs.reset(Prefs.SIZE)
                refreshers.forEach { it() }
            }
        }

        card("Try it") {
            link("Preview a notification") { preview(::sampleNotification) }
            link("Preview a delivery") { preview(::sampleDelivery) }
            link("Preview a timer") { preview(::sampleTimer) }
            link("Preview charging") { preview(::sampleCharging) }
        }
    }

    // ---- previews -------------------------------------------------------------------------------

    private fun preview(make: () -> Unit) {
        if (NotchService.instance == null) toast("Turn on the notch overlay first") else make()
    }

    private fun sampleNotification() = Hub.banner(
        Island(
            key = "preview", kind = Kind.NOTIFICATION, accent = Palette.WHITE,
            leading = getDrawable(R.mipmap.ic_launcher), appIcon = getDrawable(R.mipmap.ic_launcher),
            appLabel = "Notchify", topRight = "now", title = "Notchify is ready",
            subtitle = "Music, calls, maps, deliveries and notifications now live around your camera.",
            actions = listOf(IslandAction(IslandAction.Style.CHIP, label = "Nice", collapse = true) {}),
        ),
    )

    private fun sampleDelivery() {
        val labels = listOf("Placed", "Preparing", "On the way", "Arriving", "Delivered")
        var stage = 1
        fun publish() {
            val eta = "${12 - stage * 3} min"
            Hub.put(
                Island(
                    key = "preview_order", kind = Kind.DELIVERY, accent = Palette.GREEN,
                    leading = getDrawable(R.drawable.ic_bag), leadingShape = Island.Shape.NONE, leadingTint = Palette.GREEN,
                    leadingKey = stage.toString(),
                    trailing = Trailing.Text(eta, Palette.GREEN), appIcon = getDrawable(R.mipmap.ic_launcher),
                    appLabel = "Notchify", topRight = "ETA $eta", title = "Your order is ${labels[stage].lowercase()}",
                    subtitle = "Preview order", art = getDrawable(R.drawable.ic_bag), artShape = Island.Shape.NONE,
                    artTint = Palette.GREEN, info = Info.Stages(labels, stage),
                ),
            )
            Hub.peek("preview_order", 4000)
        }
        publish()
        for (i in 2..4) {
            main.postDelayed({
                stage = i
                publish()
            }, (i - 1) * 6000L)
        }
        main.postDelayed({ Hub.remove("preview_order") }, 26_000)
    }

    private fun sampleTimer() {
        val icon = getDrawable(R.drawable.ic_timer)
        Hub.put(
            Island(
                key = "preview_timer", kind = Kind.TIMER, accent = Palette.ORANGE,
                leading = icon, leadingShape = Island.Shape.NONE, leadingTint = Palette.ORANGE,
                trailing = Trailing.Chrono(System.currentTimeMillis() + 60_000, true, Palette.ORANGE),
                appIcon = getDrawable(R.mipmap.ic_launcher), appLabel = "Notchify",
                title = "Tea", subtitle = "Preview timer", bigTrailing = true,
            ),
        )
        main.postDelayed({ Hub.remove("preview_timer") }, 60_000)
    }

    private fun sampleCharging() = Hub.banner(
        Island(
            key = "charging", kind = Kind.CHARGING, accent = Palette.GREEN,
            leading = getDrawable(R.drawable.ic_bolt), leadingShape = Island.Shape.NONE, leadingTint = Palette.GREEN,
            trailing = Trailing.Battery(80, Palette.GREEN), compactOnly = true, duration = 2800,
        ),
    )

    // ---- status ---------------------------------------------------------------------------------

    private fun listenerOn() = getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(this, NotificationService::class.java))

    private fun overlayOn(): Boolean {
        val me = ComponentName(this, NotchService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openListener() = open(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(this, NotificationService::class.java).flattenToString(),
        ),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    private fun openOverlay() = open(
        // Not in the public SDK; Settings still resolves it, and open() falls back to the list if not.
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            ComponentName(this, NotchService::class.java).flattenToString(),
        ),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    private fun open(first: Intent, fallback: Intent) {
        try {
            startActivity(first)
        } catch (_: Exception) {
            startActivity(fallback)
        }
    }

    // ---- tiny view kit --------------------------------------------------------------------------

    private inner class Card(val body: LinearLayout) {
        fun add(v: View) = body.addView(v)

        fun note(s: String) = add(text(s, 13f, Palette.TEXT2).apply { setPadding(0, dp(6), 0, dp(6)) })

        fun link(label: String, onClick: () -> Unit) = add(text(label, 15f, Palette.NOTHING_RED, 600).apply {
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onClick() }
        })

        fun step(title: String, desc: String, ok: () -> Boolean, action: String, onClick: () -> Unit) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(6))
            }
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(title, 16f, Color.WHITE, 600))
            texts.addView(text(desc, 13f, Palette.TEXT2).apply { setPadding(0, dp(2), dp(12), 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val status = text("", 14f, Color.WHITE, 600).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            row.addView(status)
            add(row)
            refreshers += {
                val done = ok()
                status.text = if (done) "On" else action
                status.setTextColor(if (done) Palette.GREEN else Color.WHITE)
                status.background = if (done) null else pill(Palette.NOTHING_RED)
                status.setOnClickListener(if (done) null else View.OnClickListener { onClick() })
            }
        }

        @Suppress("DEPRECATION")
        fun toggle(label: String, key: String, enabled: () -> Boolean = { true }, onChange: (Boolean) -> Unit = {}) {
            val s = Switch(this@MainActivity).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(8), 0, dp(8))
            }
            add(s)
            refreshers += {
                s.setOnCheckedChangeListener(null)
                s.isChecked = prefs.on(key)
                s.isEnabled = enabled()
                s.alpha = if (s.isEnabled) 1f else 0.4f
                s.setOnCheckedChangeListener { _, checked ->
                    prefs.set(key, checked)
                    onChange(checked)
                }
            }
        }

        fun slider(label: String, key: String, range: Int) {
            val title = text("", 14f, Color.WHITE, 500).apply { setPadding(0, dp(10), 0, 0) }
            val bar = SeekBar(this@MainActivity).apply {
                min = -range
                max = range
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                        title.text = "$label   ${if (value > 0) "+" else ""}$value px"
                        if (!fromUser) return
                        if (!prefs.on(Prefs.CALIBRATE)) prefs.set(Prefs.CALIBRATE, true)
                        prefs.setInt(key, value)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = refreshers.forEach { it() }
                })
            }
            add(title)
            add(bar)
            refreshers += {
                bar.progress = prefs.int(key)
                title.text = "$label   ${if (bar.progress > 0) "+" else ""}${bar.progress} px"
            }
        }
    }

    private fun card(heading: String, fill: Card.() -> Unit) {
        list.addView(text(heading.uppercase(), 12f, Palette.TEXT2, 600).apply {
            letterSpacing = 0.08f
            setPadding(dp(4), dp(14), 0, dp(8))
        })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xFF141414.toInt())
            }
        }
        Card(body).fill()
        list.addView(body)
    }

    private fun text(s: String, sp: Float, color: Int, weight: Int = 400) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(100).toFloat()
        setColor(color)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val REQ_SPEED = 1
        private const val REQ_BACKGROUND = 2
        private const val REQ_CONTROLS = 3
    }
}

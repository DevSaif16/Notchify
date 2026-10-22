package com.saif.notchify

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import com.saif.notchify.ui.EqualizerView
import com.saif.notchify.ui.IslandView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Owns the overlay. An accessibility overlay is the only window type an app may place above the
 * status bar and over the camera cutout. The service subscribes to no accessibility events and
 * reads no screen content.
 */
class NotchService : AccessibilityService(), Hub.Listener, IslandView.Host,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var view: IslandView? = null
    private var attached = false
    private val lp = LayoutParams()
    private val main = Handler(Looper.getMainLooper())

    private var ranked: List<Island> = emptyList()
    private var primary: Island? = null
    private var secondary: Island? = null
    /** The island currently open in the expanded view, whoever opened it. */
    private var open: Island? = null
    private var expandedKey: String? = null
    private var banner: Island? = null
    private val queue = ArrayDeque<Island>()
    private val autoSeen = HashSet<String>()

    private var peekKey: String? = null
    private var peekUrgent = false
    private var navDismissed = false

    private var screenOn = true
    private var landscape = false
    private var fullscreen = false

    private val endBanner = Runnable { finishBanner() }
    private val endPeek = Runnable { clearPeek() }
    private val autoCollapse = Runnable { collapse() }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        EqualizerView.animated = prefs.on(Prefs.EQUALIZER)

        val v = IslandView(this, this, Geometry.read(this, prefs), prefs)
        lp.type = LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        lp.format = PixelFormat.TRANSLUCENT
        lp.gravity = Gravity.TOP or Gravity.START
        lp.flags = BASE_FLAGS or LayoutParams.FLAG_NOT_TOUCHABLE
        lp.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lp.setCanPlayMoveAnimation(false)
        lp.windowAnimations = 0
        lp.title = "Notchify"
        lp.width = 1
        lp.height = 1
        view = v
        v.setOnApplyWindowInsetsListener { _, insets ->
            val hiddenBars = !insets.isVisible(WindowInsets.Type.statusBars())
            if (hiddenBars != fullscreen) {
                fullscreen = hiddenBars
                render()
            }
            insets
        }
        wm.addView(v, lp)
        attached = true

        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }, RECEIVER_NOT_EXPORTED)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        startTorch()
        syncHeadsUp()
        Hub.listener = this
    }

    /** `adb shell dumpsys activity service com.saif.notchify/.NotchService` */
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        val g = view?.geo
        writer.println("Notchify")
        writer.println("  calibration: offsetX=${prefs.int(Prefs.OFFSET_X)} offsetY=${prefs.int(Prefs.OFFSET_Y)} size=${prefs.int(Prefs.SIZE)}")
        writer.println("  hole: cx=${g?.cx} cy=${g?.cy} w=${g?.holeW} h=${g?.holeH}")
        writer.println("  ranked=${ranked.map { it.key }} open=${open?.key} banner=${banner?.key} peek=$peekKey")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) NotificationService.instance?.trimCaches()
    }

    private fun teardown() {
        if (view == null) return
        if (Hub.listener === this) Hub.listener = null
        runCatching { unregisterReceiver(receiver) }
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        stopTorch()
        syncLocation(false)
        quick.stop()
        quick.glyph.off()
        main.removeCallbacksAndMessages(null)
        if (attached) runCatching { wm.removeViewImmediate(view) }
        attached = false
        view = null
        HeadsUp.suppress(this, false)
        if (instance === this) instance = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land != landscape) {
            landscape = land
            if (!land) view?.setGeometry(Geometry.read(this, prefs))
            syncHeadsUp()
            render()
        }
    }

    /** Hide Android's pop-ups only while the island can show them instead; landscape gets them back. */
    private fun syncHeadsUp() {
        HeadsUp.suppress(this, prefs.on(Prefs.HEADS_UP) && prefs.on(Prefs.NOTIFICATIONS) && !landscape && view != null)
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.HEADS_UP_OWNED -> return
            Prefs.OFFSET_X, Prefs.OFFSET_Y, Prefs.SIZE, Prefs.CALIBRATE, Prefs.SYMMETRIC ->
                view?.setGeometry(Geometry.read(this, prefs))
            Prefs.HEADS_UP, Prefs.NOTIFICATIONS -> syncHeadsUp()
            Prefs.EQUALIZER -> EqualizerView.animated = prefs.on(Prefs.EQUALIZER)
            Prefs.DRIVE_ALWAYS -> navDismissed = false
        }
        render()
    }

    // ---- what to show ---------------------------------------------------------------------------

    private fun allowed(island: Island) = prefs.on(Prefs.keyFor(island.kind))

    private fun find(key: String?) = key?.let { k -> ranked.firstOrNull { it.key == k } }

    private fun render() {
        val v = view ?: return
        ranked = Hub.ranked().filter(::allowed)
        syncLocation(ranked.any { it.kind == Kind.NAVIGATION })
        val hide = !screenOn || landscape
        v.idleTouchable = prefs.on(Prefs.CONTROLS)
        v.setHidden(hide)
        if (hide) return

        autoSeen.retainAll(ranked.mapTo(HashSet()) { it.key })
        ranked.firstOrNull { it.autoExpand && autoSeen.add(it.key) }?.let { incoming ->
            banner?.let {
                queue.addFirst(it)
                main.removeCallbacks(endBanner)
                banner = null
            }
            expandedKey = incoming.key
        }

        val expanded = if (expandedKey == CONTROLS_KEY) controlsIsland() else find(expandedKey)
        if (expanded?.kind == Kind.CONTROLS) quick.start() else quick.stop()
        if (expanded == null) {
            expandedKey = null
            main.removeCallbacks(autoCollapse)
        } else if (!expanded.autoExpand && !main.hasCallbacks(autoCollapse)) {
            main.postDelayed(autoCollapse, AUTO_COLLAPSE_MS)
        }

        if (peekKey != null && find(peekKey) == null) clearPeek(render = false)
        val nav = ranked.firstOrNull { it.kind == Kind.NAVIGATION }
        if (nav == null) navDismissed = false
        val pinnedNav = if (prefs.on(Prefs.DRIVE_ALWAYS) && !navDismissed) nav else null
        val peek = find(peekKey) ?: pinnedNav
        val urgentPeek = peek != null && (peekUrgent || peek === pinnedNav)

        if (expanded == null && !urgentPeek && banner == null) queue.removeFirstOrNull()?.let(::startBanner)

        val shown = if (fullscreen && prefs.on(Prefs.HIDE_FULLSCREEN)) {
            ranked.filter { it.kind == Kind.CALL || it.kind == Kind.ALARM || it.kind == Kind.NAVIGATION }
        } else {
            ranked
        }
        primary = shown.firstOrNull()
        secondary = shown.getOrNull(1)

        val b = banner
        open = when {
            expanded != null -> expanded
            urgentPeek -> peek
            b != null -> null
            else -> peek
        }
        val o = open
        when {
            o != null -> v.show(IslandView.Style.EXPANDED, o, null, ranked.filter { it.key != o.key })
            b != null -> v.show(if (b.compactOnly) IslandView.Style.COMPACT else IslandView.Style.EXPANDED, b, null)
            primary != null -> v.show(IslandView.Style.COMPACT, primary, secondary)
            else -> v.show(IslandView.Style.IDLE, null, null)
        }
    }

    override fun onLiveChanged() = render()

    override fun onBanner(island: Island) {
        if (!screenOn || landscape || !allowed(island)) return
        queue.removeAll { it.key == island.key }
        if (expandedKey != null || (open != null && (peekUrgent || open?.kind == Kind.NAVIGATION))) {
            // Don't cover an island the user opened or a turn coming up; show it afterwards.
            queue.addLast(island)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            return
        }
        // Newest wins: a burst of messages crossfades from one to the next instead of queueing up.
        startBanner(island)
        render()
    }

    override fun onBannerGone(key: String) {
        queue.removeAll { it.key == key }
        if (banner?.key == key) finishBanner()
    }

    override fun onPeek(key: String, millis: Long, urgent: Boolean) {
        if (!screenOn || landscape || expandedKey != null) return
        val island = Hub.ranked().firstOrNull { it.key == key } ?: return
        if (!allowed(island)) return
        if (island.kind == Kind.NAVIGATION) navDismissed = false
        if (island.kind == Kind.NAVIGATION && !prefs.on(Prefs.DRIVE_PEEK) && !prefs.on(Prefs.DRIVE_ALWAYS)) return
        if (urgent) {
            banner?.let {
                queue.addFirst(it)
                main.removeCallbacks(endBanner)
                banner = null
            }
        }
        peekKey = key
        peekUrgent = urgent
        main.removeCallbacks(endPeek)
        main.postDelayed(endPeek, millis)
        render()
    }

    private fun clearPeek(render: Boolean = true) {
        main.removeCallbacks(endPeek)
        if (peekKey == null) return
        peekKey = null
        peekUrgent = false
        if (render) render()
    }

    private fun startBanner(island: Island) {
        banner = island
        main.removeCallbacks(endBanner)
        main.postDelayed(endBanner, island.duration)
    }

    private fun finishBanner() {
        main.removeCallbacks(endBanner)
        banner = null
        render()
    }

    private fun expand(key: String) {
        main.removeCallbacks(autoCollapse)
        clearPeek(render = false)
        expandedKey = key
        view?.haptic()
        render()
    }

    /** Close whatever is open; an open driving HUD stays closed until the next maneuver. */
    private fun collapse() {
        main.removeCallbacks(autoCollapse)
        if (open?.kind == Kind.NAVIGATION) navDismissed = true
        val hadSomething = expandedKey != null || peekKey != null || open != null
        expandedKey = null
        clearPeek(render = false)
        if (hadSomething) render()
    }

    // ---- gestures -------------------------------------------------------------------------------

    /** On a call, the notch is the call's remote: tapping it opens the call controls. */
    private fun tapTarget() = ranked.firstOrNull { it.kind == Kind.CALL && it.info is Info.Call } ?: primary

    override fun onTap() {
        val o = open
        val b = banner
        when {
            o != null -> {
                o.tap?.invoke()
                collapse()
            }
            b != null -> {
                b.tap?.invoke()
                finishBanner()
            }
            else -> expand(tapTarget()?.key ?: CONTROLS_KEY)
        }
    }

    // ---- control centre -------------------------------------------------------------------------

    private val quick by lazy {
        QuickControls(this, { torchOn }, ::setTorch) { intent ->
            runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            collapse()
        }
    }

    private fun controlsIsland(): Island? {
        if (!prefs.on(Prefs.CONTROLS)) return null
        return Island(
            key = CONTROLS_KEY, kind = Kind.CONTROLS, accent = Palette.WHITE, leading = null,
            appLabel = "Controls", topRight = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date()),
            info = Info.Controls(quick),
        )
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        runCatching { cameras().setTorchMode(id, on) }
    }

    override fun onLongPress() {
        if (open == null && banner == null) primary?.tap?.invoke()
    }

    override fun onSwipe(down: Boolean) {
        when {
            down && open == null && banner == null -> expand(tapTarget()?.key ?: CONTROLS_KEY)
            !down && open != null -> collapse()
            !down && banner != null -> finishBanner()
        }
    }

    override fun onOutside() = collapse()

    override fun onBubbleTap() {
        secondary?.let { expand(it.key) }
    }

    override fun onSelect(key: String) = expand(key)

    override fun onAction(action: IslandAction) {
        action.run()
        if (!action.collapse) return
        if (open != null) collapse() else if (banner != null) finishBanner()
    }

    override fun onInteraction() {
        if (expandedKey != null && main.hasCallbacks(autoCollapse)) {
            main.removeCallbacks(autoCollapse)
            main.postDelayed(autoCollapse, AUTO_COLLAPSE_MS)
        }
        if (peekKey != null && main.hasCallbacks(endPeek)) {
            // Touching a peek keeps it open as if the user had opened it.
            peekKey?.let { k ->
                clearPeek(render = false)
                expandedKey = k
            }
        }
        banner?.let {
            main.removeCallbacks(endBanner)
            main.postDelayed(endBanner, it.duration)
        }
    }

    override fun onWindow(left: Float, right: Float, height: Int, touchable: Boolean) {
        val v = view ?: return
        val g = v.geo
        val x = floor(g.cx - left).toInt().coerceAtLeast(0)
        val end = ceil(g.cx + right).toInt().coerceAtMost(g.screenW)
        val w = (end - x).coerceAtLeast(1)
        val flags = if (touchable) BASE_FLAGS else BASE_FLAGS or LayoutParams.FLAG_NOT_TOUCHABLE
        if (lp.x == x && lp.width == w && lp.height == height && lp.flags == flags) return
        lp.x = x
        lp.y = 0
        lp.width = w
        lp.height = height.coerceAtLeast(1)
        lp.flags = flags
        if (attached) wm.updateViewLayout(v, lp)
    }

    // ---- torch ----------------------------------------------------------------------------------

    private var torchId: String? = null
    private var torchMax = 1
    private var torchLevel = 1
    private var torchOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            val turnedOn = enabled && !torchOn
            torchOn = enabled
            if (enabled) torchLevel = runCatching { cameras().getTorchStrengthLevel(cameraId) }.getOrDefault(torchMax)
            publishTorch()
            // Show the beam light up for a moment.
            if (turnedOn) Hub.peek(TORCH_KEY, 1800)
        }

        override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
            if (cameraId != torchId) return
            torchLevel = newStrengthLevel
            publishTorch()
        }
    }

    private fun cameras() = getSystemService(CameraManager::class.java)

    private fun startTorch() {
        val cm = cameras()
        runCatching {
            torchId = cm.cameraIdList.firstOrNull { id ->
                val c = cm.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            torchId?.let { torchMax = cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 }
            if (torchId != null) cm.registerTorchCallback(torchCallback, main)
        }
    }

    private fun stopTorch() {
        runCatching { cameras().unregisterTorchCallback(torchCallback) }
    }

    private fun publishTorch() {
        val id = torchId ?: return
        if (!torchOn) return Hub.remove(TORCH_KEY)
        val cm = cameras()
        val percent = torchLevel * 100 / torchMax.coerceAtLeast(1)
        Hub.put(
            Island(
                key = TORCH_KEY, kind = Kind.TORCH, accent = Palette.YELLOW,
                leading = getDrawable(R.drawable.ic_flashlight), leadingShape = Island.Shape.NONE,
                leadingTint = Palette.YELLOW, leadingAnim = LeadingAnim.TORCH,
                trailing = Trailing.Text(if (torchMax > 1) "$percent%" else "On", Palette.YELLOW),
                appLabel = "Flashlight", topRight = if (torchMax > 1) "$percent%" else null,
                title = "Flashlight", subtitle = if (torchMax > 1) "Slide across the bars to dim" else null,
                art = null,
                info = Info.Torch(torchLevel, torchMax) { level -> runCatching { cm.turnOnTorchWithStrengthLevel(id, level) } },
                actions = listOf(
                    IslandAction(IslandAction.Style.CHIP, label = "Turn off", collapse = true) {
                        runCatching { cm.setTorchMode(id, false) }
                    },
                ),
                sig = "torch|$torchLevel",
            ),
        )
    }

    // ---- speed while navigating -----------------------------------------------------------------

    private var locating = false
    private val locationListener = LocationListener { loc ->
        Telemetry.speed(if (loc.hasSpeed()) loc.speed * 3.6f else null)
    }

    /**
     * The passive provider only hands over fixes some other app (Maps) already asked for, so the
     * readout costs no GPS time of its own. Registered only while a route is active.
     */
    private fun syncLocation(navigating: Boolean) {
        val want = navigating && prefs.on(Prefs.SPEED) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (want == locating) return
        val lm = getSystemService(LocationManager::class.java)
        if (want) {
            try {
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
                locating = true
            } catch (_: SecurityException) {
            }
        } else {
            lm.removeUpdates(locationListener)
            locating = false
            Telemetry.speed(null)
        }
    }

    // ---- system events --------------------------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    expandedKey = null
                    banner = null
                    queue.clear()
                    clearPeek(render = false)
                    main.removeCallbacks(endBanner)
                    main.removeCallbacks(autoCollapse)
                    render()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    render()
                }
                // Plugging in usually wakes the screen a moment after this arrives.
                Intent.ACTION_POWER_CONNECTED -> main.postDelayed({ chargingBanner() }, 400)
                AudioManager.RINGER_MODE_CHANGED_ACTION -> if (!isInitialStickyBroadcast) {
                    ringerBanner(intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1))
                }
            }
        }
    }

    private fun chargingBanner() {
        val level = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        onBanner(
            Island(
                key = "charging", kind = Kind.CHARGING, accent = Palette.GREEN,
                leading = getDrawable(R.drawable.ic_bolt), leadingShape = Island.Shape.NONE, leadingTint = Palette.GREEN,
                trailing = Trailing.Battery(level, Palette.GREEN),
                compactOnly = true, duration = 2800,
            ),
        )
    }

    private fun ringerBanner(mode: Int) {
        val (icon, label, color) = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> Triple(R.drawable.ic_bell_off, "Silent", Palette.ORANGE)
            AudioManager.RINGER_MODE_VIBRATE -> Triple(R.drawable.ic_vibrate, "Vibrate", Palette.WHITE)
            AudioManager.RINGER_MODE_NORMAL -> Triple(R.drawable.ic_bell, "Ring", Palette.WHITE)
            else -> return
        }
        onBanner(
            Island(
                key = "ringer", kind = Kind.RINGER, accent = color,
                leading = getDrawable(icon), leadingShape = Island.Shape.NONE, leadingTint = color,
                trailing = Trailing.Text(label, color),
                compactOnly = true, duration = 2200,
            ),
        )
    }

    companion object {
        private const val BASE_FLAGS = LayoutParams.FLAG_NOT_FOCUSABLE or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            LayoutParams.FLAG_NOT_TOUCH_MODAL or
            LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            LayoutParams.FLAG_HARDWARE_ACCELERATED
        private const val AUTO_COLLAPSE_MS = 8000L
        private const val MAX_QUEUE = 4
        private const val TORCH_KEY = "torch"
        private const val CONTROLS_KEY = com.saif.notchify.ui.CONTROLS_KEY

        /** The running service, for the settings screen's previews. Cleared on teardown. */
        @SuppressLint("StaticFieldLeak")
        var instance: NotchService? = null
            private set
    }
}

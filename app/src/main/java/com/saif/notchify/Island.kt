package com.saif.notchify

import android.graphics.drawable.Drawable

/** What an island represents. Higher priority wins the compact pill; the runner-up becomes the bubble. */
enum class Kind(val priority: Int) {
    NAVIGATION(110), CALL(100), ALARM(95), DELIVERY(70), TIMER(60), TORCH(55), MEDIA(50), LIVE(40),

    // Transient banners: shown once, never ranked.
    NOTIFICATION(0), CHARGING(0), RINGER(0),

    // Opened by tapping the camera while nothing else is showing.
    CONTROLS(0),
}

/** The widget on the right of the camera in the compact pill (and, when [Island.bigTrailing], large in the expanded body). */
sealed interface Trailing {
    data object None : Trailing
    class Text(val text: CharSequence, val color: Int) : Trailing
    class Equalizer(val playing: Boolean, val color: Int) : Trailing
    class Ring(val fraction: Float, val color: Int) : Trailing
    class Chrono(val base: Long, val countDown: Boolean, val color: Int) : Trailing
    class Glyph(val drawable: Drawable, val tint: Int) : Trailing
    class Battery(val level: Int, val color: Int) : Trailing
}

sealed interface Progress {
    class Fixed(val fraction: Float) : Progress
    class Media(
        val position: () -> Long,
        val duration: Long,
        val playing: Boolean,
        val seek: ((Long) -> Unit)?,
    ) : Progress
}

/** Richer, kind-specific content drawn as an infographic in the expanded island. */
sealed interface Info {
    /** Turn-by-turn HUD: maneuver, live distance, approach meter and trip facts. */
    class Nav(
        val distance: CharSequence?,
        val street: CharSequence?,
        /** 0 when the maneuver was first announced, 1 at the turn. */
        val approach: Float?,
        val trip: List<CharSequence>,
        val segments: Segments?,
    ) : Info

    /** A delivery or ride: which step it is on. */
    class Stages(val labels: List<String>, val current: Int) : Info

    /** Android 16 ProgressStyle: coloured segments, milestone points and a tracker. */
    class Segments(
        val parts: List<Pair<Float, Int>>,
        val points: List<Pair<Float, Int>>,
        val progress: Float,
        val tracker: Drawable?,
    ) : Info

    /** Ongoing call: audio route, mic and volume. */
    class Call(val openCallScreen: (() -> Unit)?) : Info

    class Torch(val level: Int, val max: Int, val setLevel: (Int) -> Unit) : Info

    /** The control centre: radios, location, torch, Glyph and sensors in use; live while open. */
    class Controls(val controls: QuickControls) : Info
}

class IslandAction(
    val style: Style,
    val icon: Drawable? = null,
    val label: CharSequence? = null,
    /** Stable identity, so a button can morph (play ↔ pause) instead of being rebuilt. */
    val id: String = label?.toString() ?: style.name,
    /** Collapse the island (or end the banner) after running. */
    val collapse: Boolean = false,
    val run: () -> Unit,
) {
    enum class Style { MEDIA, MEDIA_MAIN, ACCEPT, DECLINE, CHIP }
}

enum class LeadingAnim { NONE, TORCH }

/** Immutable description of one thing the island can show; rebuilt on every source update. */
class Island(
    val key: String,
    val kind: Kind,
    val accent: Int,
    val leading: Drawable?,
    val leadingShape: Shape = Shape.CIRCLE,
    val leadingTint: Int? = null,
    /** Changes when the leading item means something new (next maneuver, next track): it pops in. */
    val leadingKey: String? = null,
    val leadingAnim: LeadingAnim = LeadingAnim.NONE,
    val trailing: Trailing = Trailing.None,
    val appIcon: Drawable? = null,
    val appLabel: CharSequence? = null,
    val topRight: CharSequence? = null,
    val title: CharSequence? = null,
    val subtitle: CharSequence? = null,
    val art: Drawable? = leading,
    val artShape: Shape = Shape.ROUNDED,
    val artTint: Int? = leadingTint,
    val titleLarge: Boolean = false,
    val bigTrailing: Boolean = false,
    val progress: Progress? = null,
    val info: Info? = null,
    val actions: List<IslandAction> = emptyList(),
    val tap: (() -> Unit)? = null,
    /** Opens expanded on arrival (incoming call, ringing alarm). */
    val autoExpand: Boolean = false,
    /** Banner shown as a compact pill rather than expanded (charging, ringer). */
    val compactOnly: Boolean = false,
    val playing: Boolean = false,
    val duration: Long = 5000L,
    val since: Long = System.currentTimeMillis(),
    /** Content fingerprint: an update with the same one is not redrawn. Empty = always redraw. */
    val sig: String = "",
) {
    enum class Shape { CIRCLE, ROUNDED, NONE }
}

/** Main-thread hand-off between producers (notifications, media, torch) and the overlay. */
object Hub {
    interface Listener {
        fun onLiveChanged()
        fun onBanner(island: Island)
        fun onBannerGone(key: String)
        fun onPeek(key: String, millis: Long, urgent: Boolean)
    }

    private val live = LinkedHashMap<String, Island>()

    var listener: Listener? = null
        set(value) {
            field = value
            value?.onLiveChanged()
        }

    fun put(island: Island) {
        val old = live.put(island.key, island)
        if (old == null || island.sig.isEmpty() || old.sig != island.sig) listener?.onLiveChanged()
    }

    fun remove(key: String) {
        if (live.remove(key) != null) listener?.onLiveChanged()
    }

    fun ranked(): List<Island> = live.values.sortedWith(
        compareByDescending<Island> { it.kind.priority }.thenByDescending { it.since },
    )

    fun banner(island: Island) {
        listener?.onBanner(island)
    }

    fun bannerGone(key: String) {
        listener?.onBannerGone(key)
    }

    /** Briefly open a live island by itself: a turn coming up, an order changing stage. */
    fun peek(key: String, millis: Long, urgent: Boolean = false) {
        listener?.onPeek(key, millis, urgent)
    }
}

/** Speed from location fixes Maps is already making; only collected while navigating. */
object Telemetry {
    var speedKmh: Float? = null
        private set
    private val listeners = ArrayList<() -> Unit>()

    fun speed(kmh: Float?) {
        if (kmh == speedKmh) return
        speedKmh = kmh
        listeners.toList().forEach { it() }
    }

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
}

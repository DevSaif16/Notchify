package com.saif.notchify

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.math.roundToInt

/**
 * Turns notifications into islands. Ongoing things (calls, alarms, navigation, deliveries and
 * rides, timers, progress / Live Updates) become persistent islands ranked by [Kind.priority];
 * ordinary alerts become one-off banners. Media comes from [MediaTracker], not from notifications.
 */
class NotificationService : NotificationListenerService() {
    private lateinit var prefs: Prefs
    private var media: MediaTracker? = null
    private val main = Handler(Looper.getMainLooper())
    private val liveKeys = HashSet<String>()
    private val lastRaw = HashMap<String, String>()
    private val shown = HashMap<String, Int>()
    private val icons = HashMap<String, Drawable?>()
    private val labels = HashMap<String, CharSequence>()
    private val ranking = Ranking()

    private class NavState(
        var maneuver: String? = null,
        var startMeters: Float? = null,
        var lastMeters: Float? = null,
        var approachPeeked: Boolean = false,
    )
    private val navStates = HashMap<String, NavState>()

    private class OrderState(var stage: Int = -1, var text: String? = null, var peekedAt: Long = 0L)
    private val orders = HashMap<String, OrderState>()
    private val softExpiry = HashMap<String, Runnable>()

    private val callStarts = HashMap<String, Long>()
    private var callAudioStart: Long? = null
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        callAudioStart = when (mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> callAudioStart ?: System.currentTimeMillis()
            else -> null
        }
    }

    override fun onListenerConnected() {
        instance = this
        prefs = Prefs(this)
        runCatching { getSystemService(AudioManager::class.java).addOnModeChangedListener(mainExecutor, modeListener) }
        media = MediaTracker(this, ComponentName(this, NotificationService::class.java), ::appIcon, ::appLabel).also { it.start() }
        val map = runCatching { currentRanking }.getOrNull()
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it, map, seeding = true) }
    }

    override fun onListenerDisconnected() {
        runCatching { getSystemService(AudioManager::class.java).removeOnModeChangedListener(modeListener) }
        media?.stop()
        media = null
        liveKeys.forEach(Hub::remove)
        liveKeys.clear()
        lastRaw.clear()
        shown.clear()
        main.removeCallbacksAndMessages(null)
        softExpiry.keys.forEach(Hub::remove)
        softExpiry.clear()
        if (instance === this) instance = null
    }

    fun trimCaches() {
        icons.clear()
        labels.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        handle(sbn, rankingMap, seeding = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        val k = sbn.key
        if (liveKeys.remove(k)) Hub.remove(k)
        lastRaw.remove(k)
        shown.remove(k)
        navStates.remove(k)
        orders.remove(k)
        callStarts.remove(k)
        Hub.bannerGone(k)
    }

    /**
     * Apps re-post the same notification constantly (Maps every second, timers, progress). A
     * repost whose visible content is unchanged is dropped here, before any icon is loaded.
     */
    private fun fingerprint(n: Notification): String {
        val ex = n.extras
        return buildString {
            append(n.category).append('|').append(n.flags).append('|')
            append(ex.getCharSequence(Notification.EXTRA_TITLE)).append('|')
            append(ex.getCharSequence(Notification.EXTRA_TEXT)).append('|')
            append(ex.getCharSequence(Notification.EXTRA_SUB_TEXT)).append('|')
            append(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)).append('|')
            append(ex.getCharSequence(SHORT_CRITICAL_TEXT)).append('|')
            append(ex.getInt(Notification.EXTRA_PROGRESS)).append('/').append(ex.getInt(Notification.EXTRA_PROGRESS_MAX)).append('|')
            append(ex.getInt(Notification.EXTRA_CALL_TYPE)).append('|')
            append(n.actions?.joinToString(",") { it.title ?: "" })
            if (ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) append('|').append(n.`when`)
            @Suppress("DEPRECATION")
            append('|').append(ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0)
        }
    }

    private fun handle(sbn: StatusBarNotification, map: RankingMap?, seeding: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (isMedia(n)) return
        val raw = fingerprint(n)
        if (lastRaw[sbn.key] == raw) return
        lastRaw[sbn.key] = raw

        val live = try {
            liveIsland(sbn, raw, seeding)
        } catch (_: RuntimeException) {
            null
        }
        if (live != null) {
            liveKeys += sbn.key
            Hub.put(live)
            afterPut(sbn, live, seeding)
            return
        }
        if (liveKeys.remove(sbn.key)) Hub.remove(sbn.key)
        if (!seeding) banner(sbn, map)
    }

    // ---- classification -------------------------------------------------------------------------

    private fun template(n: Notification) = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()

    private fun isMedia(n: Notification) =
        n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || template(n).endsWith("MediaStyle") ||
            template(n).endsWith("MediaCustomViewStyle")

    private fun isOngoing(n: Notification) =
        n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0

    private fun isPromoted(n: Notification) =
        Build.VERSION.SDK_INT >= 36 && n.flags and Notification.FLAG_PROMOTED_ONGOING != 0

    private fun hasProgress(n: Notification) =
        n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 && !n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

    private fun text(n: Notification) =
        "${n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""} ${n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""}"

    private fun isDelivery(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val active = isOngoing(n) || isPromoted(n)
        if (!active) return false
        if (sbn.packageName in DELIVERY_APPS || sbn.packageName in RIDE_APPS) return true
        return (isPromoted(n) || hasProgress(n)) && ORDER_WORDS.containsMatchIn(text(n))
    }

    private fun liveIsland(sbn: StatusBarNotification, raw: String, seeding: Boolean): Island? {
        val n = sbn.notification
        val ex = n.extras
        val ongoing = isOngoing(n)
        return when {
            n.category == Notification.CATEGORY_CALL || template(n).endsWith("\$CallStyle") -> call(sbn, raw)
            n.category == Notification.CATEGORY_ALARM && n.fullScreenIntent != null -> alarm(sbn, raw)
            n.category == Notification.CATEGORY_NAVIGATION || (ongoing && sbn.packageName in NAV_APPS) -> navigation(sbn, raw)
            isDelivery(sbn) -> delivery(sbn, raw, sbn.key)
            ongoing && (ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ||
                n.category == Notification.CATEGORY_STOPWATCH) -> timer(sbn, raw)
            ongoing && (hasProgress(n) || isPromoted(n)) -> liveUpdate(sbn, raw)
            else -> null
        }
    }

    /** Peeks: a new maneuver or a turn coming up opens the HUD; an order changing stage pops open. */
    private fun afterPut(sbn: StatusBarNotification, island: Island, seeding: Boolean) {
        if (seeding) return
        when (island.kind) {
            Kind.NAVIGATION -> {
                val st = navStates[sbn.key] ?: return
                val nav = island.info as? Info.Nav ?: return
                if (st.maneuver != null && justChanged.remove(sbn.key)) {
                    Hub.peek(sbn.key, 6000, urgent = true)
                }
                val meters = st.lastMeters
                if (meters != null && meters <= APPROACH_METERS && !st.approachPeeked && nav.distance != null) {
                    st.approachPeeked = true
                    // Open until the turn is taken (the next maneuver replaces this peek).
                    Hub.peek(sbn.key, 150_000, urgent = true)
                }
            }
            Kind.DELIVERY -> {
                val st = orders[island.key] ?: return
                if (justChanged.remove(island.key)) {
                    val now = System.currentTimeMillis()
                    if (now - st.peekedAt > 20_000 || stageJumped.remove(island.key)) {
                        st.peekedAt = now
                        Hub.peek(island.key, 5000)
                    }
                }
            }
            else -> Unit
        }
    }

    private val justChanged = HashSet<String>()
    private val stageJumped = HashSet<String>()

    // ---- builders -------------------------------------------------------------------------------

    private fun call(sbn: StatusBarNotification, raw: String): Island {
        val n = sbn.notification
        val ex = n.extras
        val type = ex.getInt(Notification.EXTRA_CALL_TYPE, 0)
        val person = ex.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        val name = person?.name ?: ex.getCharSequence(Notification.EXTRA_TITLE) ?: "Call"
        val avatar = (person?.icon ?: n.getLargeIcon())?.loadSmall(this)
        val answer = ex.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
            ?: actionMatching(n, ANSWER_WORDS)
        val decline = ex.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
            ?: ex.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
            ?: actionMatching(n, DECLINE_WORDS)
        val incoming = type == Notification.CallStyle.CALL_TYPE_INCOMING ||
            (type == 0 && answer != null && !ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val start = if (incoming) null else callStart(sbn)
        val phone = getDrawable(R.drawable.ic_call)!!
        // Ringing: decline / answer as big buttons. Ongoing: hang-up lives inside the call controls.
        val actions = if (!incoming) emptyList() else buildList {
            decline?.let { pi ->
                add(IslandAction(IslandAction.Style.DECLINE, icon = getDrawable(R.drawable.ic_call_end), id = "end") { pi.sendFromIsland(this@NotificationService) })
            }
            answer?.let { pi ->
                add(IslandAction(IslandAction.Style.ACCEPT, icon = phone, id = "answer") { pi.sendFromIsland(this@NotificationService) })
            }
        }
        val hangUp = decline?.let { pi -> { pi.sendFromIsland(this@NotificationService) } }
        val tap = tapFor(sbn)
        return Island(
            key = sbn.key, kind = Kind.CALL, accent = Palette.GREEN,
            leading = avatar ?: phone,
            leadingShape = if (avatar != null) Island.Shape.CIRCLE else Island.Shape.NONE,
            leadingTint = if (avatar != null) null else Palette.GREEN,
            trailing = if (start == null) Trailing.Glyph(phone, Palette.GREEN) else Trailing.Chrono(start, false, Palette.GREEN),
            appIcon = appIcon(sbn.packageName), appLabel = appLabel(sbn.packageName),
            topRight = if (incoming) "Incoming call" else "On call",
            title = name, subtitle = ex.getCharSequence(Notification.EXTRA_TEXT),
            art = avatar ?: phone, artShape = if (avatar != null) Island.Shape.CIRCLE else Island.Shape.NONE,
            artTint = if (avatar != null) null else Palette.GREEN,
            bigTrailing = start != null,
            info = if (incoming) null else Info.Call(tap, hangUp),
            actions = actions, tap = tap, autoExpand = incoming,
            since = start ?: System.currentTimeMillis(), sig = raw,
        )
    }

    /**
     * When the call really started. The system dialer stamps its notification with it; WhatsApp and
     * other VoIP apps do not (WhatsApp's `when` is a fixed time of day), so for them the start is
     * when the phone's audio switched into call mode, or when the call notification first turned
     * ongoing, whichever came first.
     */
    private fun callStart(sbn: StatusBarNotification): Long = callStarts.getOrPut(sbn.key) {
        val now = System.currentTimeMillis()
        val stamped = sbn.notification.`when`
        if (sbn.packageName in TRUSTED_DIALERS && stamped in (now - 12 * 3600_000L)..(now + 60_000L)) {
            stamped
        } else {
            minOf(sbn.postTime.takeIf { it in 1..now } ?: now, callAudioStart ?: now)
        }
    }

    private fun alarm(sbn: StatusBarNotification, raw: String): Island {
        val n = sbn.notification
        val icon = getDrawable(R.drawable.ic_alarm)!!
        return Island(
            key = sbn.key, kind = Kind.ALARM, accent = Palette.ORANGE,
            leading = icon, leadingShape = Island.Shape.NONE, leadingTint = Palette.ORANGE,
            trailing = Trailing.Text(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.take(8) ?: "", Palette.ORANGE),
            appIcon = appIcon(sbn.packageName), appLabel = appLabel(sbn.packageName),
            title = n.extras.getCharSequence(Notification.EXTRA_TITLE),
            subtitle = n.extras.getCharSequence(Notification.EXTRA_TEXT),
            actions = chips(n), tap = tapFor(sbn), autoExpand = true, sig = raw,
        )
    }

    private fun navigation(sbn: StatusBarNotification, raw: String): Island {
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)
        val short = ex.getCharSequence(SHORT_CRITICAL_TEXT)
        val match = Distance.find(short) ?: Distance.find(title) ?: Distance.find(text)
        val meters = match?.let(Distance::meters)
        // The instruction / street: whichever line is more than just the distance.
        val street = listOf(title, text).firstNotNullOfOrNull { line ->
            line?.toString()?.replace(Distance.PATTERN, "")?.trim(' ', '·', '•', '-', ',')?.takeIf { it.isNotBlank() }
        }
        val trip = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            .split('·', '•', '|').map { it.trim() }.filter { it.isNotEmpty() }

        val st = navStates.getOrPut(sbn.key) { NavState() }
        val maneuver = street ?: title?.toString()
        if (maneuver != st.maneuver) {
            st.maneuver = maneuver
            st.startMeters = meters
            st.approachPeeked = false
            justChanged += sbn.key
        } else if (meters != null && (st.startMeters == null || meters > st.startMeters!!)) {
            st.startMeters = meters
        }
        st.lastMeters = meters
        val start = st.startMeters
        val approach = if (meters != null && start != null && start > 0f) (1f - meters / start).coerceIn(0f, 1f) else null

        val glyph = n.getLargeIcon()?.loadSmall(this) ?: n.smallIcon?.loadDrawable(this) ?: getDrawable(R.drawable.ic_navigation)
        val accent = Palette.usable(n.color, Palette.GREEN)
        val segments = segmentsOf(n)
        return Island(
            key = sbn.key, kind = Kind.NAVIGATION, accent = accent,
            leading = glyph, leadingShape = Island.Shape.NONE, leadingTint = Palette.WHITE, leadingKey = maneuver,
            trailing = match?.value?.let { Trailing.Text(it, Palette.WHITE) } ?: Trailing.None,
            appIcon = appIcon(sbn.packageName), appLabel = appLabel(sbn.packageName),
            topRight = trip.firstOrNull(),
            title = match?.value ?: title, subtitle = street, art = glyph, artShape = Island.Shape.NONE,
            artTint = Palette.WHITE,
            info = Info.Nav(match?.value, street, approach, trip, segments),
            actions = chips(n, max = 2), tap = tapFor(sbn), sig = raw,
        )
    }

    private fun delivery(sbn: StatusBarNotification, raw: String, key: String, soft: Boolean = false): Island {
        val n = sbn.notification
        val ex = n.extras
        val ride = sbn.packageName in RIDE_APPS
        val title = ex.getCharSequence(Notification.EXTRA_TITLE) ?: n.tickerText ?: if (ride) "Ride in progress" else "Order in progress"
        val body = ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT)
        val all = "$title $body"
        val stage = stageOf(all, ride)
        val eta = ex.getCharSequence(SHORT_CRITICAL_TEXT)?.toString() ?: ETA.find(all)?.let { "${it.groupValues[1]} min" }
        val labels = if (ride) RIDE_LABELS else DELIVERY_LABELS
        val accent = Palette.usable(n.color, Palette.GREEN)
        val app = appIcon(sbn.packageName)
        val segments = segmentsOf(n)
        val fraction = if (hasProgress(n)) ex.getInt(Notification.EXTRA_PROGRESS).toFloat() / ex.getInt(Notification.EXTRA_PROGRESS_MAX) else null

        val st = orders.getOrPut(key) { OrderState() }
        if (st.text != all) {
            if (st.text != null) justChanged += key
            if (stage > st.stage && st.stage >= 0) stageJumped += key
            st.text = all
            if (stage >= 0) st.stage = stage
        }
        return Island(
            key = key, kind = Kind.DELIVERY, accent = accent,
            leading = app, leadingShape = Island.Shape.ROUNDED, leadingKey = stage.toString(),
            trailing = when {
                eta != null -> Trailing.Text(eta, accent)
                stage >= 0 -> Trailing.Ring(stage / (labels.size - 1f), accent)
                fraction != null -> Trailing.Ring(fraction, accent)
                else -> Trailing.None
            },
            appIcon = app, appLabel = appLabel(sbn.packageName),
            topRight = eta?.let { if (ride) "Arrives in $it" else "ETA $it" },
            title = title, subtitle = body,
            art = n.getLargeIcon()?.loadSmall(this) ?: app,
            info = segments ?: if (stage >= 0) Info.Stages(labels, stage) else null,
            progress = if (segments == null && stage < 0 && fraction != null) Progress.Fixed(fraction) else null,
            actions = if (soft) emptyList() else chips(n), tap = tapFor(sbn, cancel = !soft), sig = raw,
        )
    }

    private fun stageOf(text: String, ride: Boolean): Int {
        val t = text.lowercase()
        val table = if (ride) RIDE_STAGES else DELIVERY_STAGES
        for (i in table.indices.reversed()) if (table[i].any { it in t }) return i
        return -1
    }

    /** Android 16 ProgressStyle (Live Updates): coloured segments, milestone points and a tracker. */
    private fun segmentsOf(n: Notification): Info.Segments? {
        if (Build.VERSION.SDK_INT < 36 || !template(n).endsWith("\$ProgressStyle")) return null
        val ps = runCatching { Notification.Builder.recoverBuilder(this, n).style as? Notification.ProgressStyle }.getOrNull() ?: return null
        val total = ps.progressSegments.sumOf { it.length }.takeIf { it > 0 } ?: ps.progressMax.takeIf { it > 0 } ?: return null
        return Info.Segments(
            parts = ps.progressSegments.map { it.length.toFloat() / total to it.color },
            points = ps.progressPoints.map { it.position.toFloat() / total to it.color },
            progress = ps.progress.toFloat() / total,
            tracker = ps.progressTrackerIcon?.loadSmall(this, 96),
        )
    }

    private fun timer(sbn: StatusBarNotification, raw: String): Island {
        val n = sbn.notification
        val ex = n.extras
        val accent = Palette.ORANGE
        val icon = n.smallIcon?.loadDrawable(this) ?: getDrawable(R.drawable.ic_timer)
        val trailing = if (ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
            Trailing.Chrono(n.`when`, ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN), accent)
        } else {
            Trailing.Text(ex.getCharSequence(Notification.EXTRA_TEXT)?.take(8) ?: "", accent)
        }
        return Island(
            key = sbn.key, kind = Kind.TIMER, accent = accent,
            leading = icon, leadingShape = Island.Shape.NONE, leadingTint = accent,
            trailing = trailing,
            appIcon = appIcon(sbn.packageName), appLabel = appLabel(sbn.packageName),
            title = ex.getCharSequence(Notification.EXTRA_TITLE), subtitle = ex.getCharSequence(Notification.EXTRA_TEXT),
            bigTrailing = true, actions = chips(n), tap = tapFor(sbn), sig = raw,
        )
    }

    private fun liveUpdate(sbn: StatusBarNotification, raw: String): Island {
        val n = sbn.notification
        val ex = n.extras
        val fraction = if (hasProgress(n)) ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / ex.getInt(Notification.EXTRA_PROGRESS_MAX) else null
        val accent = Palette.usable(n.color, Palette.BLUE)
        val short = ex.getCharSequence(SHORT_CRITICAL_TEXT)
        val small = n.smallIcon?.loadDrawable(this)
        val large = n.getLargeIcon()?.loadSmall(this)
        val segments = segmentsOf(n)
        return Island(
            key = sbn.key, kind = Kind.LIVE, accent = accent,
            leading = small, leadingShape = Island.Shape.NONE, leadingTint = accent,
            trailing = when {
                fraction != null -> Trailing.Ring(fraction, accent)
                short != null -> Trailing.Text(short, accent)
                else -> Trailing.None
            },
            appIcon = appIcon(sbn.packageName), appLabel = appLabel(sbn.packageName),
            topRight = fraction?.let { "${(it * 100).roundToInt()}%" } ?: short,
            title = ex.getCharSequence(Notification.EXTRA_TITLE), subtitle = ex.getCharSequence(Notification.EXTRA_TEXT),
            art = large ?: small, artShape = if (large != null) Island.Shape.ROUNDED else Island.Shape.NONE,
            artTint = if (large != null) null else accent,
            info = segments,
            progress = if (segments == null) fraction?.let { Progress.Fixed(it) } else null,
            actions = chips(n), tap = tapFor(sbn), sig = raw,
        )
    }

    /**
     * One banner per new alert. Repeats, silent updates, low-importance channels, Do Not Disturb
     * and group summaries are skipped, so the island never shows the same thing twice.
     */
    private fun banner(sbn: StatusBarNotification, map: RankingMap?) {
        val n = sbn.notification
        if (isOngoing(n) || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        softOrder(sbn)
        if (!prefs.on(Prefs.NOTIFICATIONS)) return
        if (map != null && map.getRanking(sbn.key, ranking)) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            if (!ranking.matchesInterruptionFilter() || ranking.isSuspended) return
        }

        val ex = n.extras
        val message = lastMessage(ex)
        val conversation = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
        var title = message?.sender ?: ex.getCharSequence(Notification.EXTRA_TITLE)
        if (message?.sender != null && conversation != null && conversation.toString() != message.sender.toString()) {
            title = "${message.sender} · $conversation"
        }
        val text = message?.text ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val hash = "$title\u0000$text".hashCode()
        if (shown[sbn.key] == hash) return
        val repeat = shown.containsKey(sbn.key) && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        shown[sbn.key] = hash
        if (repeat) return

        // Like iOS "Show previews: when unlocked": a locked phone shows who, not what.
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val redact = locked && !prefs.on(Prefs.LOCK_PREVIEWS) && n.visibility != Notification.VISIBILITY_PUBLIC
        if (locked && n.visibility == Notification.VISIBILITY_SECRET) return

        val app = appIcon(sbn.packageName)
        val label = appLabel(sbn.packageName)
        val avatar = (message?.icon ?: n.getLargeIcon())?.loadSmall(this)
        Hub.banner(
            Island(
                key = sbn.key, kind = Kind.NOTIFICATION, accent = Palette.usable(n.color, Palette.WHITE),
                leading = avatar ?: app, leadingShape = Island.Shape.CIRCLE,
                appIcon = app, appLabel = label, topRight = "now",
                title = if (redact) label else title,
                subtitle = if (redact) "New notification" else text,
                art = avatar ?: app, artShape = if (avatar != null) Island.Shape.CIRCLE else Island.Shape.ROUNDED,
                actions = if (redact) emptyList() else chips(n, max = 3),
                tap = tapFor(sbn), duration = 5000,
            ),
        )
    }

    /**
     * Delivery apps that only send status alerts ("Your order is out for delivery") still get a
     * live island: it follows each alert and stays until the order is delivered.
     */
    private fun softOrder(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in DELIVERY_APPS && pkg !in RIDE_APPS) return
        if (liveKeys.any { it.contains("|$pkg|") }) return
        val stage = stageOf(text(sbn.notification) + " " + (sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ""), pkg in RIDE_APPS)
        if (stage < 0) return
        val key = "order:$pkg"
        val island = delivery(sbn, fingerprint(sbn.notification), key, soft = true)
        Hub.put(island)
        afterPut(sbn, island, seeding = false)
        softExpiry.remove(key)?.let(main::removeCallbacks)
        val last = if (pkg in RIDE_APPS) RIDE_LABELS.size - 1 else DELIVERY_LABELS.size - 1
        val expire = Runnable {
            softExpiry.remove(key)
            orders.remove(key)
            Hub.remove(key)
        }
        softExpiry[key] = expire
        main.postDelayed(expire, if (stage == last) 10 * 60_000L else 90 * 60_000L)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private class Message(val sender: CharSequence?, val text: CharSequence?, val icon: android.graphics.drawable.Icon?)

    @Suppress("DEPRECATION")
    private fun lastMessage(ex: Bundle): Message? {
        val last = ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle ?: return null
        val person = last.getParcelable("sender_person") as? Person
        return Message(person?.name ?: last.getCharSequence("sender"), last.getCharSequence("text"), person?.icon)
    }

    private fun tapFor(sbn: StatusBarNotification, cancel: Boolean = true): (() -> Unit)? {
        val pi = sbn.notification.contentIntent ?: return null
        val autoCancel = cancel && sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0
        val key = sbn.key
        return {
            pi.sendFromIsland(this)
            if (autoCancel) runCatching { cancelNotification(key) }
        }
    }

    /** The notification's own buttons, minus any that need typed input (reply). */
    private fun chips(n: Notification, max: Int = 3): List<IslandAction> =
        n.actions.orEmpty().asSequence()
            .filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(max)
            .map { a -> IslandAction(IslandAction.Style.CHIP, label = a.title, collapse = true) { a.actionIntent.sendFromIsland(this) } }
            .toList()

    private fun actionMatching(n: Notification, words: List<String>): PendingIntent? =
        n.actions?.firstOrNull { a -> words.any { a.title?.toString()?.contains(it, ignoreCase = true) == true } }?.actionIntent

    private fun appIcon(pkg: String): Drawable? = icons.getOrPut(pkg) {
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    private fun appLabel(pkg: String): CharSequence = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)) }.getOrDefault(pkg)
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: NotificationService? = null
            private set

        private const val SHORT_CRITICAL_TEXT = "android.shortCriticalText"
        private const val APPROACH_METERS = 350f
        private val ETA = Regex("""(\d+)\s?(?:-\s?\d+\s?)?(?:min|mins|minutes)\b""", RegexOption.IGNORE_CASE)
        private val ORDER_WORDS = Regex(
            """\b(order|delivery|deliver|courier|parcel|package|shipment|rider|driver|pickup|picked up|out for delivery|arriving|eta)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val ANSWER_WORDS = listOf("answer", "accept", "pick up")
        private val DECLINE_WORDS = listOf("decline", "reject", "hang up", "end call", "dismiss")
        private val TRUSTED_DIALERS = setOf(
            "com.google.android.dialer", "com.android.dialer", "com.android.server.telecom", "com.nothing.dialer",
        )
        private val NAV_APPS = setOf(
            "com.google.android.apps.maps", "com.google.android.apps.mapslite", "com.waze",
            "com.here.app.maps", "net.osmand", "net.osmand.plus", "com.sygic.aura",
            "ru.yandex.yandexnavi", "com.citymapper.app.release", "com.mappls.app.navigation.events",
        )
        private val DELIVERY_APPS = setOf(
            "com.application.zomato", "in.swiggy.android", "com.grofers.customerapp", "com.zeptoconsumerapp",
            "com.dunzo.user", "com.bigbasket.mobileapp", "com.flipkart.android", "com.flipkart.minutes",
            "in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping", "com.myntra.android",
            "com.ubercab.eats", "com.dd.doordash", "com.grubhub.android", "com.instacart.client",
            "com.deliveroo.orderapp", "com.global.foodpanda.android", "com.talabat", "com.done.faasos",
            "com.Dominos", "com.mcdonalds.mobileapp", "com.jio.mart", "com.blinkit.consumer", "com.swiggy.instamart",
        )
        private val RIDE_APPS = setOf(
            "com.ubercab", "com.olacabs.customer", "com.rapido.passenger", "com.lyft.android",
            "ee.mtakso.client", "com.bolt.client", "com.blusmart.rider", "com.indriver.android",
        )
        private val DELIVERY_LABELS = listOf("Placed", "Preparing", "On the way", "Arriving", "Delivered")
        private val RIDE_LABELS = listOf("Booked", "Coming", "Arrived", "On trip", "Done")
        private val DELIVERY_STAGES = listOf(
            listOf("placed", "confirmed", "accepted", "order received", "received your order"),
            listOf("preparing", "being prepared", "packing", "packed", "cooking", "ready for pickup", "is ready"),
            listOf("picked up", "out for delivery", "on the way", "on its way", "dispatched", "shipped", "in transit", "rider is", "partner is"),
            listOf("arriving", "nearby", "almost there", "reached your", "at your door", "few minutes away", "mins away", "min away"),
            listOf("delivered", "completed", "enjoy your"),
        )
        private val RIDE_STAGES = listOf(
            listOf("booked", "searching", "finding", "confirmed"),
            listOf("on the way", "is coming", "arriving", "assigned", "min away", "mins away"),
            listOf("arrived", "has arrived", "is here", "waiting"),
            listOf("trip started", "on trip", "ride started", "heading to", "on the way to", "enjoy your ride"),
            listOf("completed", "reached your destination", "rate your", "trip ended"),
        )
    }
}

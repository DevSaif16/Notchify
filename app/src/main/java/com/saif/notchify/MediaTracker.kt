package com.saif.notchify

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Follows active media sessions through callbacks (no polling) and publishes one "media" island:
 * the session that is playing, or the last one for a minute after it pauses. Players report
 * position every second or so; those callbacks change nothing visible, so the island's
 * fingerprint skips them and nothing is redrawn.
 */
class MediaTracker(
    private val context: Context,
    private val listener: ComponentName,
    private val appIcon: (String) -> Drawable?,
    private val appLabel: (String) -> CharSequence,
) : MediaSessionManager.OnActiveSessionsChangedListener {

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private var current: MediaSession.Token? = null
    private var pausedAt = 0L
    private var since = System.currentTimeMillis()
    private val expire = Runnable { refresh() }

    private inner class Tracked(val controller: MediaController) : MediaController.Callback() {
        var artKey: String? = null
        var art: Drawable? = null
        var accent = Palette.WHITE

        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()

        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()

        override fun onSessionDestroyed() {
            tracked.remove(controller.sessionToken)?.let { controller.unregisterCallback(it) }
            refresh()
        }
    }

    fun start() {
        try {
            sessions.addOnActiveSessionsChangedListener(this, listener, main)
            onActiveSessionsChanged(sessions.getActiveSessions(listener))
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(this) }
        tracked.values.forEach { it.controller.unregisterCallback(it) }
        tracked.clear()
        main.removeCallbacks(expire)
        Hub.remove(KEY)
    }

    override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        val list = controllers.orEmpty()
        val tokens = list.mapTo(HashSet()) { it.sessionToken }
        tracked.keys.filter { it !in tokens }.forEach { token ->
            tracked.remove(token)?.let { it.controller.unregisterCallback(it) }
        }
        for (c in list) {
            if (c.sessionToken in tracked) continue
            val t = Tracked(c)
            c.registerCallback(t, main)
            tracked[c.sessionToken] = t
        }
        refresh()
    }

    private fun MediaController.playing() = when (playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    private fun MediaController.title() = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)
        ?: metadata?.description?.title

    private fun refresh() {
        main.removeCallbacks(expire)
        val playing = tracked.values.firstOrNull { it.controller.playing() && it.controller.title() != null }
        if (playing != null) {
            if (current != playing.controller.sessionToken || pausedAt != 0L) since = System.currentTimeMillis()
            current = playing.controller.sessionToken
            pausedAt = 0L
            Hub.put(build(playing, true))
            return
        }
        val last = tracked[current]
        if (last == null || last.controller.title() == null) return Hub.remove(KEY)
        val now = SystemClock.elapsedRealtime()
        if (pausedAt == 0L) pausedAt = now
        val left = PAUSED_GRACE_MS - (now - pausedAt)
        if (left <= 0) return Hub.remove(KEY)
        main.postDelayed(expire, left)
        Hub.put(build(last, false))
    }

    private fun build(t: Tracked, playing: Boolean): Island {
        val c = t.controller
        val md = c.metadata
        val title = c.title()
        val artist = md?.getText(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md?.description?.subtitle
        loadArt(t, md, title)
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val supported = c.playbackState?.actions ?: 0L
        fun has(action: Long) = supported == 0L || supported and action != 0L
        val controls = c.transportControls
        val icon = appIcon(c.packageName)
        val art = t.art ?: icon

        val actions = buildList {
            if (has(PlaybackState.ACTION_SKIP_TO_PREVIOUS)) {
                add(IslandAction(IslandAction.Style.MEDIA, icon = context.getDrawable(R.drawable.ic_skip_prev), id = "prev") { controls.skipToPrevious() })
            }
            add(
                IslandAction(
                    IslandAction.Style.MEDIA_MAIN,
                    icon = context.getDrawable(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    id = if (playing) "pause" else "play",
                ) { if (playing) controls.pause() else controls.play() },
            )
            if (has(PlaybackState.ACTION_SKIP_TO_NEXT)) {
                add(IslandAction(IslandAction.Style.MEDIA, icon = context.getDrawable(R.drawable.ic_skip_next), id = "next") { controls.skipToNext() })
            }
        }
        val canSeek = supported and PlaybackState.ACTION_SEEK_TO != 0L

        return Island(
            key = KEY, kind = Kind.MEDIA, accent = t.accent,
            leading = art, leadingShape = Island.Shape.ROUNDED, leadingKey = t.artKey,
            trailing = Trailing.Equalizer(playing, t.accent),
            appIcon = icon, appLabel = appLabel(c.packageName),
            title = title, subtitle = artist, art = art, artShape = Island.Shape.ROUNDED,
            bigTrailing = true, playing = playing,
            progress = if (duration > 0) {
                Progress.Media({ position(c, duration) }, duration, playing, if (canSeek) { p -> controls.seekTo(p) } else null)
            } else {
                null
            },
            actions = actions,
            tap = c.sessionActivity?.let { pi -> { pi.sendFromIsland(context) } },
            since = since,
            sig = "${c.packageName}|${t.artKey}|$playing|$duration|$supported|" +
                (if (playing) "" else (c.playbackState?.position ?: 0L) / 1000),
        )
    }

    /** Decodes and shrinks the cover once per track rather than on every state change. */
    private fun loadArt(t: Tracked, md: MediaMetadata?, title: CharSequence?) {
        val bitmap = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.description?.iconBitmap
        val key = "$title|${md?.getText(MediaMetadata.METADATA_KEY_ARTIST)}|${bitmap?.width}x${bitmap?.height}"
        if (key == t.artKey) return
        t.artKey = key
        if (bitmap == null) {
            t.art = null
            t.accent = Palette.WHITE
            return
        }
        val small = Palette.shrink(bitmap)
        t.art = BitmapDrawable(context.resources, small)
        t.accent = Palette.accent(small)
    }

    private fun position(c: MediaController, duration: Long): Long {
        val s = c.playbackState ?: return 0L
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING) {
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        return p.coerceIn(0L, duration)
    }

    companion object {
        const val KEY = "media"
        private const val PAUSED_GRACE_MS = 60_000L
    }
}

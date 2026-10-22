package com.saif.notchify

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Palette {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val TEXT2 = 0xFF98989F.toInt()
    const val CHIP = 0x29FFFFFF
    const val NOTHING_RED = 0xFFD71921.toInt()

    /** An app's notification colour, lifted until it reads on black; [fallback] when unset. */
    fun usable(color: Int, fallback: Int): Int {
        if (color == 0 || Color.alpha(color) < 0x80) return fallback
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.15f) return fallback
        hsv[2] = max(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }

    /** The most vivid colour in a piece of album art, or white for greyscale art. */
    fun accent(art: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(art, 12, 12, true)
        val hsv = FloatArray(3)
        var best = 0
        var bestScore = 0f
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val px = small.getPixel(x, y)
            Color.colorToHSV(px, hsv)
            val score = hsv[1] * (if (hsv[2] < 0.25f) 0.2f else hsv[2])
            if (score > bestScore) {
                bestScore = score
                best = px
            }
        }
        if (small !== art) small.recycle()
        if (bestScore < 0.12f) return 0xFFEBEBF5.toInt()
        Color.colorToHSV(best, hsv)
        hsv[1] = min(hsv[1], 0.8f)
        hsv[2] = max(hsv[2], 0.88f)
        return Color.HSVToColor(hsv)
    }

    /** Downscales art once so the island never holds a full-resolution bitmap. */
    fun shrink(art: Bitmap, maxPx: Int = 192): Bitmap {
        val soft = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val scale = maxPx.toFloat() / max(soft.width, soft.height)
        if (scale >= 1f) return soft
        return Bitmap.createScaledBitmap(soft, (soft.width * scale).roundToInt(), (soft.height * scale).roundToInt(), true)
    }
}

/** Loads another app's icon, shrinking large bitmaps (avatars, maneuver art) to what the island draws. */
fun Icon.loadSmall(context: Context, maxPx: Int = 160): Drawable? {
    val d = loadDrawable(context) ?: return null
    val bitmap = (d as? BitmapDrawable)?.bitmap ?: return d
    if (max(bitmap.width, bitmap.height) <= maxPx) return d
    return BitmapDrawable(context.resources, Palette.shrink(bitmap, maxPx))
}

/** Distances as Maps and friends write them: "200 m", "1.2 km", "0.3 mi", "500 ft". */
object Distance {
    val PATTERN = Regex("""(\d+(?:[.,]\d+)?)\s?(km|mi|ft|yd|m)\b""", RegexOption.IGNORE_CASE)

    fun find(text: CharSequence?): MatchResult? = text?.let { PATTERN.find(it) }

    fun meters(match: MatchResult): Float? {
        val value = match.groupValues[1].replace(',', '.').toFloatOrNull() ?: return null
        return value * when (match.groupValues[2].lowercase()) {
            "km" -> 1000f
            "mi" -> 1609.34f
            "ft" -> 0.3048f
            "yd" -> 0.9144f
            else -> 1f
        }
    }
}

/**
 * Sends another app's PendingIntent from the island. A bound accessibility service may start
 * activities from the background, but since Android 14 the sender has to opt in explicitly.
 */
fun PendingIntent.sendFromIsland(context: Context) {
    val options = ActivityOptions.makeBasic()
    if (Build.VERSION.SDK_INT >= 36) {
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
    } else if (Build.VERSION.SDK_INT >= 34) {
        @Suppress("DEPRECATION")
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }
    try {
        send(context, 0, null, null, null, null, options.toBundle())
    } catch (_: PendingIntent.CanceledException) {
    }
}

/**
 * Android's heads-up pop-ups duplicate everything the island shows. This flips the global
 * setting off while the island is visible and restores it only if Notchify was the one that
 * turned it off. Needs the adb grant in [GRANT_COMMAND].
 */
object HeadsUp {
    private const val KEY = "heads_up_notifications_enabled"
    const val GRANT_COMMAND = "adb shell pm grant com.saif.notchify android.permission.WRITE_SECURE_SETTINGS"

    fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun suppress(context: Context, suppress: Boolean) {
        if (!granted(context)) return
        val resolver = context.contentResolver
        val prefs = Prefs(context)
        try {
            if (suppress) {
                if (Settings.Global.getInt(resolver, KEY, 1) != 0) {
                    Settings.Global.putInt(resolver, KEY, 0)
                    prefs.set(Prefs.HEADS_UP_OWNED, true)
                }
            } else if (prefs.on(Prefs.HEADS_UP_OWNED)) {
                Settings.Global.putInt(resolver, KEY, 1)
                prefs.set(Prefs.HEADS_UP_OWNED, false)
            }
        } catch (_: SecurityException) {
        }
    }
}

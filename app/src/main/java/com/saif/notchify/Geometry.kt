package com.saif.notchify

import android.content.Context
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the camera hole is, in screen pixels, read from the display's own cutout path so the
 * idle island sits exactly on the lens. On the Nothing Phone (4a) Pro that is a 70.6 px circle
 * centred at (630.5, 85.4); the system's bounding rect is stretched to the top edge, so only the
 * path gives the real hole.
 */
class Geometry private constructor(
    val cx: Float,
    val cy: Float,
    val holeW: Float,
    val holeH: Float,
    val screenW: Int,
    val screenH: Int,
    density: Float,
    /** Compact content to the right of the camera only, so the notification icons on the left stay visible. */
    val rightWeighted: Boolean,
) {
    val idleW = holeW + density
    val idleH = holeH + density
    val compactH = max(holeH + 12f * density, 34f * density)
    val halfLimit = min(cx, screenW - cx)
    val expandedW = min(screenW - 20f * density, 2f * halfLimit - 20f * density)
    val expandedR = 38f * density

    /** In the right-weighted pill the camera sits in the centre of the pill's left round end. */
    val capLeft = compactH / 2f

    companion object {
        fun read(context: Context, prefs: Prefs): Geometry {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val density = context.resources.displayMetrics.density

            val hole = RectF()
            display.cutout?.let { cutout ->
                val path = cutout.cutoutPath
                if (path != null && !path.isEmpty) {
                    @Suppress("DEPRECATION")
                    path.computeBounds(hole, true)
                } else if (!cutout.boundingRectTop.isEmpty) {
                    hole.set(cutout.boundingRectTop)
                }
            }
            if (hole.isEmpty || hole.top > size.y / 4f) {
                // No top cutout: float a small pill where a status-bar camera would be.
                hole.set(size.x / 2f - 12f * density, 10f * density, size.x / 2f + 12f * density, 34f * density)
            }

            val adjust = prefs.int(Prefs.SIZE).toFloat()
            return Geometry(
                cx = ((hole.centerX() + prefs.int(Prefs.OFFSET_X)) * 2f).roundToInt() / 2f,
                cy = hole.centerY() + prefs.int(Prefs.OFFSET_Y),
                holeW = max(8f, hole.width() + adjust),
                holeH = max(8f, hole.height() + adjust),
                screenW = size.x,
                screenH = size.y,
                density = density,
                rightWeighted = !prefs.on(Prefs.SYMMETRIC),
            )
        }
    }
}

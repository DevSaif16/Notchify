package com.saif.notchify.ui

import android.content.Context
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import com.saif.notchify.Geometry
import com.saif.notchify.Island
import com.saif.notchify.Kind
import com.saif.notchify.LeadingAnim
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The compact pill. Right-weighted (default): the camera sits in the pill's left round end and
 * both items follow it on the right, so the notification icons left of the camera stay visible.
 * Symmetric: leading left of the camera, trailing right, like iOS.
 */
class CompactView(context: Context) : ViewGroup(context), Bindable {
    private var geo: Geometry? = null
    private val leading = ImageView(context)
    private val torch = TorchView(context)
    private val trailing = TrailingView(context)
    private var shownKey: String? = null
    private var shownLeadingKey: String? = null

    init {
        addView(leading)
        addView(torch)
        addView(trailing)
    }

    override fun bind(island: Island, geo: Geometry) {
        this.geo = geo
        val sameIsland = island.key == shownKey
        if (island.leadingAnim == LeadingAnim.TORCH) {
            leading.visibility = GONE
            torch.visibility = VISIBLE
            if (!sameIsland) torch.ignite()
        } else {
            torch.visibility = GONE
            leading.visibility = VISIBLE
            leading.show(island.leading, island.leadingShape, island.leadingTint)
            if (sameIsland && island.leadingKey != null && island.leadingKey != shownLeadingKey) leading.pop()
            if (island.kind == Kind.MEDIA) {
                // Cover art breathes down when paused, like iOS.
                val s = if (island.playing) 1f else 0.8f
                leading.animate().scaleX(s).scaleY(s).setDuration(420).setInterpolator(OvershootInterpolator(1.6f)).start()
            } else if (!sameIsland) {
                leading.scaleX = 1f
                leading.scaleY = 1f
            }
        }
        shownKey = island.key
        shownLeadingKey = island.leadingKey
        trailing.bind(island.trailing, iconSize(geo), big = false)
        requestLayout()
    }

    private fun iconSize(g: Geometry) = (g.compactH * 0.66f).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val g = geo ?: return setMeasuredDimension(0, 0)
        val h = g.compactH.roundToInt()
        val icon = iconSize(g)
        leading.measure(exactly(icon), exactly(icon))
        torch.measure(exactly(icon), exactly(icon))
        trailing.measure(UNSPECIFIED, exactly(icon))
        val inset = (h - icon) / 2
        val tw = trailing.measuredWidth
        val w = if (g.rightWeighted) {
            (g.capLeft + g.holeW / 2f).roundToInt() + dpi(9f) + icon + (if (tw > 0) dpi(10f) + tw else 0) + inset
        } else {
            val slot = max(icon, tw)
            2 * (inset + slot + dpi(16f)) + g.holeW.roundToInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val g = geo ?: return
        val w = r - l
        val h = b - t
        val icon = leading.measuredWidth
        val inset = (h - icon) / 2
        val lx = if (g.rightWeighted) (g.capLeft + g.holeW / 2f).roundToInt() + dpi(9f) else inset
        leading.layout(lx, inset, lx + icon, inset + icon)
        torch.layout(lx, inset, lx + icon, inset + icon)
        val tw = trailing.measuredWidth
        val th = trailing.measuredHeight
        trailing.layout(w - inset - tw, (h - th) / 2, w - inset, (h + th) / 2)
    }
}

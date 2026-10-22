package com.saif.notchify

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("notchify", Context.MODE_PRIVATE)

    fun on(key: String): Boolean = sp.getBoolean(key, key !in OFF_BY_DEFAULT)
    fun set(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    fun int(key: String): Int = sp.getInt(key, 0)
    fun setInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()

    companion object {
        const val MEDIA = "media"
        const val CALLS = "calls"
        const val NAVIGATION = "navigation"
        const val DELIVERY = "delivery"
        const val TIMERS = "timers"
        const val TORCH = "torch"
        const val LIVE = "live"
        const val NOTIFICATIONS = "notifications"
        const val CHARGING = "charging"
        const val RINGER = "ringer"
        const val CONTROLS = "controls"

        const val DRIVE_PEEK = "drive_peek"
        const val DRIVE_ALWAYS = "drive_always"
        const val SPEED = "speed"
        const val HEADS_UP = "suppress_heads_up"
        const val HEADS_UP_OWNED = "heads_up_owned"
        const val LOCK_PREVIEWS = "lock_previews"
        const val HIDE_FULLSCREEN = "hide_fullscreen"
        const val SYMMETRIC = "symmetric"
        const val EQUALIZER = "equalizer"
        const val HAPTICS = "haptics"
        const val CALIBRATE = "calibrate"

        const val OFFSET_X = "offset_x"
        const val OFFSET_Y = "offset_y"
        const val SIZE = "size"

        private val OFF_BY_DEFAULT = setOf(HEADS_UP_OWNED, LOCK_PREVIEWS, CALIBRATE, DRIVE_ALWAYS, SPEED, SYMMETRIC)

        fun keyFor(kind: Kind): String = when (kind) {
            Kind.CALL, Kind.ALARM -> CALLS
            Kind.NAVIGATION -> NAVIGATION
            Kind.DELIVERY -> DELIVERY
            Kind.TIMER -> TIMERS
            Kind.TORCH -> TORCH
            Kind.MEDIA -> MEDIA
            Kind.LIVE -> LIVE
            Kind.NOTIFICATION -> NOTIFICATIONS
            Kind.CHARGING -> CHARGING
            Kind.RINGER -> RINGER
            Kind.CONTROLS -> CONTROLS
        }
    }
}

package com.example.ancnameguard

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FILE = "guard"
    const val WAKE = "wake_words"
    const val MSG = "voice_message"
    const val REQUIRE_BT = "require_bt"
    const val USE_VOICE = "use_voice"
    const val USE_NOTIF = "use_notif"
    const val USE_CHIME = "use_chime"
    const val USE_VIBRATE = "use_vibrate"
    const val MUTE_BEEPS = "mute_beeps"
    const val SAVER = "battery_saver"
    const val ARMED = "armed"
    const val CHAR = "character"

    const val DEFAULT_WAKE = "siva, shiva, prasad, seeva"
    const val DEFAULT_MSG = "Hey Siva, someone is calling you!"

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

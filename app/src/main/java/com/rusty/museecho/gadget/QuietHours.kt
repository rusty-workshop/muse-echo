package com.rusty.museecho.gadget

import java.time.LocalTime

/**
 * Overnight quiet: no wake word, replies as captions only, the screen isn't
 * woken for Muse's own messages, and timers ring softly. Alarms still ring.
 * The window may wrap midnight (22:00 to 07:00).
 */
object QuietHours {
    fun isQuiet(prefs: Prefs, now: LocalTime = LocalTime.now()): Boolean =
        System.currentTimeMillis() < prefs.quietUntil ||
            (System.currentTimeMillis() >= prefs.quietSkipUntil && prefs.quietEnabled && inWindow(prefs.quietStart, prefs.quietEnd, now))

    fun inWindow(start: String, end: String, now: LocalTime): Boolean {
        val s = parse(start) ?: return false
        val e = parse(end) ?: return false
        return if (s <= e) now >= s && now < e else now >= s || now < e
    }

    fun parse(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim().padStart(5, '0')) }.getOrNull()
}

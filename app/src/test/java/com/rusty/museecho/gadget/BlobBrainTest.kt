package com.rusty.museecho.gadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlobBrainTest {
    @Test
    fun agendaTimes() {
        assertEquals(9 * 60 + 30, BlobBrain.minutesOfDay("9:30"))
        assertEquals(9 * 60 + 30, BlobBrain.minutesOfDay("9:30 AM"))
        assertEquals(21 * 60 + 30, BlobBrain.minutesOfDay("9:30pm"))
        assertEquals(9 * 60, BlobBrain.minutesOfDay("9am"))
        assertEquals(14 * 60, BlobBrain.minutesOfDay("14:00"))
        assertEquals(12 * 60, BlobBrain.minutesOfDay("Noon"))
        assertEquals(12 * 60 + 15, BlobBrain.minutesOfDay("12:15 PM"))
        assertEquals(15, BlobBrain.minutesOfDay("12:15 a.m."))
        assertEquals(10 * 60, BlobBrain.minutesOfDay("10:00 - 11:00"))
        assertNull(BlobBrain.minutesOfDay("All day"))
        assertNull(BlobBrain.minutesOfDay("25:00"))
        assertNull(BlobBrain.minutesOfDay(""))
    }

    @Test
    fun agendaDates() {
        val today = java.time.LocalDate.of(2026, 10, 10)
        assertEquals(true, BlobBrain.isToday("", today))
        assertEquals(true, BlobBrain.isToday("2026-10-10", today))
        assertEquals(true, BlobBrain.isToday("Saturday, October 10", today))
        assertEquals(true, BlobBrain.isToday("Oct 10th", today))
        assertEquals(true, BlobBrain.isToday("10/10", today))
        assertEquals(false, BlobBrain.isToday("2026-10-09", today))
        assertEquals(false, BlobBrain.isToday("Friday, October 9", today))
        assertEquals(false, BlobBrain.isToday("November 10", today))
    }

    @Test
    fun yesNo() {
        assertEquals(true, BlobBrain.yesNo("Yes, the oven is off."))
        assertEquals(true, BlobBrain.yesNo("Yep!"))
        assertEquals(false, BlobBrain.yesNo("No, it's not raining."))
        assertEquals(false, BlobBrain.yesNo("Not yet. Maybe later."))
        assertNull(BlobBrain.yesNo("No problem, done."))
        assertNull(BlobBrain.yesNo("Nobody called."))
        assertNull(BlobBrain.yesNo("It's 72 degrees."))
    }

    @Test
    fun replyTone() {
        assertEquals(BlobBrain.Tone(Mood.HAPPY, hop = true), BlobBrain.tone("Congrats on the new job! 🎉"))
        assertEquals(Mood.SAD, BlobBrain.tone("I'm sorry to hear that.")?.mood)
        assertEquals(Mood.WORRIED, BlobBrain.tone("Heads up: judgment-bot is down.")?.mood)
        assertEquals(Mood.WINK, BlobBrain.tone("Why did the blob cross the road? Haha.")?.mood)
        assertNull(BlobBrain.tone("It's 72 degrees."))
        assertNull(BlobBrain.tone("Storms around 6, take an umbrella. Nothing else urgent."))
        assertNull(BlobBrain.tone("No outage today, and it isn't urgent."))
        assertEquals(Mood.WORRIED, BlobBrain.tone("Not great: the backup failed.")?.mood)
    }
}

class BlobSoundsTest {
    @org.junit.Test
    fun everySoundIsShortAndQuiet() {
        for (s in BlobSound.entries) {
            val pcm = BlobSounds.render(s)
            org.junit.Assert.assertTrue("$s is empty", pcm.isNotEmpty())
            org.junit.Assert.assertTrue("$s is too long", pcm.size < BlobSounds.RATE)
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            org.junit.Assert.assertTrue("$s is too loud ($peak)", peak < Short.MAX_VALUE * 0.15)
            // Starts and ends near silence: no clicks.
            org.junit.Assert.assertTrue("$s clicks at the start", kotlin.math.abs(pcm.first().toInt()) < 200)
        }
    }
}

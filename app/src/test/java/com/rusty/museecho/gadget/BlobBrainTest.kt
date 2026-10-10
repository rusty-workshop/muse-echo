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

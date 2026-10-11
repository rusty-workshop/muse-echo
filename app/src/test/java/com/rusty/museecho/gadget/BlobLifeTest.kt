package com.rusty.museecho.gadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobLifeTest {
    @Test
    fun sunTimesForJacksonville() {
        val (rise, set) = LifeMath.sunTimes(30.3322, -81.6557, java.time.LocalDate.of(2026, 10, 11), java.time.ZoneId.of("America/New_York"))!!
        // About 7:24 am and 7:04 pm EDT.
        assertTrue("sunrise $rise", rise in (7 * 60 + 14)..(7 * 60 + 34))
        assertTrue("sunset $set", set in (18 * 60 + 54)..(19 * 60 + 14))
    }

    @Test
    fun warmthFollowsTheSun() {
        val rise = 7 * 60; val set = 19 * 60
        assertEquals(0f, LifeMath.warmth(12 * 60, rise, set), 0.001f)
        assertEquals(0f, LifeMath.warmth(set - 30, rise, set), 0.001f)
        assertEquals(0.5f, LifeMath.warmth(set + 15, rise, set), 0.01f)
        assertEquals(1f, LifeMath.warmth(23 * 60, rise, set), 0.001f)
        assertEquals(1f, LifeMath.warmth(3 * 60, rise, set), 0.001f)
        assertEquals(0.5f, LifeMath.warmth(rise + 15, rise, set), 0.01f)
    }

    @Test
    fun bedtimeIsTheMedianAcrossMidnight() {
        val nights = listOf(LifeMath.afterNoon(23, 10), LifeMath.afterNoon(0, 20), LifeMath.afterNoon(23, 40),
            LifeMath.afterNoon(23, 30), LifeMath.afterNoon(2, 0))
        assertNull(LifeMath.usualBedtime(nights.take(4)))
        assertEquals("11:40 pm", LifeMath.clock(LifeMath.usualBedtime(nights)!!))
        assertEquals("12:20 am", LifeMath.clock(LifeMath.afterNoon(0, 20)))
    }

    @Test
    fun roomGrowsWithAge() {
        assertEquals(listOf(Decor.WINDOW, Decor.RUG), LifeMath.dueByAge(0))
        assertTrue(Decor.LAMP in LifeMath.dueByAge(4))
        assertTrue(Decor.FAIRY_LIGHTS !in LifeMath.dueByAge(20))
        assertTrue(Decor.FAIRY_LIGHTS in LifeMath.dueByAge(21))
    }

    @Test
    fun friendChatsAlternate() {
        LifeMath.FRIEND_CHATS.forEach { chat -> assertTrue(chat.size in 3..6) }
    }
}

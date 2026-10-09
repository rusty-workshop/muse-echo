package com.rusty.museecho.gadget

import com.rusty.museecho.integrations.Timers
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAssistantTest {
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 10, 9, 14, 5).atZone(zone).toInstant().toEpochMilli()
    private val timers = Timers(Files.createTempFile("t", ".json").toFile()) { now }
    private val bot = OfflineAssistant(timers, { null }, { "Pixel" }) { now }

    @Test
    fun parsesSpokenDurations() {
        assertEquals(5 * 60_000L, OfflineAssistant.duration("set a timer for five minutes"))
        assertEquals(25 * 60_000L, OfflineAssistant.duration("timer for twenty five minutes"))
        assertEquals(90_000L, OfflineAssistant.duration("one minute thirty seconds"))
        assertEquals(30 * 60_000L, OfflineAssistant.duration("set a timer for half an hour"))
        assertEquals(150_000L, OfflineAssistant.duration("two and a half minutes"))
        assertEquals(60_000L, OfflineAssistant.duration("a minute"))
        assertEquals(10 * 60_000L, OfflineAssistant.duration("set a 10 minute timer"))
    }

    @Test
    fun parsesSpokenClockTimes() {
        assertEquals("7:30 am", OfflineAssistant.clockTime("set an alarm for seven thirty a m"))
        assertEquals("6:00 pm", OfflineAssistant.clockTime("alarm at six p m"))
        assertEquals("7:05", OfflineAssistant.clockTime("wake me up at seven oh five"))
        assertEquals("9:00 pm", OfflineAssistant.clockTime("alarm for nine o clock tonight"))
        assertEquals("12:00 pm", OfflineAssistant.clockTime("alarm at noon"))
        assertEquals("6:45 am", OfflineAssistant.clockTime("set an alarm for 6:45 am"))
    }

    @Test
    fun answersTheBasics() {
        assertEquals("Timer set for 5m.", bot.answer("Set a timer for five minutes"))
        assertEquals(1, timers.items.value.size)
        val left = bot.answer("how much time is left on my timer")
        assertTrue(left, left.startsWith("5m timer: 5m left"))
        assertEquals("Cancelled 1 timer.", bot.answer("cancel the timer"))
        assertEquals("It's 2:05 PM.", bot.answer("what time is it"))
        assertTrue(bot.answer("set an alarm for seven a m").startsWith("Alarm set for 7:00 AM"))
        assertTrue(bot.answer("what's the weather").contains("don't have the weather"))
        assertTrue(bot.answer("tell me a joke").startsWith("I can't reach Pixel right now"))
    }
}

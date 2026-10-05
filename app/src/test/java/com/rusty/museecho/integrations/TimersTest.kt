package com.rusty.museecho.integrations

import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimersTest {
    private val zone = ZoneId.systemDefault()
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    private fun at(ms: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone)

    @Test
    fun parsesTimes() {
        assertEquals(LocalTime.of(7, 30), Timers.parseTime("07:30"))
        assertEquals(LocalTime.of(19, 30), Timers.parseTime("7:30 pm"))
        assertEquals(LocalTime.of(18, 0), Timers.parseTime("6pm"))
        assertEquals(LocalTime.of(0, 15), Timers.parseTime("12:15 am"))
        assertEquals(LocalTime.of(12, 0), Timers.parseTime("12 p.m."))
        assertNull(Timers.parseTime("25:00"))
        assertNull(Timers.parseTime("soon"))
        assertEquals(DayOfWeek.MONDAY, Timers.parseDay("mon"))
        assertEquals(DayOfWeek.FRIDAY, Timers.parseDay("Friday"))
    }

    @Test
    fun timersRingAndAlarmsRepeat() {
        // Monday 2026-10-05 08:00
        var now = ms(LocalDateTime.of(2026, 10, 5, 8, 0))
        val file = Files.createTempFile("timers", ".json").toFile()
        val t = Timers(file) { now }
        t.addTimer(5 * 60_000, "pasta")
        val alarm = t.addAlarm("7:30", "wake up", listOf("monday", "wednesday"))
        // Already past 7:30 on Monday, so next is Wednesday.
        assertEquals(LocalDateTime.of(2026, 10, 7, 7, 30), at(alarm.dueAtMs))
        val once = t.addAlarm("9:00 am", "", emptyList())
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), at(once.dueAtMs))

        now += 5 * 60_000
        assertEquals(listOf("pasta"), t.tick().map { it.label })
        assertEquals(1, t.ringing.value.size)
        t.dismiss()

        now = ms(LocalDateTime.of(2026, 10, 7, 7, 30))
        val rang = t.tick().map { it.label }.toSet()
        assertEquals(setOf("wake up", "Alarm"), rang)
        // The repeating alarm moved to next Monday; the one-off is gone.
        assertEquals(listOf(LocalDateTime.of(2026, 10, 12, 7, 30)), t.items.value.map { at(it.dueAtMs) })

        // Survives a restart.
        assertEquals(1, Timers(file) { now }.items.value.size)
        assertEquals(1, t.cancel("wake up"))
        assertTrue(t.items.value.isEmpty())
        file.delete()
    }

    @Test
    fun formatsDurations() {
        assertEquals("5m", Timers.formatDuration(300_000))
        assertEquals("1h 30m", Timers.formatDuration(5_400_000))
        assertEquals("4:59", Timers.clock(299_000))
        assertEquals("1:02:03", Timers.clock(3_723_000))
    }
}

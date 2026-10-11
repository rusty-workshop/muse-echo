package com.rusty.museecho.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockFacesTest {
    private fun words(h: Int, m: Int) = timeInWords(h, m).first

    @Test
    fun wordClock() {
        assertEquals("It's six o'clock", words(18, 0))
        assertEquals("It's quarter past six", words(6, 15))
        assertEquals("It's almost quarter past six", words(6, 14))
        assertEquals("It's just after half past six", words(6, 31))
        assertEquals("It's quarter to seven", words(6, 45))
        assertEquals("It's almost seven o'clock", words(6, 58))
        assertEquals("It's twelve o'clock", words(0, 0))
        assertEquals("It's twenty-five to one", words(12, 35))
    }

    @Test
    fun keyWordsHighlightTheTime() {
        assertEquals(listOf("quarter", "past", "six"), timeInWords(6, 15).second)
        assertEquals(listOf("seven"), timeInWords(7, 0).second)
    }
}

class FrameOrderTest {
    private fun files(vararg n: String) = n.map { java.io.File("/tmp/$it") }

    @Test
    fun hiddenNeverAndFavouritesTwice() {
        repeat(50) {
            val order = frameOrder(files("a", "b", "c", "d"), favs = setOf("a"), hidden = setOf("d"), last = "b")
            assertEquals(4, order.size)
            assertEquals(2, order.count { it.name == "a" })
            assertEquals(0, order.count { it.name == "d" })
            org.junit.Assert.assertNotEquals("b", order.first().name)
        }
    }
}

class TimePadTest {
    @Test
    fun timerDigits() {
        assertEquals(330_000L, timerDigitsToMs("530"))
        assertEquals(3_600_000L, timerDigitsToMs("10000"))
        assertEquals(0L, timerDigitsToMs(""))
    }

    @Test
    fun alarmDigits() {
        assertEquals(7 to 30, alarmDigitsToTime("730"))
        assertEquals(12 to 15, alarmDigitsToTime("1215"))
        org.junit.Assert.assertNull(alarmDigitsToTime("75"))
        org.junit.Assert.assertNull(alarmDigitsToTime("1375"))
        org.junit.Assert.assertNull(alarmDigitsToTime("1300"))
    }
}

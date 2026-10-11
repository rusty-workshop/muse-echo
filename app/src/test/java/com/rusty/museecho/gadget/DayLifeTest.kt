package com.rusty.museecho.gadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayLifeTest {
    @Test
    fun thingParsesPixelsFormat() {
        val t = DayWriting.parseThing("KIND: word\nTITLE: Petrichor\nTEXT: The smell of rain on dry ground.", "2026-10-12")!!
        assertEquals("word", t.kind)
        assertEquals("Petrichor", t.title)
        assertEquals("The smell of rain on dry ground.", t.text)
    }

    @Test
    fun thingCopesWithMarkdownAndOddKinds() {
        val t = DayWriting.parseThing("Here you go!\n**Kind:** On this day\n**Title:** \"Moon landing\"\n**Text:** In 1969...", "d")!!
        assertEquals("history", t.kind)
        assertEquals("Moon landing", t.title)
        assertEquals("fact", DayWriting.parseThing("KIND: trivia\nTITLE: x\nTEXT: y", "d")!!.kind)
        assertNull(DayWriting.parseThing("Sure! Did you know octopuses have three hearts?", "d"))
    }

    @Test
    fun diaryPromptUsesTheDay() {
        val p = DayWriting.diaryPrompt(DayFacts("2026-10-12", talks = 3, pokes = 2, tvMinutes = 95, events = listOf("9:41 pm lights off")), "Sunny")
        assertTrue("talked to it 3" in p)
        assertTrue("poked 2" in p)
        assertTrue("1 h 35 min" in p)
        assertTrue("9:41 pm lights off" in p)
        assertTrue("weather: Sunny" in p)
        assertTrue("timer" !in p.substringBefore("Reply with"))
    }

    @Test
    fun awayFor() {
        assertEquals("25 min", DayWriting.awayFor(25 * 60_000L))
        assertEquals("3 h 10 min", DayWriting.awayFor((3 * 60 + 10) * 60_000L))
    }
}

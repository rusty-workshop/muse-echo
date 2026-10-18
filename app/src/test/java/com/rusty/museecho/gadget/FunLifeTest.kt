package com.rusty.museecho.gadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunLifeTest {
    @Test
    fun triviaParsesAroundExtraText() {
        val reply = "Here you go!\n```json\n[{\"q\": \"Biggest planet?\", \"options\": [\"Mars\", \"Jupiter\", \"Venus\", \"Earth\"], \"answer\": 1}," +
            "{\"q\": \"Bad one\", \"options\": [\"a\"], \"answer\": 0}," +
            "{\"q\": \"Ocean?\", \"options\": [\"Pacific\", \"Lake\"], \"answer\": \"Pacific\"}]\n```"
        val qs = FunLife.parseTrivia(reply)
        assertEquals(2, qs.size)
        assertEquals(1, qs[0].answer)
        assertEquals(0, qs[1].answer)
        assertTrue(FunLife.parseTrivia("no json here").isEmpty())
    }
}

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

    @Test
    fun mysteryCluesPreferTellingOnes() {
        val f = Friend(id = "a", name = "Moss", personality = Personality.SLEEPY.name, petKind = "tortoise", color = 0xFF7FE0A8)
        val others = listOf(f, Friend(id = "b", name = "Pip", personality = Personality.SLEEPY.name, petKind = "bunny", color = 0xFFFF9EC7))
        val clues = FriendsBrain.mysteryClues(f, others, java.util.Random(1))
        assertEquals(3, clues.size)
        // Both are sleepy, so the yawn shouldn't be one of the three when better clues exist.
        assertTrue(clues.none { "yawned" in it })
    }
}

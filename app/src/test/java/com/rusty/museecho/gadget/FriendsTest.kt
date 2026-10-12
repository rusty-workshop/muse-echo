package com.rusty.museecho.gadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsTest {
    @Test
    fun namesFromPixel() {
        assertEquals("Sprout" to "A shy little blob who loves moss.", FriendsBrain.parseName("NAME: sprout\nBIO: A shy little blob who loves moss."))
        assertEquals("Nibbles", FriendsBrain.parseName("**Name:** \"Nibbles!\"\n**Bio:** Eats cookies.")!!.first)
        assertNull(FriendsBrain.parseName("How about Sprout?"))
    }

    @Test
    fun ticTacToeGamesAreLegal() {
        val rnd = java.util.Random(7)
        repeat(200) {
            val (moves, winner) = FriendsBrain.ticTacToe(rnd)
            assertEquals(moves.size, moves.distinct().size)
            assertTrue(moves.all { it in 0..8 })
            val b = CharArray(9) { ' ' }
            moves.forEachIndexed { i, c -> b[c] = if (i % 2 == 0) 'X' else 'O' }
            assertEquals(winner, FriendsBrain.winner(b))
            if (winner == null) assertEquals(9, moves.size)
        }
    }

    @Test
    fun rockPaperScissors() {
        assertEquals(1, FriendsBrain.rps(0, 2))  // rock beats scissors
        assertEquals(-1, FriendsBrain.rps(0, 1)) // rock loses to paper
        assertEquals(1, FriendsBrain.rps(2, 1))  // scissors beat paper
        assertEquals(0, FriendsBrain.rps(1, 1))
    }

    @Test
    fun chatFitsThePersonality() {
        val shy = Friend("a", "Moss", personality = Personality.SHY.name)
        assertEquals(false to "…hi. 👉👈", FriendsBrain.chat(shy, java.util.Random(1)).first())
        val withMemory = shy.copy(memories = listOf("the great cookie fight"))
        assertTrue((0 until 30).any { s -> FriendsBrain.chat(withMemory, java.util.Random(s.toLong())).any { "cookie fight" in it.second } })
    }

    @Test
    fun partyDays() {
        assertEquals("Halloween", FriendsBrain.holiday(java.time.LocalDate.of(2026, 10, 31)))
        assertNull(FriendsBrain.holiday(java.time.LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun bondsAreSymmetric() {
        val d = FriendsData(friends = listOf(Friend("a", "Pip"), Friend("b", "Moss"), Friend("c", "Doze")),
            bonds = mapOf(FriendsBrain.pair("b", "a") to 40, FriendsBrain.pair("a", "c") to 5))
        assertEquals(40, d.bond("a", "b"))
        assertEquals(40, d.bond("b", "a"))
        assertEquals(listOf("Moss", "Doze"), d.buddies("a").map { it.first.name })
        assertTrue(d.buddies("b").map { it.first.name } == listOf("Pip"))
    }

    @Test
    fun whereaboutsMakeSense() {
        val f = Friend("x", "Pip", lastVisitMs = 0)
        val noon = 1_800_000_000_000L
        assertEquals(FriendsBrain.Where.VISITING, FriendsBrain.whereabouts(f, setOf("x"), noon, 12))
        assertEquals(FriendsBrain.Where.ASLEEP, FriendsBrain.whereabouts(f, emptySet(), noon, 2))
        val away = f.copy(lastVisitMs = noon - 5 * 86_400_000L, postcardMs = noon - 3_600_000L)
        assertEquals(FriendsBrain.Where.AWAY, FriendsBrain.whereabouts(away, emptySet(), noon, 12))
        val sleepy = f.copy(personality = Personality.SLEEPY.name)
        assertEquals(FriendsBrain.Where.ASLEEP, FriendsBrain.whereabouts(sleepy, emptySet(), noon, 14))
    }

    @Test
    fun hangoutLinesUseTheirVoice() {
        val moss = Friend("b", "Moss", personality = Personality.SHY.name)
        val pip = Friend("a", "Pip")
        assertTrue(FriendsBrain.hangoutLine(moss, pip, "built a pillow fort").startsWith("…Pip and I built a pillow fort"))
        assertTrue(FriendsBrain.hangoutLine(pip, moss, "had a picnic").contains("Moss and I had a picnic"))
    }

    @Test
    fun comicsParse() {
        val reply = """TITLE: The Great Sock Hunt
PANEL: Bean, Moss | curious | none | room | Where did Moss go? | Morning
PANEL: Moss | surprised | gift | outside | Found you a sock! |
PANEL: Bean, Moss | happy | heart | room | Best gift ever. | The end"""
        val c = FriendsBrain.parseComic(reply, "2026-10-12")!!
        assertEquals("The Great Sock Hunt", c.title)
        assertEquals(listOf("Bean", "Moss"), c.panels[0].who)
        assertEquals("", c.panels[0].prop)
        assertEquals("gift", c.panels[1].prop)
        assertEquals("outside", c.panels[1].place)
        assertNull(FriendsBrain.parseComic("TITLE: x\nPANEL: a | happy | none | room | hi", "d"))
    }

    @Test
    fun blobNames() {
        assertEquals("Bean", FriendsBrain.parseBlobName("NAME: bean!"))
        assertNull(FriendsBrain.parseBlobName("How about Bean?"))
    }

    @Test
    fun birthdays() {
        val b = FriendsBrain.randomBirthday(java.util.Random(3))
        assertTrue(Regex("\\d{2}-\\d{2}").matches(b))
        assertTrue(FriendsBrain.isBirthday(Friend("a", birthday = "10-12"), java.time.LocalDate.of(2026, 10, 12)))
    }
}

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

    @Test
    fun storiesParse() {
        val reply = """TITLE: Pebble's Big Climb
CAST: Pebble, Doze
CHAPTER: Pebble | I'm going to climb the bookshelf!
CHAPTER: Doze | I'll be base camp. *yawn*
CHAPTER: Pebble | Training day: ten tiny push-ups!
CHAPTER: Puff | Pebble made it to the top! 🎉"""
        val s = FriendsBrain.parseStory(reply, "2026-10-12")!!
        assertEquals("Pebble's Big Climb", s.title)
        assertEquals(listOf("Pebble", "Doze"), s.cast)
        assertEquals(4, s.chapters.size)
        assertEquals("Doze", s.next!!.copy().let { s.chapters[1].who })
        assertEquals(false, s.done)
        assertEquals(true, s.copy(at = 4).done)
        assertNull(FriendsBrain.parseStory("TITLE: x\nCAST: a\nCHAPTER: a | b", "d"))
    }

    @Test
    fun gardenGrows() {
        assertEquals(0, FriendsBrain.gardenStage(0, 0, 6))
        assertEquals(1, FriendsBrain.gardenStage(3, 0, 6))
        assertEquals(2, FriendsBrain.gardenStage(3, 3, 6))
        assertEquals(3, FriendsBrain.gardenStage(10, 0, 6))
        assertEquals(2, FriendsBrain.gardenStage(10, 0, 12), "no flowers in December")
    }

    private fun assertEquals(expected: Int, actual: Int, msg: String) = org.junit.Assert.assertEquals(msg, expected.toLong(), actual.toLong())

    @Test
    fun puffTakesTheWin() {
        val b = "OO XX    ".toCharArray()
        assertEquals(2, FriendsBrain.puffMove(b, java.util.Random(1)))
    }

    @Test
    fun outfitsSuitTheWeather() {
        val rnd = java.util.Random(1)
        assertEquals("SCARF", FriendsBrain.pickOutfit(listOf("SCARF", "BOW"), 45, "Cloudy", false, rnd))
        assertEquals("SUNGLASSES", FriendsBrain.pickOutfit(listOf("SUNGLASSES", "BOW"), 92, "Clear sky", false, rnd))
        assertEquals("PARTY_HAT", FriendsBrain.pickOutfit(listOf("PARTY_HAT", "SCARF"), 40, "Snow", true, rnd))
    }

    @Test
    fun practiceHasDiminishingReturns() {
        assertEquals(9, FriendsBrain.practiced(0, 0))
        assertEquals(7, FriendsBrain.practiced(0, 1))
        assertEquals(1, FriendsBrain.practiced(0, 5))
        assertEquals(100, FriendsBrain.practiced(98, 0))
    }

    @Test
    fun gazetteParses() {
        val g = FriendsBrain.parseGazette("""HEADLINE: Snail Wins Race!
ARTICLE: Miso's big day | Miso the snail crossed the rug.
ARTICLE: Market news | Glitter sold out.
WEATHER: Cloudy with a chance of cookies""", "2026-10-18")!!
        assertEquals("Snail Wins Race!", g.headline)
        assertEquals(2, g.articles.size)
        assertEquals("Cloudy with a chance of cookies", g.weather)
    }

    @Test
    fun everyoneHasAClubAndAPet() {
        Personality.entries.forEach { p ->
            val f = Friend("x", personality = p.name)
            assertTrue(FriendsBrain.clubOf(f) in FriendsBrain.Club.entries)
            assertTrue(FriendsBrain.petEmoji(FriendsBrain.petFor(p)) != "🐟" || FriendsBrain.petFor(p) == "glitterfish")
        }
    }

    @Test
    fun penpalsBooksRelativesStickers() {
        val p = FriendsBrain.parsePenpal("**NAME:** Sora!\nPLACE: a lighthouse in Hokkaido\nBIO: Loves the sea.")!!
        assertEquals("Sora", p.name)
        assertEquals("a lighthouse in Hokkaido", p.place)
        assertNull(FriendsBrain.parsePenpal("NAME: Sora"))
        val b = FriendsBrain.parseBook("TITLE: \"The Puddle Who Wanted to Be a Lake\"\nREVIEW: Made me cry (happy tears).", "Pip", "2026-10-12")!!
        assertEquals("The Puddle Who Wanted to Be a Lake", b.title)
        assertEquals("Pip", b.reader)
        assertNull(FriendsBrain.parseBook("I can't think of one", "Pip", "2026-10-12"))
        Personality.entries.forEach { assertTrue(FriendsBrain.relativeOf(it).names.isNotEmpty()) }
        assertTrue(FriendsBrain.relativeOf(Personality.TINY).scale < FriendsBrain.relativeOf(Personality.SLEEPY).scale)
        val ids = FriendsBrain.STICKERS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(FriendsBrain.STICKERS.all { it.who == "puff" || it.who == "you" })
        assertTrue(Friend("guest", scaleOverride = 0.45f).scale == 0.45f)
    }

    @Test
    fun recipesStarsFestivals() {
        val r = FriendsBrain.parseRecipe("**TITLE:** Cloud Cupcakes\nINGREDIENTS: 1 cup flour; 2 eggs; a pinch of moonlight\nSTEPS: Mix; Bake 20 min; Eat", "Puff & Pip", "2026-10-12", true)!!
        assertEquals("Cloud Cupcakes", r.title)
        assertEquals(3, r.ingredients.size)
        assertEquals("Bake 20 min", r.steps[1])
        assertNull(FriendsBrain.parseRecipe("TITLE: Nothing", "Puff", "2026-10-12", false))
        assertEquals(3, FriendsBrain.parseLines("1. A sock went missing 🧦\n2. It was scary\n3. It was in the jar 🍪\n4. extra")!!.size)
        assertNull(FriendsBrain.parseLines("just one line"))
        assertEquals("The Sleepy Teapot", FriendsBrain.parseConstellation("NAME: The Sleepy Teapot\nSHAPE: a teapot having a nap", "Puff", "2026-10-12")!!.name)
        assertEquals("full moon", FriendsBrain.moonPhase(java.time.LocalDate.of(2024, 10, 17)).second)
        assertEquals("new moon", FriendsBrain.moonPhase(java.time.LocalDate.of(2024, 10, 2)).second)
        assertEquals("the Orionids", FriendsBrain.meteorShower(java.time.LocalDate.of(2026, 10, 21)))
        assertNull(FriendsBrain.meteorShower(java.time.LocalDate.of(2026, 10, 12)))
        assertEquals(FriendsBrain.Festival.FAIR, FriendsBrain.festival(java.time.LocalDate.of(2026, 10, 17), 12))
        assertEquals(FriendsBrain.Festival.FAIR, FriendsBrain.festival(java.time.LocalDate.of(2026, 10, 18), 12))
        assertNull(FriendsBrain.festival(java.time.LocalDate.of(2026, 10, 10), 12))
        assertEquals(FriendsBrain.Festival.PARADE, FriendsBrain.festival(java.time.LocalDate.of(2026, 12, 5), 18))
        assertNull(FriendsBrain.festival(java.time.LocalDate.of(2026, 12, 5), 12))
        assertTrue(FriendsBrain.lightsOut(Friend("a", personality = "SLEEPY")) < FriendsBrain.lightsOut(Friend("a", personality = "SPARKLY")))
        Personality.entries.forEach { assertTrue(FriendsBrain.presentFor(it).second.isNotBlank()) }
    }

    @Test
    fun boardPuppetsAnniversariesCalendar() {
        assertEquals(11 to "ladder", FriendsBrain.snakesMove(1, 2))
        assertEquals(4 to "snake", FriendsBrain.snakesMove(10, 4))
        assertEquals(27 to null, FriendsBrain.snakesMove(27, 6))
        assertEquals(30 to null, FriendsBrain.snakesMove(27, 3))
        val play = FriendsBrain.parsePuppet("TITLE: The Lost Hat\nSCENE 1: A hat blew away 🎩\nSCENE 2: Everyone chased it 🏃\nSCENE 3: A bird wore it 🐦\nSCENE 4: They shared 💛")!!
        assertEquals("The Lost Hat", play.first)
        assertEquals(4, play.second.size)
        assertNull(FriendsBrain.parsePuppet("TITLE: Too short\nSCENE 1: one"))
        val zone = java.time.ZoneId.systemDefault()
        val met = java.time.LocalDate.of(2026, 1, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("1 month", FriendsBrain.anniversary(met, java.time.LocalDate.of(2026, 2, 15)))
        assertEquals("6 months", FriendsBrain.anniversary(met, java.time.LocalDate.of(2026, 7, 15)))
        assertEquals("1 year", FriendsBrain.anniversary(met, java.time.LocalDate.of(2027, 1, 15)))
        assertNull(FriendsBrain.anniversary(met, java.time.LocalDate.of(2026, 3, 15)))
        assertNull(FriendsBrain.anniversary(met, java.time.LocalDate.of(2026, 2, 16)))
        assertEquals("Thanksgiving", FriendsBrain.holiday(java.time.LocalDate.of(2026, 11, 26)))
        assertNull(FriendsBrain.holiday(java.time.LocalDate.of(2026, 11, 19)))
        assertEquals("🌧️", FriendsBrain.weatherDoodle("Light rain", 60))
        assertEquals(2, FriendsBrain.upgradeLevel(90))
        assertEquals(0, FriendsBrain.upgradeLevel(10))
        assertEquals(FriendsBrain.COLLECTIBLES.size, FriendsBrain.COLLECTIBLES.map { it.id }.toSet().size)
        assertEquals(setOf("Shells", "Leaves", "Stamps", "Stones"), FriendsBrain.COLLECTIBLES.map { it.set }.toSet())
    }
}

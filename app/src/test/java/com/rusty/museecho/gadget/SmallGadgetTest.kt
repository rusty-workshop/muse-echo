package com.rusty.museecho.gadget

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallGadgetTest {
    @Test
    fun quietWindowsWrapMidnight() {
        assertTrue(QuietHours.inWindow("22:00", "07:00", LocalTime.of(23, 30)))
        assertTrue(QuietHours.inWindow("22:00", "07:00", LocalTime.of(3, 0)))
        assertFalse(QuietHours.inWindow("22:00", "07:00", LocalTime.of(7, 0)))
        assertFalse(QuietHours.inWindow("22:00", "07:00", LocalTime.of(12, 0)))
        assertTrue(QuietHours.inWindow("13:00", "15:00", LocalTime.of(14, 0)))
        assertEquals(LocalTime.of(7, 5), QuietHours.parse("7:05"))
    }

    @Test
    fun speechDropsMarkdownAndUrls() {
        val md = "## Today\n- **Rain** at 3pm\n- see [radar](https://x.test/r)\n\nMore at https://x.test/long ![map](https://x.test/m.png)"
        assertEquals("Today\nRain at 3pm\nsee radar\nMore at a link", Markdown.toSpeech(md))
    }

    @Test
    fun firstChunkIsCutAtACommaOnlyWhenItHelps() {
        assertEquals(listOf("Sure."), NeuralVoice.firstChunks("Sure."))
        assertEquals(
            listOf("It's seventy five degrees and overcast,", "with a high of eighty five today. Bring an umbrella."),
            NeuralVoice.firstChunks("It's seventy five degrees and overcast, with a high of eighty five today. Bring an umbrella."),
        )
        // A comma after the first sentence ends doesn't split it.
        assertEquals(listOf("Yes. Later, maybe."), NeuralVoice.firstChunks("Yes. Later, maybe."))
    }
}

package com.rusty.museecho.gadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {
    private val json = """{"result":[{"conf":0.98,"word":"so"},{"conf":0.91,"word":"hey"},{"conf":0.72,"word":"pixel"}],"text":"so hey pixel"}"""

    @Test
    fun confidenceIsTheWeakestWordOfThePhrase() {
        assertEquals(0.72, WakeWord.phraseConfidence(json, listOf("hey", "pixel"))!!, 1e-9)
        assertEquals(0.72, WakeWord.phraseConfidence(json, listOf("pixel"))!!, 1e-9)
    }

    @Test
    fun missingPhraseIsNull() {
        assertNull(WakeWord.phraseConfidence(json, listOf("hey", "muse")))
        assertNull(WakeWord.phraseConfidence("""{"text":"hey pixel"}""", listOf("hey", "pixel")))
    }

    @Test
    fun strictIsStricterThanEager() {
        assert(WakeWord.Sensitivity.LOW.minConf > WakeWord.Sensitivity.MEDIUM.minConf)
        assert(WakeWord.Sensitivity.MEDIUM.minConf > WakeWord.Sensitivity.HIGH.minConf)
    }
}

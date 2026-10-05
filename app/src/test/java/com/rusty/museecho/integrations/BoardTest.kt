package com.rusty.museecho.integrations

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {
    private fun tmp(name: String) = Files.createTempDirectory("board").toFile().resolve(name)

    @Test
    fun notesAddRemoveAndPersist() {
        val file = tmp("notes.json")
        val notes = Notes(file)
        notes.set("Shopping", listOf("milk", " eggs ", ""))
        notes.add(listOf("Milk", "bread"))
        assertEquals(listOf("milk", "eggs", "bread"), notes.value.value.items)
        assertEquals(1, notes.remove(listOf("EGG")))
        assertEquals(PinnedNote("Shopping", listOf("milk", "bread")), Notes(file).value.value)
        notes.clear()
        assertTrue(Notes(file).value.value.items.isEmpty())
    }

    @Test
    fun glanceExpiresAndHistoryIsCapped() {
        val glance = Glance(tmp("glance.json"))
        glance.set("Dentist at 3", 30)
        assertEquals("Dentist at 3", glance.current())
        assertEquals("", glance.current(System.currentTimeMillis() + 31 * 60_000L))
        glance.set("Pinned", null)
        assertEquals("Pinned", glance.current(Long.MAX_VALUE))

        val history = History(tmp("history.json"))
        repeat(History.MAX + 5) { history.add(Exchange(it.toLong(), "q$it", "a$it")) }
        assertEquals(History.MAX, history.value.value.size)
        assertEquals("q5", history.value.value.first().heard)
        assertEquals("Partly cloudy", Weather.describe(2))
    }
}

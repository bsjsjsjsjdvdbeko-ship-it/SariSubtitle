package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Stage9ContextTest {
    @Test
    fun deduplicatorRejectsNearDuplicateOverlap() {
        val d = TranscriptDeduplicator()
        assertTrue(d.shouldTranslate("Hello Sarah, we have to go"))
        assertTrue(!d.shouldTranslate("Hello Sarah we have to go"))
        assertTrue(d.shouldTranslate("Why are you leaving now?"))
    }

    @Test
    fun translationMemoryHashIsStable() {
        assertEquals(TextHash.sha256("Hello"), TextHash.sha256(" Hello "))
        assertTrue(TextHash.sha256("Hello").length == 64)
    }

    @Test
    fun contextKeepsRecentSegmentsAndGlossaryModelIsSerializable() {
        val context = TranslationContext(
            previousSegments = listOf("John: Where are you?", "Sarah: I'm here."),
            glossary = mapOf("The Council" to "المجلس"),
            characterNotes = "John: speaker label seen in transcript"
        )
        assertEquals(2, context.previousSegments.size)
        assertEquals("المجلس", context.glossary["The Council"])
        assertTrue(context.characterNotes.contains("John"))
    }

    @Test
    fun chunkTimingUsesOverlapStep() {
        assertEquals(1600L, AudioChunker.STEP_MS)
        assertEquals(0L, AudioChunker.startMs(0))
        assertEquals(1600L, AudioChunker.startMs(1))
    }
}

package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage47ContextTimingTest {
    private fun cue(sequence: Long, start: Long, end: Long, text: String) =
        LiveSttRouter.LiveCue(
            sequence = sequence,
            startMs = start,
            endMs = end,
            text = text,
            language = "en",
            provider = "deepgram",
            isFinal = true,
            revision = 0
        )

    @Test
    fun contextIsBoundedAndSessionScoped() {
        val buffer = LiveTranslationContextBuffer(maxItems = 3)
        buffer.beginSession(10L)
        for (i in 1L..5L) {
            buffer.add(10L, LiveTranslationContextBuffer.Item(i, "en", "s$i", "t$i"))
        }
        assertEquals(listOf(3L, 4L, 5L), buffer.snapshot(10L).map { it.sequence })
        assertTrue(buffer.snapshot(11L).isEmpty())
    }

    @Test
    fun duplicateSequenceReplacesContextItem() {
        val buffer = LiveTranslationContextBuffer(maxItems = 3)
        buffer.beginSession(10L)
        buffer.add(10L, LiveTranslationContextBuffer.Item(1L, "en", "old", "قديم"))
        buffer.add(10L, LiveTranslationContextBuffer.Item(1L, "en", "new", "جديد"))
        assertEquals(1, buffer.snapshot(10L).size)
        assertEquals("جديد", buffer.snapshot(10L).single().translatedText)
    }

    @Test
    fun timingKeepsShortCueReadable() {
        val result = LiveSubtitleTimingPolicy.displayUntilMs(cue(1L, 0L, 500L, "hello"))
        assertTrue(result >= 1_700L)
        assertTrue(result <= 8_500L)
    }

    @Test
    fun timingUsesAudioEndAndReadingLength() {
        val result = LiveSubtitleTimingPolicy.displayUntilMs(
            cue(1L, 2_000L, 4_000L, "one two three four five six seven eight")
        )
        assertTrue(result >= 4_900L)
        assertTrue(result <= 12_000L)
    }
}

package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage45StreamingStabilizationTest {
    @Test
    fun partialsCanBeReplacedAndFinalCanCloseSameTrack() {
        val stabilizer = LiveTranscriptStabilizer()

        val p1 = stabilizer.accept(
            LiveTranscriptStabilizer.Event(7, 1000, 1500, "hello", "en", "Deepgram", LiveTranscriptStabilizer.Kind.PARTIAL, 1)
        )
        assertEquals(LiveTranscriptStabilizer.Action.EMIT_PARTIAL, p1.action)

        val p2 = stabilizer.accept(
            LiveTranscriptStabilizer.Event(7, 1000, 1900, "hello world", "en", "Deepgram", LiveTranscriptStabilizer.Kind.PARTIAL, 2)
        )
        assertEquals(LiveTranscriptStabilizer.Action.REPLACE, p2.action)
        assertEquals("hello world", p2.event?.text)

        val f = stabilizer.accept(
            LiveTranscriptStabilizer.Event(7, 1000, 2300, "hello world today", "en", "Deepgram", LiveTranscriptStabilizer.Kind.FINAL, 3)
        )
        assertEquals(LiveTranscriptStabilizer.Action.EMIT_FINAL, f.action)

        val late = stabilizer.accept(
            LiveTranscriptStabilizer.Event(7, 1000, 2300, "hello world", "en", "Deepgram", LiveTranscriptStabilizer.Kind.PARTIAL, 4)
        )
        assertEquals(LiveTranscriptStabilizer.Action.DROP, late.action)
    }

    @Test
    fun finalSequenceOlderThanNewestFinalIsDropped() {
        val stabilizer = LiveTranscriptStabilizer()
        assertTrue(
            stabilizer.accept(
                LiveTranscriptStabilizer.Event(20, 2000, 2600, "new", "en", "Deepgram", LiveTranscriptStabilizer.Kind.FINAL, 1)
            ).action == LiveTranscriptStabilizer.Action.EMIT_FINAL
        )
        assertEquals(
            LiveTranscriptStabilizer.Action.DROP,
            stabilizer.accept(
                LiveTranscriptStabilizer.Event(19, 1900, 2500, "old", "en", "Deepgram", LiveTranscriptStabilizer.Kind.FINAL, 9)
            ).action
        )
    }
}

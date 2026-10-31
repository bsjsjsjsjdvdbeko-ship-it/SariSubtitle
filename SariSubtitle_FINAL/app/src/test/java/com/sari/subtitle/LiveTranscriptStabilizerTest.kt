package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptStabilizerTest {
    private fun event(seq: Long, text: String, kind: LiveTranscriptStabilizer.Kind, rev: Int) =
        LiveTranscriptStabilizer.Event(seq, 1000, 2000, text, "en", "test", kind, rev)

    @Test fun partialCanBeReplacedAndFinalLocksTrack() {
        val s = LiveTranscriptStabilizer()
        assertEquals(LiveTranscriptStabilizer.Action.EMIT_PARTIAL, s.accept(event(1, "hello", LiveTranscriptStabilizer.Kind.PARTIAL, 0)).action)
        val replaced = s.accept(event(1, "hello world", LiveTranscriptStabilizer.Kind.PARTIAL, 1))
        assertEquals(LiveTranscriptStabilizer.Action.REPLACE, replaced.action)
        assertNotNull(replaced.event)
        assertEquals(LiveTranscriptStabilizer.Action.EMIT_FINAL, s.accept(event(1, "hello world today", LiveTranscriptStabilizer.Kind.FINAL, 2)).action)
        assertEquals(LiveTranscriptStabilizer.Action.DROP, s.accept(event(1, "hello world today again", LiveTranscriptStabilizer.Kind.PARTIAL, 3)).action)
    }

    @Test fun olderFinalIsDroppedAfterNewerFinal() {
        val s = LiveTranscriptStabilizer()
        assertEquals(LiveTranscriptStabilizer.Action.EMIT_FINAL, s.accept(event(10, "new", LiveTranscriptStabilizer.Kind.FINAL, 0)).action)
        assertEquals(LiveTranscriptStabilizer.Action.DROP, s.accept(event(9, "old", LiveTranscriptStabilizer.Kind.FINAL, 0)).action)
    }

    @Test fun lowerRevisionIsDropped() {
        val s = LiveTranscriptStabilizer()
        s.accept(event(2, "first", LiveTranscriptStabilizer.Kind.PARTIAL, 3))
        assertEquals(LiveTranscriptStabilizer.Action.DROP, s.accept(event(2, "older", LiveTranscriptStabilizer.Kind.PARTIAL, 2)).action)
        assertTrue(s.size() == 1)
    }
}

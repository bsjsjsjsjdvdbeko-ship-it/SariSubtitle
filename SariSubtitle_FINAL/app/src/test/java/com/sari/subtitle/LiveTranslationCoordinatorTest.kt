package com.sari.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranslationCoordinatorTest {
    private fun cue(sequence: Long, revision: Int = 0, final: Boolean = true) =
        LiveSttRouter.LiveCue(
            sequence = sequence,
            startMs = sequence * 1000L,
            endMs = sequence * 1000L + 1500L,
            text = "hello $sequence",
            language = "en",
            provider = "deepgram",
            isFinal = final,
            revision = revision
        )

    @Test
    fun olderSessionIsRejected() {
        val c = LiveTranslationCoordinator()
        c.beginSession(10L)
        assertFalse(c.acceptForTranslation(LiveTranslationCoordinator.Request(9L, cue(1), 1L)).accepted)
    }

    @Test
    fun duplicateRevisionIsRejected() {
        val c = LiveTranslationCoordinator()
        c.beginSession(10L)
        assertTrue(c.acceptForTranslation(LiveTranslationCoordinator.Request(10L, cue(1, 2), 1L)).accepted)
        assertFalse(c.acceptForTranslation(LiveTranslationCoordinator.Request(10L, cue(1, 2), 2L)).accepted)
        assertFalse(c.acceptForTranslation(LiveTranslationCoordinator.Request(10L, cue(1, 1), 3L)).accepted)
    }

    @Test
    fun olderPublishedSequenceIsRejected() {
        val c = LiveTranslationCoordinator()
        c.beginSession(10L)
        assertTrue(c.acceptForPublish(10L, cue(2)).accepted)
        assertFalse(c.acceptForPublish(10L, cue(1)).accepted)
    }

    @Test
    fun partialCannotEnterTranslation() {
        val c = LiveTranslationCoordinator()
        c.beginSession(10L)
        assertFalse(c.acceptForTranslation(LiveTranslationCoordinator.Request(10L, cue(1, final = false), 1L)).accepted)
    }
}

package com.sari.subtitle

import org.junit.Assert.*
import org.junit.Test

class Stage48TimelineTest {
    @Test fun newerCueTrimsPreviousOverlapWindow() {
        val manager = LiveSubtitleTimelineManager()
        manager.beginSession(7L)
        val first = manager.schedule(7L, 1L, 0, 0L, 2_000L, 3_000L)
        assertTrue(first.accepted)
        val second = manager.schedule(7L, 2L, 0, 1_500L, 3_000L, 4_000L)
        assertTrue(second.accepted)
        assertNotNull(second.previousTrimmedUntilMs)
        assertTrue(second.previousTrimmedUntilMs!! < 1_500L)
    }

    @Test fun staleSequenceIsRejected() {
        val manager = LiveSubtitleTimelineManager()
        manager.beginSession(9L)
        assertTrue(manager.schedule(9L, 4L, 0, 0L, 1_000L, 2_000L).accepted)
        val stale = manager.schedule(9L, 3L, 0, 1_000L, 2_000L, 3_000L)
        assertFalse(stale.accepted)
        assertEquals("older_sequence", stale.reason)
    }

    @Test fun duplicateRevisionIsRejected() {
        val manager = LiveSubtitleTimelineManager()
        manager.beginSession(10L)
        assertTrue(manager.schedule(10L, 1L, 2, 0L, 1_000L, 2_000L).accepted)
        val duplicate = manager.schedule(10L, 1L, 2, 0L, 1_000L, 2_000L)
        assertFalse(duplicate.accepted)
        assertEquals("duplicate_revision", duplicate.reason)
    }

    @Test fun oldSessionCannotPublish() {
        val manager = LiveSubtitleTimelineManager()
        manager.beginSession(11L)
        val stale = manager.schedule(10L, 1L, 0, 0L, 1_000L, 2_000L)
        assertFalse(stale.accepted)
        assertEquals("stale_session", stale.reason)
    }
}

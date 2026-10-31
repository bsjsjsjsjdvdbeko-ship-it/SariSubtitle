package com.sari.subtitle

import org.junit.Assert.*
import org.junit.Test

class Stage49SubtitleQueueTest {
    @Test
    fun newerCueReplacesSameSequenceRevision() {
        val q = LiveSubtitleQueue()
        q.beginSession(7L)
        assertTrue(q.offer(LiveSubtitleQueue.Item(7, 1, 0, "old", 0, 1000, 1500)))
        assertTrue(q.offer(LiveSubtitleQueue.Item(7, 1, 1, "new", 0, 1000, 1500)))
        val action = q.advance(500)
        assertEquals("new", (action as LiveSubtitleQueue.Action.Show).item.text)
    }

    @Test
    fun futureCueDoesNotHideCurrentCue() {
        val q = LiveSubtitleQueue()
        q.beginSession(7L)
        q.offer(LiveSubtitleQueue.Item(7, 1, 0, "first", 0, 1000, 1200))
        q.offer(LiveSubtitleQueue.Item(7, 2, 0, "second", 1500, 2500, 3000))
        assertTrue(q.advance(500) is LiveSubtitleQueue.Action.Show)
        assertTrue(q.advance(1200) is LiveSubtitleQueue.Action.Hide)
        assertTrue(q.advance(1600) is LiveSubtitleQueue.Action.Show)
    }

    @Test
    fun staleSessionCannotEnterQueue() {
        val q = LiveSubtitleQueue()
        q.beginSession(9L)
        assertFalse(q.offer(LiveSubtitleQueue.Item(8, 1, 0, "stale", 0, 1000, 1500)))
    }
}

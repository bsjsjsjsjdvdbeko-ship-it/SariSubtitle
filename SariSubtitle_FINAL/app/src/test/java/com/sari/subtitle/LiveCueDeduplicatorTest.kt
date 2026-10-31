package com.sari.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCueDeduplicatorTest {
    @Test fun suppressesExactOverlapDuplicate() {
        val d = LiveCueDeduplicator()
        assertTrue(d.shouldEmit("Hello world", 1000))
        assertFalse(d.shouldEmit("hello world", 2500))
    }

    @Test fun acceptsSameTextAfterWindow() {
        val d = LiveCueDeduplicator(windowMs = 1000)
        assertTrue(d.shouldEmit("hello world", 1000))
        assertTrue(d.shouldEmit("hello world", 2501))
    }
}

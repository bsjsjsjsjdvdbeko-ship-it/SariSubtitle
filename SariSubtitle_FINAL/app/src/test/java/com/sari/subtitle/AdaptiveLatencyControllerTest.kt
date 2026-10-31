package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLatencyControllerTest {
    @Test
    fun highLatencyShrinksChunkRecommendation() {
        val c = AdaptiveLatencyController(targetE2eMs = 1800L)
        repeat(5) { c.recordEndToEnd(4500L) }
        assertTrue(c.recommendedChunkDurationMs() < 2000L)
    }

    @Test
    fun lowLatencyGrowsChunkRecommendationWithinBounds() {
        val c = AdaptiveLatencyController(targetE2eMs = 1800L)
        repeat(5) { c.recordEndToEnd(500L) }
        assertTrue(c.recommendedChunkDurationMs() > 2000L)
        assertTrue(c.recommendedChunkDurationMs() <= 2400L)
    }

    @Test
    fun resetRemovesMeasuredHistory() {
        val c = AdaptiveLatencyController()
        c.recordEndToEnd(4000L)
        c.reset()
        assertEquals(0, c.snapshot().samples)
        assertEquals(null, c.snapshot().ewmaE2eMs)
        assertEquals(2000L, c.recommendedChunkDurationMs())
    }
}

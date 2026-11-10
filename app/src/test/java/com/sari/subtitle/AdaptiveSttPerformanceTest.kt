package com.sari.subtitle

import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSttPerformanceTest {
    @Test
    fun slowSttIncreasesChunkSizeToReduceRequestOverhead() {
        val controller = AdaptiveLatencyController()
        repeat(6) { controller.recordSttProcessing(processingMs = 3_200L, audioDurationMs = 2_000L) }
        assertTrue(controller.recommendedChunkDurationMs() > 2_000L)
        assertTrue(controller.recommendedChunkDurationMs() <= 2_400L)
    }

    @Test
    fun fastSttReducesChunkSizeForLowerSubtitleDelay() {
        val controller = AdaptiveLatencyController()
        repeat(6) { controller.recordSttProcessing(processingMs = 500L, audioDurationMs = 2_000L) }
        assertTrue(controller.recommendedChunkDurationMs() < 2_000L)
        assertTrue(controller.recommendedChunkDurationMs() >= 1_200L)
    }

    @Test
    fun repeatedQueuePressureIsBounded() {
        val controller = AdaptiveLatencyController()
        repeat(20) { controller.recordQueuePressure() }
        assertTrue(controller.recommendedChunkDurationMs() <= 2_400L)
    }
}

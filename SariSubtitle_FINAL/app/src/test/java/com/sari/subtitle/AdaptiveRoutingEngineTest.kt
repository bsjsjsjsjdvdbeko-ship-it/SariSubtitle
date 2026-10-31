package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRoutingEngineTest {
    private fun base(latency: Int = 22, quality: Int = 18) = ProviderScoringEngine.ScoreBreakdown(
        provider = "test", total = 80, health = 35, latency = latency,
        quality = quality, quota = 18, reliability = 15, reason = "test"
    )

    @Test fun shortChunkRewardsMeasuredLowLatency() {
        val context = AdaptiveRoutingEngine.ContextSnapshot(
            task = ProviderScoringEngine.Task.STT,
            chunkDurationMs = 2000L
        )
        assertTrue(AdaptiveRoutingEngine.adjustment("test", base(22), context).points > 0)
    }

    @Test fun fastSpeechUsesOnlyMeasuredSignal() {
        val context = AdaptiveRoutingEngine.ContextSnapshot(
            task = ProviderScoringEngine.Task.STT,
            speechSpeedWpm = 210
        )
        assertTrue(AdaptiveRoutingEngine.adjustment("test", base(22, 19), context).points > 0)
    }

    @Test fun durationParserReadsSrtTimeline() {
        val srt = "1\n00:00:01,000 --> 00:00:03,500\nHello\n\n2\n00:00:05,000 --> 00:00:08,000\nWorld"
        assertEquals(7000L, AdaptiveRoutingEngine.srtDurationMs(srt))
    }

    @Test fun wordsPerMinuteRequiresPositiveDuration() {
        assertEquals(null, AdaptiveRoutingEngine.wordsPerMinute("hello world", 0))
        assertEquals(60, AdaptiveRoutingEngine.wordsPerMinute("one two", 2000))
    }
}

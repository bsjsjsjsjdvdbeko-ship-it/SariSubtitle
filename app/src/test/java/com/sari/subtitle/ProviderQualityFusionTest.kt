package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderQualityFusionTest {
    private fun record(
        score: Int?,
        samples: Int,
        ageMs: Long = 0L
    ) = ProviderBenchmarkStore.Record(
        provider = "test",
        runAtMs = 1_000L - ageMs,
        samples = samples,
        successes = samples,
        failures = 0,
        averageLatencyMs = 300L,
        qualityScore = 90,
        qualitySamples = samples,
        qualityReasons = emptyList(),
        referenceBleu = score?.div(100.0),
        referenceChrf = score?.div(100.0),
        referenceScore = score,
        referenceSamples = samples
    )

    @Test
    fun `translation uses recent reference benchmark with bounded contribution`() {
        val result = ProviderQualityFusion.referenceSignal(record(90, 10), nowMs = 1_000L)
        assertTrue(result.usable)
        assertEquals(4, result.contribution)
    }

    @Test
    fun `low reference quality cannot dominate the score`() {
        val result = ProviderQualityFusion.referenceSignal(record(0, 10), nowMs = 1_000L)
        assertTrue(result.usable)
        assertEquals(-4, result.contribution)
    }

    @Test
    fun `warming up reference remains neutral`() {
        val result = ProviderQualityFusion.referenceSignal(record(100, 2), nowMs = 1_000L)
        assertFalse(result.usable)
        assertEquals(0, result.contribution)
    }

    @Test
    fun `stale reference remains neutral`() {
        val result = ProviderQualityFusion.referenceSignal(
            record(100, 10, ageMs = 8L * 24L * 60L * 60L * 1000L),
            nowMs = 1_000L
        )
        assertFalse(result.usable)
        assertEquals(0, result.contribution)
    }

    @Test
    fun `reference is not used for STT`() {
        val result = ProviderQualityFusion.fusedTotal(
            50,
            record(100, 10),
            ProviderScoringEngine.Task.STT,
            nowMs = 1_000L
        )
        assertEquals(50, result.first)
        assertEquals(0, result.second.contribution)
    }
}

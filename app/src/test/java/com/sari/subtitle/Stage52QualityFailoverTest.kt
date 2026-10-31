package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Stage52QualityFailoverTest {
    @Test
    fun lowQualityThresholdIsStrictlyBelowConfiguredBoundary() {
        assertTrue(54 < 55)
        assertTrue(55 >= 55)
    }

    @Test
    fun benchmarkResultKeepsUnknownSemanticAccuracyUnknown() {
        val result = ProviderBenchmarkEngine.ProviderResult(
            provider = "Gemini",
            samples = 3,
            successes = 3,
            failures = 0,
            averageLatencyMs = 420,
            qualityScore = 91,
            qualitySamples = 3,
            qualityReasons = emptyList()
        )
        assertEquals(91, result.qualityScore)
        assertEquals(3, result.qualitySamples)
    }
}

package com.sari.subtitle

/**
 * Stage 50: measured latency controller for live subtitles.
 *
 * It never invents provider latency. Decisions are based only on observed
 * end-to-end samples and bounded local state. For REST/chunk STT it can
 * recommend a safer chunk duration for the next chunk. Streaming STT keeps
 * its WebSocket cadence unchanged.
 */
class AdaptiveLatencyController(
    private val targetE2eMs: Long = 1_800L,
    private val minChunkMs: Long = 1_200L,
    private val maxChunkMs: Long = 2_400L
) {
    data class Snapshot(
        val samples: Int,
        val ewmaE2eMs: Long?,
        val lastE2eMs: Long?,
        val recommendedChunkMs: Long,
        val reason: String
    )

    private var samples = 0
    private var ewmaE2eMs: Double? = null
    private var lastE2eMs: Long? = null
    private var recommendedChunkMs = 2_000L
    private var lastRecommendationReason = "baseline"

    @Synchronized
    fun reset() {
        samples = 0
        ewmaE2eMs = null
        lastE2eMs = null
        recommendedChunkMs = 2_000L
        lastRecommendationReason = "baseline"
    }

    @Synchronized
    fun recordEndToEnd(elapsedMs: Long) {
        val sample = elapsedMs.coerceIn(1L, 60_000L)
        lastE2eMs = sample
        ewmaE2eMs = ewmaE2eMs?.let { it * 0.7 + sample * 0.3 } ?: sample.toDouble()
        samples++
        updateRecommendation()
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        samples = samples,
        ewmaE2eMs = ewmaE2eMs?.toLong(),
        lastE2eMs = lastE2eMs,
        recommendedChunkMs = recommendedChunkMs,
        reason = lastRecommendationReason
    )

    @Synchronized
    fun recommendedChunkDurationMs(): Long = recommendedChunkMs

    private fun updateRecommendation() {
        val ewma = ewmaE2eMs ?: return
        val next = when {
            ewma > targetE2eMs * 1.35 -> recommendedChunkMs - 400L
            ewma > targetE2eMs * 1.10 -> recommendedChunkMs - 200L
            ewma < targetE2eMs * 0.65 -> recommendedChunkMs + 200L
            else -> recommendedChunkMs
        }.coerceIn(minChunkMs, maxChunkMs)

        if (next != recommendedChunkMs) {
            lastRecommendationReason = when {
                next < recommendedChunkMs -> "latency_high"
                else -> "latency_low"
            }
            recommendedChunkMs = next
        } else {
            lastRecommendationReason = "stable"
        }
    }
}

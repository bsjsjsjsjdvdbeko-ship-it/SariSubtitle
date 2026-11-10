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
    private var ewmaSttProcessingMs: Double? = null
    private var ewmaAudioDurationMs: Double? = null
    private var sttSamples = 0
    private var queuePressureEvents = 0
    private var lastRecommendationReason = "baseline"

    @Synchronized
    fun reset() {
        samples = 0
        ewmaE2eMs = null
        lastE2eMs = null
        recommendedChunkMs = 2_000L
        ewmaSttProcessingMs = null
        ewmaAudioDurationMs = null
        sttSamples = 0
        queuePressureEvents = 0
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

    /** Record measured STT wall time against the amount of audio processed. */
    @Synchronized
    fun recordSttProcessing(processingMs: Long, audioDurationMs: Long) {
        if (processingMs <= 0L || audioDurationMs <= 0L) return
        val processing = processingMs.coerceAtMost(180_000L).toDouble()
        val audio = audioDurationMs.coerceIn(250L, 30_000L).toDouble()
        ewmaSttProcessingMs = ewmaSttProcessingMs?.let { it * 0.75 + processing * 0.25 } ?: processing
        ewmaAudioDurationMs = ewmaAudioDurationMs?.let { it * 0.75 + audio * 0.25 } ?: audio
        sttSamples++
        updateSttRecommendation()
    }

    /** Queue overflow is a measured backpressure signal; use larger chunks to reduce request overhead. */
    @Synchronized
    fun recordQueuePressure() {
        queuePressureEvents++
        if (queuePressureEvents >= 2) {
            val next = (recommendedChunkMs + 200L).coerceAtMost(maxChunkMs)
            if (next != recommendedChunkMs) {
                recommendedChunkMs = next
                lastRecommendationReason = "queue_pressure"
            }
            queuePressureEvents = 0
        }
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

    private fun updateSttRecommendation() {
        val processing = ewmaSttProcessingMs ?: return
        val audio = ewmaAudioDurationMs ?: return
        // If transcription consistently takes longer than the audio chunk, reduce
        // request frequency with a slightly larger chunk. If it is comfortably fast,
        // shorten chunks to improve subtitle responsiveness.
        val ratio = processing / audio
        val previous = recommendedChunkMs
        val next = when {
            ratio > 1.35 -> recommendedChunkMs + 200L
            ratio < 0.55 -> recommendedChunkMs - 200L
            else -> recommendedChunkMs
        }.coerceIn(minChunkMs, maxChunkMs)
        if (next != previous) {
            recommendedChunkMs = next
            lastRecommendationReason = if (next > previous) "stt_throughput_low" else "stt_fast"
        } else {
            lastRecommendationReason = "stt_stable"
        }
    }

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

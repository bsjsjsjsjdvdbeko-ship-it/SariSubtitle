package com.sari.subtitle

import kotlin.math.roundToInt

/**
 * Stage 55: measured quality fusion policy.
 *
 * Ground-truth benchmark quality is deliberately a bounded routing signal, not a
 * replacement for live quality. It is used only for Translation and only when a
 * recent benchmark contains enough reference samples.
 */
object ProviderQualityFusion {
    private const val MIN_REFERENCE_SAMPLES = 3
    private const val MAX_REFERENCE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val NEUTRAL_REFERENCE_SCORE = 50
    private const val MAX_REFERENCE_CONTRIBUTION = 4

    data class ReferenceSignal(
        val contribution: Int,
        val score: Int?,
        val samples: Int,
        val ageMs: Long?,
        val usable: Boolean,
        val reason: String
    )

    fun referenceSignal(
        benchmark: ProviderBenchmarkStore.Record?,
        nowMs: Long = System.currentTimeMillis()
    ): ReferenceSignal {
        if (benchmark == null) {
            return ReferenceSignal(0, null, 0, null, false, "reference=unknown")
        }

        val age = (nowMs - benchmark.runAtMs).coerceAtLeast(0L)
        val score = benchmark.referenceScore
        if (score == null || benchmark.referenceSamples < MIN_REFERENCE_SAMPLES) {
            return ReferenceSignal(
                0,
                score,
                benchmark.referenceSamples,
                age,
                false,
                "reference=warming_up(samples=${benchmark.referenceSamples})"
            )
        }
        if (age > MAX_REFERENCE_AGE_MS) {
            return ReferenceSignal(
                0,
                score,
                benchmark.referenceSamples,
                age,
                false,
                "reference=stale(age=${age / 86_400_000L}d)"
            )
        }

        // More reference cases increase confidence, but the benchmark must never
        // overpower live quality/latency/reliability. Neutral is 50/100.
        val confidence = when {
            benchmark.referenceSamples >= 10 -> 1.0
            benchmark.referenceSamples >= 5 -> 0.75
            else -> 0.5
        }
        val raw = ((score - NEUTRAL_REFERENCE_SCORE) / 10.0 * confidence).roundToInt()
        val contribution = raw.coerceIn(-MAX_REFERENCE_CONTRIBUTION, MAX_REFERENCE_CONTRIBUTION)
        val direction = when {
            contribution > 0 -> "positive"
            contribution < 0 -> "negative"
            else -> "neutral"
        }
        return ReferenceSignal(
            contribution,
            score,
            benchmark.referenceSamples,
            age,
            true,
            "reference=$score/${benchmark.referenceSamples},fusion=$contribution($direction)"
        )
    }

    fun fusedTotal(
        baseTotal: Int,
        benchmark: ProviderBenchmarkStore.Record?,
        task: ProviderScoringEngine.Task,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Int, ReferenceSignal> {
        if (task != ProviderScoringEngine.Task.TRANSLATION) {
            return baseTotal to ReferenceSignal(0, null, 0, null, false, "reference=stt_not_applicable")
        }
        val signal = referenceSignal(benchmark, nowMs)
        return (baseTotal + signal.contribution) to signal
    }
}

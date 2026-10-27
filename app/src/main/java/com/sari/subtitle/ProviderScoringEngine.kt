package com.sari.subtitle

import android.content.Context
import kotlin.math.roundToInt

/**
 * Stage 39: quality/quota-aware scoring.
 *
 * Important: semantic accuracy is NOT guessed. qualityScore is used only when a real
 * benchmark/structural validation explicitly records it. Unknown quality contributes neutral weight.
 */
object ProviderScoringEngine {
    enum class Task { STT, TRANSLATION }

    data class ScoreBreakdown(
        val provider: String,
        val total: Int,
        val health: Int,
        val latency: Int,
        val quality: Int,
        val quota: Int,
        val reliability: Int,
        val reference: Int,
        val reason: String
    )

    fun score(context: Context, provider: String, task: Task): ScoreBreakdown {
        val health = ProviderHealthStore.get(context, provider)
        val metrics = ProviderMetricsStore.get(context, provider)
        val circuit = ProviderRoutingEngine.circuit(context, provider)
        if (circuit == ProviderRoutingEngine.CircuitState.OPEN) {
            return ScoreBreakdown(provider, Int.MIN_VALUE, 0, 0, 0, 0, 0, 0, "Circuit OPEN")
        }

        val healthScore = when (health?.state) {
            ProviderHealthStore.State.READY -> 35
            ProviderHealthStore.State.RATE_LIMITED -> 8
            ProviderHealthStore.State.SERVER_ERROR -> 12
            ProviderHealthStore.State.NETWORK_ERROR -> 15
            ProviderHealthStore.State.ERROR -> 10
            ProviderHealthStore.State.AUTH_ERROR -> -100
            ProviderHealthStore.State.NOT_CONFIGURED -> -100
            null -> 22
        }

        val latencyMs = metrics?.latencyEwmaMs ?: health?.latencyMs
        val latencyScore = when {
            latencyMs == null -> 18
            latencyMs <= 250 -> 25
            latencyMs <= 500 -> 22
            latencyMs <= 1000 -> 18
            latencyMs <= 2000 -> 12
            else -> 5
        }

        val qualityScore = metrics?.takeIf { it.qualityScore != null && it.qualitySamples >= 3 }?.qualityScore?.let { q ->
            // Quality affects routing only after at least three independent observations.
            // Until then, keep a neutral contribution instead of overreacting to one cue.
            val weight = if (task == Task.STT) 0.30 else 0.25
            (q * weight).roundToInt()
        } ?: 18

        val quotaScore = quotaScore(metrics)
        val reliabilityScore = when {
            metrics == null -> 15
            metrics.qualityDegradationCount == 0 && metrics.successCount >= metrics.failureCount * 5 + 1 -> 20
            metrics.qualityDegradationCount <= 1 && metrics.successCount >= metrics.failureCount * 2 + 1 -> 16
            metrics.failureCount == 0 -> 17
            else -> 8
        }
        val qualityPenalty = when {
            metrics == null -> 0
            metrics.qualityDegradationCount >= 5 -> 8
            metrics.qualityDegradationCount >= 3 -> 5
            metrics.qualityDegradationCount >= 1 -> 2
            else -> 0
        }

        val baseTotal = healthScore + latencyScore + qualityScore + quotaScore + reliabilityScore - qualityPenalty
        val benchmark = if (task == Task.TRANSLATION) ProviderBenchmarkStore.get(context, provider) else null
        val fused = ProviderQualityFusion.fusedTotal(baseTotal, benchmark, task)
        val total = fused.first
        val referenceContribution = fused.second.contribution
        val reason = buildString {
            append("health=$healthScore")
            append(" latency=${latencyMs?.let { "${it}ms" } ?: "unknown"}")
            val measuredQuality = metrics?.takeIf { it.qualityScore != null && it.qualitySamples >= 3 }?.qualityScore
            append(" quality=${measuredQuality?.toString() ?: "warming_up"}")
            append(" quota=${quotaLabel(metrics)}")
            append(" reliability=$reliabilityScore")
            append(" qualityDegradations=${metrics?.qualityDegradationCount ?: 0}")
            append(" ${fused.second.reason}")
        }
        return ScoreBreakdown(provider, total, healthScore, latencyScore, qualityScore, quotaScore, reliabilityScore, referenceContribution, reason)
    }

    fun rank(context: Context, providers: List<String>, task: Task, configured: (String) -> Boolean): List<ScoreBreakdown> =
        providers.distinct().filter(configured).map { score(context, it, task) }
            .filter { it.total != Int.MIN_VALUE }
            .sortedByDescending { it.total }

    private fun quotaScore(m: ProviderMetricsStore.Metrics?): Int {
        val remaining = m?.quotaRemainingRequests ?: return 18 // unknown: neutral
        val limit = m.quotaLimitRequests ?: return 16
        if (limit <= 0L) return 16
        val pct = remaining.toDouble() / limit.toDouble()
        return when {
            pct >= 0.50 -> 20
            pct >= 0.20 -> 15
            pct >= 0.05 -> 8
            else -> 2
        }
    }

    private fun quotaLabel(m: ProviderMetricsStore.Metrics?): String {
        val r = m?.quotaRemainingRequests ?: return "unknown"
        val l = m.quotaLimitRequests ?: return "${r}?"
        return "$r/$l"
    }
}

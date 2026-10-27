package com.sari.subtitle

import android.content.Context
import java.util.concurrent.CancellationException

/**
 * Stage 58: executes one provider decision as a controlled failover transaction.
 *
 * The controller owns only orchestration. It does not know API keys, audio formats,
 * provider response schemas, or translation prompts. Callers supply the actual request.
 *
 * Flow:
 * Decision -> Primary -> validate -> quality gate -> ACCEPT
 *                         |                 |
 *                         +---- error -----+----> NEXT eligible provider
 *
 * A quality rejection is intentionally NOT recorded as an HTTP failure and therefore
 * does not open the circuit breaker. A transport/auth/server failure is recorded through
 * ProviderRoutingEngine.recordFailure().
 */
object ProviderDecisionFailoverController {
    const val DEFAULT_QUALITY_THRESHOLD = 55

    data class Attempt(
        val provider: String,
        val latencyMs: Long,
        val qualityScore: Int?,
        val accepted: Boolean,
        val reason: String
    )

    data class Success<T>(
        val provider: String,
        val value: T,
        val latencyMs: Long,
        val qualityScore: Int?,
        val attempts: List<Attempt>
    )

    class ExhaustedException(
        message: String,
        val attempts: List<Attempt>,
        cause: Throwable? = null
    ) : IllegalStateException(message, cause)

    /**
     * Run the candidates already selected by ProviderDecisionEngine.
     * The operation is called only for eligible candidates, in decision order.
     */
    fun <T> execute(
        context: Context,
        decision: ProviderDecisionEngine.Result,
        qualityThreshold: Int = DEFAULT_QUALITY_THRESHOLD,
        operation: (provider: String) -> T,
        validate: (value: T) -> Unit = {},
        quality: (value: T) -> Int? = { null },
        successDetail: (provider: String, latencyMs: Long) -> String = { provider, latency ->
            "نجح الطلب الفعلي عبر $provider خلال ${latency}ms"
        }
    ): Success<T> {
        val attempts = ArrayList<Attempt>()
        var lastError: Throwable? = null
        val candidates = decision.decisions.filter { it.eligible }

        if (candidates.isEmpty()) {
            val reason = decision.selectionReason.ifBlank { "لا يوجد Provider مؤهل" }
            recordTrace(context, decision, null, "no_eligible_provider:$reason", emptyList())
            throw ExhaustedException(reason, emptyList())
        }

        candidates.forEachIndexed { index, candidate ->
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                val value = operation(candidate.provider)
                validate(value)
                val latency = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                val qualityScore = quality(value)?.coerceIn(0, 100)
                val hasNext = index < candidates.lastIndex

                if (qualityScore != null) {
                    ProviderMetricsStore.recordQuality(context, candidate.provider, qualityScore)
                }

                val rejectForQuality = qualityScore != null &&
                    qualityScore < qualityThreshold &&
                    hasNext

                if (rejectForQuality) {
                    val reason = "quality_below_threshold:$qualityScore<$qualityThreshold"
                    ProviderRoutingEngine.recordQualityDegradation(
                        context,
                        candidate.provider,
                        qualityScore!!,
                        "$reason; سيتم تجربة Provider التالي"
                    )
                    attempts += Attempt(candidate.provider, latency, qualityScore, false, reason)
                    recordTrace(context, decision, candidate.provider, "failover:$reason", attempts)
                    return@forEachIndexed
                }

                ProviderRoutingEngine.recordSuccess(
                    context,
                    candidate.provider,
                    latency,
                    successDetail(candidate.provider, latency)
                )
                attempts += Attempt(candidate.provider, latency, qualityScore, true, "accepted")
                recordTrace(context, decision, candidate.provider, "accepted", attempts)
                return Success(candidate.provider, value, latency, qualityScore, attempts.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val latency = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                lastError = e
                ProviderRoutingEngine.recordFailure(context, candidate.provider, e, latency)
                attempts += Attempt(
                    provider = candidate.provider,
                    latencyMs = latency,
                    qualityScore = null,
                    accepted = false,
                    reason = "request_failed:${safeReason(e)}"
                )
                recordTrace(context, decision, candidate.provider, "failover:request_failed", attempts)
            }
        }

        throw ExhaustedException(
            "فشل جميع Providers المؤهلين؛ attempts=${attempts.size}",
            attempts.toList(),
            lastError
        )
    }

    /** Deterministic pure rule used by tests and documentation. */
    fun shouldFailover(qualityScore: Int?, threshold: Int, hasNext: Boolean): Boolean =
        qualityScore != null && qualityScore < threshold && hasNext

    private fun recordTrace(
        context: Context,
        decision: ProviderDecisionEngine.Result,
        provider: String?,
        reason: String,
        attempts: List<Attempt>
    ) {
        val attemptSummary = attempts.takeLast(8).map {
            "${it.provider}:${it.latencyMs}ms:${it.qualityScore ?: "na"}:${if (it.accepted) "accepted" else "rejected"}"
        }
        ProviderDecisionTraceStore.append(
            context = context,
            task = decision.task,
            selected = provider ?: decision.selected?.provider,
            reason = reason.take(300),
            candidates = (decision.decisions.map {
                "${it.provider}:${it.score}:${if (it.eligible) "eligible" else "blocked"}"
            } + attemptSummary).take(16)
        )
    }

    private fun safeReason(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName)
            .replace(Regex("\\s+"), " ")
            .take(180)
}

package com.sari.subtitle

import android.content.Context
import kotlin.math.max

/**
 * Stage 38: one deterministic routing policy for cloud providers.
 *
 * Rules:
 * 1) A provider is eligible when it is configured and its circuit is not OPEN.
 * 2) A successful Stage-37 health check is preferred over an unknown provider.
 * 3) Lower measured latency wins, but the score also penalizes unhealthy states.
 * 4) AUTH_ERROR is never retried automatically until the next explicit health check.
 * 5) RATE_LIMITED respects Retry-After when available.
 * 6) After repeated runtime failures the circuit opens temporarily; after the cooldown
 *    it becomes HALF_OPEN and permits one probe.
 *
 * This class does not store secrets, transcripts, audio or provider responses.
 */
object ProviderRoutingEngine {
    enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    data class Candidate(
        val id: String,
        val configured: Boolean,
        val health: ProviderHealthStore.Health?,
        val circuit: CircuitState,
        val score: Int,
        val reason: String
    )

    private const val OPEN_AFTER_FAILURES = 3
    private const val OPEN_COOLDOWN_MS = 60_000L
    private const val STALE_HEALTH_MS = 30 * 60_000L

    fun circuit(context: Context, provider: String): CircuitState {
        val h = ProviderHealthStore.get(context, provider) ?: return CircuitState.CLOSED
        val now = System.currentTimeMillis()
        return when {
            h.circuitOpenUntil > now -> CircuitState.OPEN
            h.circuitOpenUntil > 0L -> CircuitState.HALF_OPEN
            h.state == ProviderHealthStore.State.RATE_LIMITED &&
                h.retryAfterSec > 0L && h.checkedAt + h.retryAfterSec * 1000L > now -> CircuitState.OPEN
            else -> CircuitState.CLOSED
        }
    }

    fun rank(
        context: Context,
        providers: List<String>,
        configured: (String) -> Boolean,
        forceProvider: String? = null,
        task: ProviderScoringEngine.Task = ProviderScoringEngine.Task.STT,
        adaptiveContext: AdaptiveRoutingEngine.ContextSnapshot? = null
    ): List<Candidate> {
        val result = ProviderDecisionEngine.decide(
            context = context,
            providers = providers,
            configured = configured,
            forceProvider = forceProvider,
            task = task,
            adaptiveContext = adaptiveContext,
            recordTrace = true
        )
        return result.decisions.map { decision ->
            val health = ProviderHealthStore.get(context, decision.provider)
            Candidate(
                id = decision.provider,
                configured = true,
                health = health,
                circuit = decision.circuit,
                score = decision.score,
                reason = decision.reason
            )
        }
    }

    /** Returns the first provider that is not OPEN and not known-auth-invalid. */
    fun choose(
        context: Context,
        providers: List<String>,
        configured: (String) -> Boolean,
        forceProvider: String? = null,
        task: ProviderScoringEngine.Task = ProviderScoringEngine.Task.STT,
        adaptiveContext: AdaptiveRoutingEngine.ContextSnapshot? = null
    ): List<Candidate> = rank(context, providers, configured, forceProvider, task, adaptiveContext)
        .filter { it.circuit != CircuitState.OPEN && it.health?.state != ProviderHealthStore.State.AUTH_ERROR }

    fun recordSuccess(context: Context, provider: String, latencyMs: Long, detail: String = "نجح الطلب الفعلي") {
        val old = ProviderHealthStore.get(context, provider)
        ProviderMetricsStore.recordSuccess(context, provider, latencyMs)
        ProviderHealthStore.save(context, ProviderHealthStore.Health(
            provider = provider,
            state = ProviderHealthStore.State.READY,
            latencyMs = max(0L, latencyMs),
            checkedAt = System.currentTimeMillis(),
            httpCode = 200,
            detail = detail,
            retryAfterSec = 0L,
            failureCount = 0,
            circuitOpenUntil = 0L,
            quotaRemainingRequests = old?.quotaRemainingRequests,
            quotaLimitRequests = old?.quotaLimitRequests,
            quotaResetAtMs = old?.quotaResetAtMs
        ))
    }

    fun recordFailure(context: Context, provider: String, error: Throwable?, latencyMs: Long = 0L, httpCode: Int = 0) {
        val previous = ProviderHealthStore.get(context, provider)
        val now = System.currentTimeMillis()
        val state = ProviderHealthStore.stateFromError(error?.message.orEmpty(), httpCode)
        val failures = (previous?.failureCount ?: 0) + 1
        val open = state == ProviderHealthStore.State.AUTH_ERROR ||
            failures >= OPEN_AFTER_FAILURES
        val openUntil = if (open && state != ProviderHealthStore.State.AUTH_ERROR) now + OPEN_COOLDOWN_MS else 0L
        ProviderMetricsStore.recordFailure(context, provider)
        ProviderHealthStore.save(context, ProviderHealthStore.Health(
            provider = provider,
            state = state,
            latencyMs = max(0L, latencyMs),
            checkedAt = now,
            httpCode = httpCode,
            detail = (error?.message ?: "فشل الطلب الفعلي").take(220),
            retryAfterSec = previous?.retryAfterSec ?: 0L,
            failureCount = failures,
            quotaRemainingRequests = previous?.quotaRemainingRequests,
            quotaLimitRequests = previous?.quotaLimitRequests,
            quotaResetAtMs = previous?.quotaResetAtMs,
            circuitOpenUntil = openUntil
        ))
    }

    /** Records a measured quality problem without pretending the HTTP request failed. */
    fun recordQualityDegradation(context: Context, provider: String, score: Int, detail: String) {
        ProviderMetricsStore.recordQualityDegradation(context, provider)
        val previous = ProviderHealthStore.get(context, provider)
        if (previous != null && previous.state != ProviderHealthStore.State.AUTH_ERROR) {
            ProviderHealthStore.save(
                context,
                previous.copy(
                    detail = detail.take(220),
                    checkedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun isUsable(context: Context, provider: String): Boolean {
        val health = ProviderHealthStore.get(context, provider) ?: return true
        if (health.state == ProviderHealthStore.State.AUTH_ERROR) return false
        return circuit(context, provider) != CircuitState.OPEN
    }


}

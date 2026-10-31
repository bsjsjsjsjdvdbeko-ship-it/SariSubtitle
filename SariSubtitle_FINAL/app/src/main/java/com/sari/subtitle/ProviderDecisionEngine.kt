package com.sari.subtitle

import android.content.Context

/**
 * Stage 56: deterministic provider decision engine.
 * It explains eligibility, ordering and failover without storing secrets, audio or text.
 */
object ProviderDecisionEngine {
    data class Decision(
        val provider: String,
        val eligible: Boolean,
        val score: Int,
        val reason: String,
        val circuit: ProviderRoutingEngine.CircuitState,
        val healthState: ProviderHealthStore.State?
    )

    data class Result(
        val task: ProviderScoringEngine.Task,
        val decisions: List<Decision>,
        val selected: Decision?,
        val selectionReason: String
    )

    fun decide(
        context: Context,
        providers: List<String>,
        configured: (String) -> Boolean,
        forceProvider: String? = null,
        task: ProviderScoringEngine.Task = ProviderScoringEngine.Task.STT,
        adaptiveContext: AdaptiveRoutingEngine.ContextSnapshot? = null,
        recordTrace: Boolean = true
    ): Result {
        val decisions = providers.distinct().mapNotNull { id ->
            if (!configured(id)) return@mapNotNull null
            val health = ProviderHealthStore.get(context, id)
            val circuit = ProviderRoutingEngine.circuit(context, id)
            val base = ProviderScoringEngine.score(context, id, task)
            val adjustment = adaptiveContext?.let { AdaptiveRoutingEngine.adjustment(id, base, it) }
            val score = base.total + (adjustment?.points ?: 0)
            val eligible = circuit != ProviderRoutingEngine.CircuitState.OPEN &&
                health?.state != ProviderHealthStore.State.AUTH_ERROR &&
                base.total != Int.MIN_VALUE
            val reason = explain(health?.state, circuit, base.reason, adjustment?.reasons.orEmpty(), eligible)
            Decision(id, eligible, score, reason, circuit, health?.state)
        }.sortedWith(
            compareByDescending<Decision> { it.provider == forceProvider && it.eligible }
                .thenByDescending { it.eligible }
                .thenByDescending { it.circuit == ProviderRoutingEngine.CircuitState.HALF_OPEN }
                .thenByDescending { it.score }
        )

        val eligible = decisions.filter { it.eligible }
        val selected = eligible.firstOrNull()
        val selectionReason = when {
            selected == null -> "لا يوجد Provider مؤهل"
            forceProvider != null && selected.provider == forceProvider -> "تم احترام المزود المحدد يدويًا: $forceProvider"
            selected.circuit == ProviderRoutingEngine.CircuitState.HALF_OPEN -> "تم اختيار HALF_OPEN كمسبار تعافٍ"
            else -> "أعلى Score مؤهل بعد Health + Latency + Quality + Quota + Reliability + Fusion"
        }

        if (recordTrace) {
            ProviderDecisionTraceStore.append(
                context,
                task,
                selected?.provider,
                selectionReason,
                decisions.map { "${it.provider}:${it.score}:${if (it.eligible) "eligible" else "blocked"}" }
            )
        }
        return Result(task, decisions, selected, selectionReason)
    }

    private fun explain(
        health: ProviderHealthStore.State?,
        circuit: ProviderRoutingEngine.CircuitState,
        scoreReason: String,
        adaptiveReasons: List<String>,
        eligible: Boolean
    ): String = buildString {
        when {
            circuit == ProviderRoutingEngine.CircuitState.OPEN -> append("blocked:circuit_open")
            health == ProviderHealthStore.State.AUTH_ERROR -> append("blocked:auth_error")
            !eligible -> append("blocked:score_or_configuration")
            circuit == ProviderRoutingEngine.CircuitState.HALF_OPEN -> append("probe:half_open")
            else -> append("eligible")
        }
        append("; $scoreReason")
        if (adaptiveReasons.isNotEmpty()) append("; adaptive=${adaptiveReasons.joinToString(",")}")
    }
}

package com.sari.subtitle

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit CLOSED/OPEN/HALF_OPEN semantics for one logical provider key. */
enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

class CircuitBreakerController(private val dao: ProviderMetricsDao) {
    private val probes = ConcurrentHashMap<String, AtomicBoolean>()

    suspend fun state(key: String, now: Long = System.currentTimeMillis()): CircuitState {
        val until = dao.circuitUntil(key) ?: return CircuitState.CLOSED
        return when {
            until <= 0L -> CircuitState.CLOSED
            until > now -> CircuitState.OPEN
            else -> CircuitState.HALF_OPEN
        }
    }

    /** Allows normal calls in CLOSED and exactly one probe in HALF_OPEN. */
    suspend fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        return when (state(key, now)) {
            CircuitState.CLOSED -> true
            CircuitState.OPEN -> false
            CircuitState.HALF_OPEN -> probes.computeIfAbsent(key) { AtomicBoolean(false) }.compareAndSet(false, true)
        }
    }

    fun releaseProbe(key: String) { probes.remove(key) }
    suspend fun close(key: String) { probes.remove(key); dao.closeCircuit(key) }
    suspend fun open(key: String, until: Long) { probes.remove(key); dao.openCircuit(key, until) }
}

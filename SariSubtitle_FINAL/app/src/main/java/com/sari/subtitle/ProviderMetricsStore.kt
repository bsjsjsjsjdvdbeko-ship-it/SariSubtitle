package com.sari.subtitle

import android.content.Context
import org.json.JSONObject

/**
 * Stage 39: observed provider metrics. No API keys, audio or transcript data are stored.
 * Quota values are nullable because many providers do not expose standard rate-limit headers.
 */
object ProviderMetricsStore {
    private const val PREFS = "sari_provider_metrics_v1"
    private const val KEY = "metrics"

    data class Metrics(
        val provider: String,
        val qualityScore: Int? = null, // only an explicitly measured structural/benchmark score
        val qualitySamples: Int = 0,
        val qualityDegradationCount: Int = 0,
        val latencyEwmaMs: Long? = null,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val quotaRemainingRequests: Long? = null,
        val quotaLimitRequests: Long? = null,
        val quotaResetAtMs: Long? = null,
        val observedAt: Long = 0L
    )

    @Synchronized fun get(context: Context, provider: String): Metrics? {
        val root = read(context)
        val o = root.optJSONObject(provider) ?: return null
        return Metrics(
            provider = provider,
            qualityScore = if (o.has("qualityScore") && !o.isNull("qualityScore")) o.optInt("qualityScore") else null,
            qualitySamples = o.optInt("qualitySamples", 0),
            qualityDegradationCount = o.optInt("qualityDegradationCount", 0),
            latencyEwmaMs = if (o.has("latencyEwmaMs") && !o.isNull("latencyEwmaMs")) o.optLong("latencyEwmaMs") else null,
            successCount = o.optInt("successCount", 0),
            failureCount = o.optInt("failureCount", 0),
            quotaRemainingRequests = if (o.has("quotaRemainingRequests") && !o.isNull("quotaRemainingRequests")) o.optLong("quotaRemainingRequests") else null,
            quotaLimitRequests = if (o.has("quotaLimitRequests") && !o.isNull("quotaLimitRequests")) o.optLong("quotaLimitRequests") else null,
            quotaResetAtMs = if (o.has("quotaResetAtMs") && !o.isNull("quotaResetAtMs")) o.optLong("quotaResetAtMs") else null,
            observedAt = o.optLong("observedAt", 0L)
        )
    }

    @Synchronized fun recordSuccess(context: Context, provider: String, latencyMs: Long) {
        val old = get(context, provider) ?: Metrics(provider)
        val latency = latencyMs.coerceAtLeast(0L)
        val ewma = old.latencyEwmaMs?.let { (it * 0.7 + latency * 0.3).toLong() } ?: latency
        save(context, old.copy(latencyEwmaMs = ewma, successCount = old.successCount + 1, observedAt = System.currentTimeMillis()))
    }

    @Synchronized fun recordFailure(context: Context, provider: String) {
        val old = get(context, provider) ?: Metrics(provider)
        save(context, old.copy(failureCount = old.failureCount + 1, observedAt = System.currentTimeMillis()))
    }

    @Synchronized fun recordQuality(context: Context, provider: String, score: Int) {
        val old = get(context, provider) ?: Metrics(provider)
        val value = score.coerceIn(0, 100)
        val samples = old.qualitySamples + 1
        val average = old.qualityScore?.let { ((it * old.qualitySamples) + value) / samples } ?: value
        save(context, old.copy(qualityScore = average, qualitySamples = samples, observedAt = System.currentTimeMillis()))
    }

    @Synchronized fun recordQualityDegradation(context: Context, provider: String) {
        val old = get(context, provider) ?: Metrics(provider)
        save(
            context,
            old.copy(
                qualityDegradationCount = old.qualityDegradationCount + 1,
                observedAt = System.currentTimeMillis()
            )
        )
    }

    @Synchronized fun recordQuota(context: Context, provider: String, remaining: Long?, limit: Long?, resetAtMs: Long?) {
        val old = get(context, provider) ?: Metrics(provider)
        save(context, old.copy(
            quotaRemainingRequests = remaining?.coerceAtLeast(0L),
            quotaLimitRequests = limit?.takeIf { it > 0L },
            quotaResetAtMs = resetAtMs?.takeIf { it > 0L },
            observedAt = System.currentTimeMillis()
        ))
    }

    private fun save(context: Context, m: Metrics) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = read(context)
        val o = JSONObject().apply {
            m.qualityScore?.let { put("qualityScore", it) }
            put("qualitySamples", m.qualitySamples.coerceAtLeast(0))
            put("qualityDegradationCount", m.qualityDegradationCount.coerceAtLeast(0))
            m.latencyEwmaMs?.let { put("latencyEwmaMs", it.coerceAtLeast(0L)) }
            put("successCount", m.successCount.coerceAtLeast(0))
            put("failureCount", m.failureCount.coerceAtLeast(0))
            m.quotaRemainingRequests?.let { put("quotaRemainingRequests", it.coerceAtLeast(0L)) }
            m.quotaLimitRequests?.let { put("quotaLimitRequests", it.coerceAtLeast(1L)) }
            m.quotaResetAtMs?.let { put("quotaResetAtMs", it.coerceAtLeast(0L)) }
            put("observedAt", m.observedAt.coerceAtLeast(0L))
        }
        root.put(m.provider, o)
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private fun read(context: Context): JSONObject = runCatching {
        JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
    }.getOrDefault(JSONObject())
}

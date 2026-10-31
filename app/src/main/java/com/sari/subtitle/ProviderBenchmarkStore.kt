package com.sari.subtitle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 53: persistent, non-sensitive benchmark results.
 * Only aggregate benchmark metadata is stored; no API keys, source text, or translations.
 */
object ProviderBenchmarkStore {
    private const val PREFS = "sari_provider_benchmark_v1"
    private const val KEY = "results"

    data class Record(
        val provider: String,
        val runAtMs: Long,
        val samples: Int,
        val successes: Int,
        val failures: Int,
        val averageLatencyMs: Long?,
        val qualityScore: Int?,
        val qualitySamples: Int,
        val qualityReasons: List<String>,
        val referenceBleu: Double?,
        val referenceChrf: Double?,
        val referenceScore: Int?,
        val referenceSamples: Int
    )

    @Synchronized
    fun save(context: Context, result: ProviderBenchmarkEngine.ProviderResult, runAtMs: Long = System.currentTimeMillis()) {
        val root = read(context)
        val items = root.optJSONArray(KEY) ?: JSONArray()
        val record = JSONObject().apply {
            put("provider", result.provider)
            put("runAtMs", runAtMs.coerceAtLeast(0L))
            put("samples", result.samples.coerceAtLeast(0))
            put("successes", result.successes.coerceAtLeast(0))
            put("failures", result.failures.coerceAtLeast(0))
            result.averageLatencyMs?.let { put("averageLatencyMs", it.coerceAtLeast(0L)) }
            result.qualityScore?.let { put("qualityScore", it.coerceIn(0, 100)) }
            put("qualitySamples", result.qualitySamples.coerceAtLeast(0))
            put("qualityReasons", JSONArray(result.qualityReasons.take(8)))
            result.referenceBleu?.let { put("referenceBleu", it.coerceIn(0.0, 1.0)) }
            result.referenceChrf?.let { put("referenceChrf", it.coerceIn(0.0, 1.0)) }
            result.referenceScore?.let { put("referenceScore", it.coerceIn(0, 100)) }
            put("referenceSamples", result.referenceSamples.coerceAtLeast(0))
        }
        // Keep only the latest result per provider; the UI is a current benchmark dashboard.
        val replacement = JSONArray()
        for (i in 0 until items.length()) {
            val old = items.optJSONObject(i) ?: continue
            if (!old.optString("provider").equals(result.provider, ignoreCase = true)) replacement.put(old)
        }
        replacement.put(record)
        root.put(KEY, replacement)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("data", root.toString()).apply()
    }

    @Synchronized
    fun get(context: Context, provider: String): Record? {
        val items = read(context).optJSONArray(KEY) ?: return null
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (!o.optString("provider").equals(provider, ignoreCase = true)) continue
            return Record(
                provider = o.optString("provider"),
                runAtMs = o.optLong("runAtMs", 0L),
                samples = o.optInt("samples", 0),
                successes = o.optInt("successes", 0),
                failures = o.optInt("failures", 0),
                averageLatencyMs = if (o.has("averageLatencyMs") && !o.isNull("averageLatencyMs")) o.optLong("averageLatencyMs") else null,
                qualityScore = if (o.has("qualityScore") && !o.isNull("qualityScore")) o.optInt("qualityScore").coerceIn(0, 100) else null,
                qualitySamples = o.optInt("qualitySamples", 0),
                qualityReasons = buildList {
                    val a = o.optJSONArray("qualityReasons") ?: return@buildList
                    for (j in 0 until a.length()) add(a.optString(j))
                },
                referenceBleu = if (o.has("referenceBleu") && !o.isNull("referenceBleu")) o.optDouble("referenceBleu").coerceIn(0.0, 1.0) else null,
                referenceChrf = if (o.has("referenceChrf") && !o.isNull("referenceChrf")) o.optDouble("referenceChrf").coerceIn(0.0, 1.0) else null,
                referenceScore = if (o.has("referenceScore") && !o.isNull("referenceScore")) o.optInt("referenceScore").coerceIn(0, 100) else null,
                referenceSamples = o.optInt("referenceSamples", 0)
            )
        }
        return null
    }

    private fun read(context: Context): JSONObject = runCatching {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("data", "{}") ?: "{}"
        JSONObject(raw)
    }.getOrDefault(JSONObject())
}

package com.sari.subtitle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent, non-secret health snapshot for configured providers.
 * API keys, prompts, transcripts and response bodies are never stored here.
 */
object ProviderHealthStore {
    private const val PREFS = "sari_provider_health_v1"
    private const val KEY = "providers"
    private const val MAX_DETAIL = 220

    enum class State { READY, NOT_CONFIGURED, AUTH_ERROR, RATE_LIMITED, SERVER_ERROR, NETWORK_ERROR, ERROR }

    data class Health(
        val provider: String,
        val state: State,
        val latencyMs: Long,
        val checkedAt: Long,
        val httpCode: Int,
        val detail: String,
        val retryAfterSec: Long = 0L,
        val failureCount: Int = 0,
        val circuitOpenUntil: Long = 0L,
        val quotaRemainingRequests: Long? = null,
        val quotaLimitRequests: Long? = null,
        val quotaResetAtMs: Long? = null
    )

    @Synchronized
    fun save(context: Context, health: Health) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        root.put(health.provider, JSONObject()
            .put("state", health.state.name)
            .put("latencyMs", health.latencyMs.coerceAtLeast(0))
            .put("checkedAt", health.checkedAt.coerceAtLeast(0))
            .put("httpCode", health.httpCode)
            .put("detail", sanitize(health.detail))
            .put("retryAfterSec", health.retryAfterSec.coerceAtLeast(0))
            .put("failureCount", health.failureCount.coerceAtLeast(0))
            .put("circuitOpenUntil", health.circuitOpenUntil.coerceAtLeast(0))
            .put("quotaRemainingRequests", health.quotaRemainingRequests ?: JSONObject.NULL)
            .put("quotaLimitRequests", health.quotaLimitRequests ?: JSONObject.NULL)
            .put("quotaResetAtMs", health.quotaResetAtMs ?: JSONObject.NULL))
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun get(context: Context, provider: String): Health? {
        val root = runCatching {
            JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val o = root.optJSONObject(provider) ?: return null
        val state = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.ERROR)
        return Health(provider, state, o.optLong("latencyMs"), o.optLong("checkedAt"),
            o.optInt("httpCode"), sanitize(o.optString("detail")), o.optLong("retryAfterSec"),
            o.optInt("failureCount"), o.optLong("circuitOpenUntil"),
            if (o.isNull("quotaRemainingRequests")) null else o.optLong("quotaRemainingRequests"),
            if (o.isNull("quotaLimitRequests")) null else o.optLong("quotaLimitRequests"),
            if (o.isNull("quotaResetAtMs")) null else o.optLong("quotaResetAtMs"))
    }

    fun all(context: Context): List<Health> {
        val root = runCatching {
            JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val result = mutableListOf<Health>()
        root.keys().forEach { key -> get(context, key)?.let(result::add) }
        return result
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun stateFromError(message: String, httpCode: Int = 0): State {
        if (httpCode > 0) return classify(httpCode)
        val text = message.lowercase()
        return when {
            "401" in text || "403" in text || "unauthorized" in text || "forbidden" in text || "invalid api key" in text -> State.AUTH_ERROR
            "429" in text || "rate limit" in text || "too many requests" in text -> State.RATE_LIMITED
            Regex("\\b5\\d{2}\\b").containsMatchIn(text) || "server error" in text -> State.SERVER_ERROR
            "timeout" in text || "unable to resolve" in text || "failed to connect" in text || "network" in text -> State.NETWORK_ERROR
            else -> State.ERROR
        }
    }

    fun classify(code: Int): State = when {
        code == 401 || code == 403 -> State.AUTH_ERROR
        code == 429 -> State.RATE_LIMITED
        code in 500..599 -> State.SERVER_ERROR
        code in 200..299 -> State.READY
        else -> State.ERROR
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(authorization|api[_ -]?key|bearer|token)\\s*[:=]?\\s*\\S+"), "$1=[REDACTED]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .take(MAX_DETAIL)
}

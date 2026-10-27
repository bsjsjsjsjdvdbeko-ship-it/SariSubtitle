package com.sari.subtitle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stage 56: bounded, non-sensitive routing decision history. */
object ProviderDecisionTraceStore {
    private const val PREFS = "sari_provider_decision_trace_v1"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 50

    data class Entry(
        val atMs: Long,
        val task: String,
        val selected: String?,
        val reason: String,
        val candidates: List<String>
    )

    @Synchronized
    fun append(
        context: Context,
        task: ProviderScoringEngine.Task,
        selected: String?,
        reason: String,
        candidates: List<String>
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY_ITEMS, "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        val start = maxOf(0, old.length() - MAX_ITEMS + 1)
        for (i in start until old.length()) next.put(old.optJSONObject(i))
        next.put(JSONObject().apply {
            put("atMs", System.currentTimeMillis())
            put("task", task.name)
            put("selected", selected ?: JSONObject.NULL)
            put("reason", reason.take(300))
            put("candidates", JSONArray(candidates.take(8)))
        })
        prefs.edit().putString(KEY_ITEMS, next.toString()).apply()
    }

    fun recent(context: Context, limit: Int = 20): List<Entry> {
        val array = runCatching {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]"))
        }.getOrElse { JSONArray() }
        val out = ArrayList<Entry>(minOf(limit, array.length()))
        for (i in (array.length() - 1 downTo 0)) {
            if (out.size >= limit) break
            val o = array.optJSONObject(i) ?: continue
            val candidates = o.optJSONArray("candidates")?.let { a ->
                List(a.length()) { idx -> a.optString(idx) }.filter { it.isNotBlank() }
            } ?: emptyList()
            out += Entry(
                atMs = o.optLong("atMs"),
                task = o.optString("task"),
                selected = o.optString("selected").takeIf { it.isNotBlank() && it != "null" },
                reason = o.optString("reason"),
                candidates = candidates
            )
        }
        return out
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

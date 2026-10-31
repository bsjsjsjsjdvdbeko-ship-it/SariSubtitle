package com.sari.subtitle

import android.content.Context
import org.json.JSONObject

/** Non-sensitive rolling routing signals. No text/audio/API credentials are stored. */
object AdaptiveContextStore {
    private const val PREFS = "sari_adaptive_context_v1"
    private const val KEY = "profile"

    data class Profile(
        val speechSpeedWpm: Int? = null,
        val typicalChunkDurationMs: Long? = null,
        val updatedAt: Long = 0L
    )

    fun get(context: Context): Profile {
        val o = runCatching {
            JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        return Profile(
            speechSpeedWpm = if (o.has("speechSpeedWpm") && !o.isNull("speechSpeedWpm")) o.optInt("speechSpeedWpm") else null,
            typicalChunkDurationMs = if (o.has("typicalChunkDurationMs") && !o.isNull("typicalChunkDurationMs")) o.optLong("typicalChunkDurationMs") else null,
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }

    @Synchronized
    fun recordSpeechSpeed(context: Context, wpm: Int) {
        val old = get(context)
        val value = wpm.coerceIn(1, 600)
        val ewma = old.speechSpeedWpm?.let { (it * 0.7 + value * 0.3).toInt() } ?: value
        save(context, old.copy(speechSpeedWpm = ewma, updatedAt = System.currentTimeMillis()))
    }

    @Synchronized
    fun recordChunkDuration(context: Context, durationMs: Long) {
        if (durationMs <= 0L) return
        val old = get(context)
        val value = durationMs.coerceIn(250L, 120_000L)
        val ewma = old.typicalChunkDurationMs?.let { (it * 0.7 + value * 0.3).toLong() } ?: value
        save(context, old.copy(typicalChunkDurationMs = ewma, updatedAt = System.currentTimeMillis()))
    }

    private fun save(context: Context, profile: Profile) {
        val o = JSONObject()
        profile.speechSpeedWpm?.let { o.put("speechSpeedWpm", it) }
        profile.typicalChunkDurationMs?.let { o.put("typicalChunkDurationMs", it) }
        o.put("updatedAt", profile.updatedAt)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }
}

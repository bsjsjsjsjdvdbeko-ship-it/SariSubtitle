package com.sari.subtitle

import android.content.Context
import java.security.MessageDigest

/**
 * Per-video playback resume store.
 * Keys are derived from VideoUrlIdentity so tracking parameters do not create duplicates.
 * Raw URLs/media URIs are never stored in this preferences file.
 */
class PlaybackPositionStore(context: Context) {
    companion object {
        private const val PREFS = "sari_playback_positions_v1"
        private const val PREFIX_POSITION = "position_"
        private const val PREFIX_DURATION = "duration_"
        private const val PREFIX_UPDATED = "updated_"
        private const val MIN_SAVE_MS = 2_000L
        private const val COMPLETION_MARGIN_MS = 5_000L
    }

    data class Record(
        val positionMs: Long,
        val durationMs: Long,
        val updatedAtMs: Long
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keyFor(source: String): String? {
        val normalized = VideoUrlIdentity.normalize(source)
        if (normalized.isBlank()) return null
        return sha256(normalized)
    }

    fun get(source: String): Record? = keyFor(source)?.let(::getByKey)

    fun getByKey(key: String): Record? {
        if (!prefs.contains(PREFIX_POSITION + key)) return null
        val position = prefs.getLong(PREFIX_POSITION + key, 0L)
        val duration = prefs.getLong(PREFIX_DURATION + key, 0L)
        val updated = prefs.getLong(PREFIX_UPDATED + key, 0L)
        return Record(position.coerceAtLeast(0L), duration.coerceAtLeast(0L), updated.coerceAtLeast(0L))
    }

    /** Saves a resume point. Near-complete videos are treated as finished and reset to 0. */
    fun save(source: String, positionMs: Long, durationMs: Long) {
        val key = keyFor(source) ?: return
        saveByKey(key, positionMs, durationMs)
    }

    fun saveByKey(key: String, positionMs: Long, durationMs: Long) {
        val safePosition = positionMs.coerceAtLeast(0L)
        val safeDuration = durationMs.coerceAtLeast(0L)
        if (safePosition < MIN_SAVE_MS) return
        if (safeDuration > 0L && safePosition >= (safeDuration - COMPLETION_MARGIN_MS).coerceAtLeast(0L)) {
            clearByKey(key)
            return
        }
        prefs.edit()
            .putLong(PREFIX_POSITION + key, safePosition)
            .putLong(PREFIX_DURATION + key, safeDuration)
            .putLong(PREFIX_UPDATED + key, System.currentTimeMillis())
            .apply()
    }

    fun clear(source: String) {
        keyFor(source)?.let(::clearByKey)
    }

    fun clearByKey(key: String) {
        prefs.edit()
            .remove(PREFIX_POSITION + key)
            .remove(PREFIX_DURATION + key)
            .remove(PREFIX_UPDATED + key)
            .apply()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

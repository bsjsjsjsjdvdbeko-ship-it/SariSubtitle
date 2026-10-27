package com.sari.subtitle

import java.util.ArrayDeque

/**
 * Prevents duplicate live subtitles caused by the intentional chunk overlap.
 * It stores only a short normalized text window; no audio or credentials are kept.
 */
class LiveCueDeduplicator(
    private val windowMs: Long = 8_000L
) {
    private data class Seen(val normalized: String, val atMs: Long)
    private val recent = ArrayDeque<Seen>()

    @Synchronized
    fun shouldEmit(text: String, timestampMs: Long): Boolean {
        val normalized = normalize(text)
        if (normalized.isBlank()) return false
        while (recent.isNotEmpty() && timestampMs - recent.first().atMs > windowMs) recent.removeFirst()

        val duplicate = recent.any { seen ->
            seen.normalized == normalized ||
                (normalized.length >= 12 && seen.normalized.length >= 12 &&
                    (normalized.contains(seen.normalized) || seen.normalized.contains(normalized)))
        }
        if (duplicate) return false
        recent.offerLast(Seen(normalized, timestampMs))
        while (recent.size > 16) recent.removeFirst()
        return true
    }

    @Synchronized
    fun clear() = recent.clear()

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace(Regex("[\\p{Punct}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

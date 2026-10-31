package com.sari.subtitle

/**
 * Stage 47: deterministic display timing for live subtitles.
 * All values are relative to the capture timeline.
 */
object LiveSubtitleTimingPolicy {
    fun displayUntilMs(cue: LiveSttRouter.LiveCue): Long {
        val start = cue.startMs.coerceAtLeast(0L)
        val end = maxOf(cue.endMs, start + 300L)
        val words = cue.text.trim()
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
            .coerceAtMost(32)
        val readingMs = (words * 260L).coerceIn(1_200L, 7_000L)
        val naturalUntil = maxOf(end + 900L, start + readingMs)
        return naturalUntil.coerceAtMost(end + 8_000L)
    }
}

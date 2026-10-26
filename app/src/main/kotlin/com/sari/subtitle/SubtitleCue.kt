package com.sari.subtitle

data class SubtitleCue(
    val original: String,
    val translated: String,
    val startElapsedMs: Long,
    val endElapsedMs: Long
) {
    val durationMs: Long get() = (endElapsedMs - startElapsedMs).coerceIn(900L, 6_000L)
}

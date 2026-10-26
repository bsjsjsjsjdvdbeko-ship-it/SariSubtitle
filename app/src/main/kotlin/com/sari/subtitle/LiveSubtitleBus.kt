package com.sari.subtitle

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live subtitle state. elapsedRealtime is used as the common clock for capture and UI. */
data class LiveSubtitle(
    val id: Long,
    val original: String,
    val translated: String,
    val capturedAtElapsedMs: Long,
    val estimatedDurationMs: Long = 2200L,
    val latencyMs: Long = 0L
)

object LiveSubtitleBus {
    @Volatile private var latestCaptureStartMs: Long = Long.MIN_VALUE
    private val _current = MutableStateFlow<LiveSubtitle?>(null)
    val current: StateFlow<LiveSubtitle?> = _current.asStateFlow()

    fun markLatestCapture(startElapsedMs: Long) {
        if (startElapsedMs > latestCaptureStartMs) latestCaptureStartMs = startElapsedMs
    }

    fun publish(
        original: String,
        translated: String,
        capturedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
        durationMs: Long = 2200L
    ) {
        if (translated.isBlank()) return
        // Never let an old network result overwrite a newer live subtitle.
        if (capturedAtElapsedMs + 1_000L < latestCaptureStartMs) return
        val now = SystemClock.elapsedRealtime()
        _current.value = LiveSubtitle(
            id = capturedAtElapsedMs,
            original = original,
            translated = translated,
            capturedAtElapsedMs = capturedAtElapsedMs,
            estimatedDurationMs = durationMs.coerceIn(900L, 6_000L),
            latencyMs = (now - capturedAtElapsedMs).coerceAtLeast(0L)
        )
    }

    fun clear() {
        latestCaptureStartMs = Long.MIN_VALUE
        _current.value = null
    }
}

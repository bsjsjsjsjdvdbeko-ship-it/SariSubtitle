package com.sari.subtitle

/**
 * Stage 48: keeps live subtitle timing monotonic and prevents visual overlap.
 *
 * The manager works on the capture timeline, not wall-clock time. It never
 * changes STT text and never stores audio. A newer final cue can trim the
 * previous cue's effective display window, while a late/older cue is rejected.
 */
class LiveSubtitleTimelineManager(
    private val minimumVisibleMs: Long = 900L,
    private val minimumGapMs: Long = 60L,
    private val maxFutureMs: Long = 8_000L
) {
    data class ScheduledCue(
        val sequence: Long,
        val revision: Int,
        val startMs: Long,
        val endMs: Long,
        val displayUntilMs: Long
    )

    data class Decision(
        val accepted: Boolean,
        val cue: ScheduledCue? = null,
        val previousTrimmedUntilMs: Long? = null,
        val reason: String = ""
    )

    private var sessionId = Long.MIN_VALUE
    private var latestSequence = Long.MIN_VALUE
    private var latestRevision = -1
    private var active: ScheduledCue? = null

    @Synchronized
    fun beginSession(newSessionId: Long) {
        sessionId = newSessionId
        latestSequence = Long.MIN_VALUE
        latestRevision = -1
        active = null
    }

    @Synchronized
    fun clear() {
        sessionId = Long.MIN_VALUE
        latestSequence = Long.MIN_VALUE
        latestRevision = -1
        active = null
    }

    @Synchronized
    fun schedule(
        activeSessionId: Long,
        sequence: Long,
        revision: Int,
        startMs: Long,
        endMs: Long,
        naturalDisplayUntilMs: Long
    ): Decision {
        if (activeSessionId != sessionId) return Decision(false, reason = "stale_session")
        if (sequence < latestSequence) return Decision(false, reason = "older_sequence")
        if (sequence == latestSequence && revision <= latestRevision) {
            return Decision(false, reason = "duplicate_revision")
        }

        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = maxOf(endMs, safeStart + 300L)
        val natural = maxOf(naturalDisplayUntilMs, safeStart + minimumVisibleMs)
        val boundedNatural = minOf(natural, safeEnd + maxFutureMs)

        val previous = active
        val previousTrim = if (previous != null && safeStart <= previous.displayUntilMs) {
            maxOf(previous.startMs + minimumVisibleMs, safeStart - minimumGapMs)
                .coerceAtMost(previous.displayUntilMs)
        } else null

        val currentUntil = if (previousTrim != null) {
            maxOf(boundedNatural, safeEnd + minimumVisibleMs)
        } else boundedNatural

        val scheduled = ScheduledCue(
            sequence = sequence,
            revision = revision,
            startMs = safeStart,
            endMs = safeEnd,
            displayUntilMs = currentUntil
        )
        latestSequence = sequence
        latestRevision = revision
        active = scheduled
        return Decision(true, scheduled, previousTrim)
    }

    @Synchronized
    fun active(): ScheduledCue? = active
}

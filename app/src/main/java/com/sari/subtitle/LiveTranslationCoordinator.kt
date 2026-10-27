package com.sari.subtitle

import java.util.concurrent.BlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.LinkedHashMap

/**
 * Stage 46: keeps live translation work bounded and session-safe.
 *
 * Design goals:
 *  - never build an unbounded translation backlog;
 *  - keep at most one network translation running and one pending;
 *  - prefer fresh live audio when the pending slot is full;
 *  - prevent a late result from an older session/sequence/revision from reaching the UI;
 *  - keep all ordering metadata in memory only.
 */
class LiveTranslationCoordinator(
    private val maxPending: Int = 1
) {
    init {
        require(maxPending >= 1) { "maxPending must be >= 1" }
    }
    data class Request(
        val sessionId: Long,
        val cue: LiveSttRouter.LiveCue,
        val submittedAtElapsedMs: Long
    )

    data class GuardResult(
        val accepted: Boolean,
        val reason: String = ""
    )

    private val latestSubmittedRevision = LinkedHashMap<Long, Int>(64, 0.75f, true)
    private var activeSessionId = Long.MIN_VALUE
    private var lastPublishedSequence = Long.MIN_VALUE
    private var lastPublishedRevision = -1

    @Synchronized
    fun beginSession(sessionId: Long) {
        activeSessionId = sessionId
        latestSubmittedRevision.clear()
        lastPublishedSequence = Long.MIN_VALUE
        lastPublishedRevision = -1
    }

    @Synchronized
    fun clear() {
        activeSessionId = Long.MIN_VALUE
        latestSubmittedRevision.clear()
        lastPublishedSequence = Long.MIN_VALUE
        lastPublishedRevision = -1
    }

    @Synchronized
    fun acceptForTranslation(request: Request): GuardResult {
        if (request.sessionId != activeSessionId) return GuardResult(false, "stale_session")
        val cue = request.cue
        if (!cue.isFinal) return GuardResult(false, "not_final")
        if (cue.sequence < lastPublishedSequence) return GuardResult(false, "older_sequence")

        val previous = latestSubmittedRevision[cue.sequence]
        if (previous != null && cue.revision <= previous) {
            return GuardResult(false, "duplicate_revision")
        }
        latestSubmittedRevision[cue.sequence] = cue.revision
        while (latestSubmittedRevision.size > 64) {
            val iterator = latestSubmittedRevision.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return GuardResult(true)
    }

    @Synchronized
    fun acceptForPublish(sessionId: Long, cue: LiveSttRouter.LiveCue): GuardResult {
        if (sessionId != activeSessionId) return GuardResult(false, "stale_session")
        if (!cue.isFinal) return GuardResult(false, "not_final")
        if (cue.sequence < lastPublishedSequence) return GuardResult(false, "older_sequence")
        if (cue.sequence == lastPublishedSequence && cue.revision <= lastPublishedRevision) {
            return GuardResult(false, "duplicate_publish")
        }
        lastPublishedSequence = cue.sequence
        lastPublishedRevision = cue.revision
        return GuardResult(true)
    }

    /**
     * Submit the newest request while keeping the executor bounded.
     * If one request is already queued (not running), it is removed in favor of the newer cue.
     */
    fun executeLatest(
        executor: ThreadPoolExecutor,
        request: Request,
        task: () -> Unit
    ): Boolean {
        val guard = acceptForTranslation(request)
        if (!guard.accepted) return false

        val queue: BlockingQueue<Runnable> = executor.queue
        synchronized(queue) {
            while (queue.size >= maxPending) {
                queue.poll() ?: break
            }
            return try {
                executor.execute(task)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Synchronized
    fun lastPublishedSequence(): Long = lastPublishedSequence

    @Synchronized
    fun snapshot(): Map<String, Long> = mapOf(
        "sessionId" to activeSessionId,
        "lastPublishedSequence" to lastPublishedSequence.toLong(),
        "pendingTracked" to latestSubmittedRevision.size.toLong()
    )
}

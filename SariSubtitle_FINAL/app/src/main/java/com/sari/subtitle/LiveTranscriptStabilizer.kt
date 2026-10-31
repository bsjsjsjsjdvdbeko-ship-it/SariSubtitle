package com.sari.subtitle

import java.util.LinkedHashMap

/**
 * Stage 44: stabilizes live STT events before translation/display.
 *
 * The current REST STT providers return final chunk transcripts, so the live
 * pipeline currently feeds FINAL events. The event model deliberately supports
 * PARTIAL -> PARTIAL -> FINAL updates without changing the capture layer.
 * No audio or credentials are stored here.
 */
class LiveTranscriptStabilizer(
    private val maxTracks: Int = 32
) {
    enum class Kind { PARTIAL, FINAL }

    data class Event(
        val sequence: Long,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val language: String,
        val provider: String,
        val kind: Kind,
        val revision: Int = 0
    )

    enum class Action { EMIT_PARTIAL, EMIT_FINAL, REPLACE, DROP }

    data class Decision(
        val action: Action,
        val event: Event? = null
    )

    private data class State(
        var latest: Event,
        var final: Boolean
    )

    private val tracks = LinkedHashMap<Long, State>(16, 0.75f, true)
    private var highestFinalSequence = Long.MIN_VALUE

    @Synchronized
    fun accept(event: Event): Decision {
        val text = normalize(event.text)
        if (text.isBlank() || event.endMs <= event.startMs) return Decision(Action.DROP)
        if (event.kind == Kind.FINAL && event.sequence < highestFinalSequence) {
            return Decision(Action.DROP)
        }

        val clean = event.copy(text = text)
        val previous = tracks[clean.sequence]
        if (previous == null) {
            tracks[clean.sequence] = State(clean, clean.kind == Kind.FINAL)
            trim()
            if (clean.kind == Kind.FINAL) highestFinalSequence = maxOf(highestFinalSequence, clean.sequence)
            return Decision(if (clean.kind == Kind.FINAL) Action.EMIT_FINAL else Action.EMIT_PARTIAL, clean)
        }

        if (previous.final) return Decision(Action.DROP)
        if (clean.revision < previous.latest.revision) return Decision(Action.DROP)
        if (clean.text == previous.latest.text && clean.kind == previous.latest.kind) return Decision(Action.DROP)

        previous.latest = clean
        if (clean.kind == Kind.FINAL) {
            previous.final = true
            highestFinalSequence = maxOf(highestFinalSequence, clean.sequence)
            return Decision(Action.EMIT_FINAL, clean)
        }
        return Decision(Action.REPLACE, clean)
    }

    @Synchronized
    fun clear() {
        tracks.clear()
        highestFinalSequence = Long.MIN_VALUE
    }

    @Synchronized
    fun size(): Int = tracks.size

    private fun trim() {
        while (tracks.size > maxTracks) {
            val iterator = tracks.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            } else break
        }
    }

    private fun normalize(value: String): String = value
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

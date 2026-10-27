package com.sari.subtitle

/** Efficient timeline lookup for cues sorted by startMs (the order guaranteed by SrtParser). */
internal object SubtitleCueTimeline {
    /** Returns the latest-starting cue active at [positionMs], or null outside cue intervals. */
    fun findAt(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? {
        if (positionMs < 0L || cues.isEmpty()) return null
        var low = 0
        var high = cues.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = low + (high - low) / 2
            if (cues[mid].startMs <= positionMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return null
        val cue = cues[candidate]
        return cue.takeIf { it.contains(positionMs) }
    }
}

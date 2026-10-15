package com.sari.subtitle.v2

/**
 * يدير قائمة cues ويوفّر بحثاً سريعاً حسب الوقت.
 */
class SubtitleRepository {

    private var cues: List<SubtitleCue> = emptyList()

    val size: Int get() = cues.size
    val isEmpty: Boolean get() = cues.isEmpty()

    /**
     * يستبدل القائمة الحالية.
     */
    fun setCues(newCues: List<SubtitleCue>) {
        cues = newCues.sortedBy { it.startMs }
    }

    fun clear() {
        cues = emptyList()
    }

    /**
     * يعيد cue المناسب للوقت المحدد (ms)، أو null.
     * يستخدم بحثاً ثنائياً.
     */
    fun findCueAt(timeMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null

        var lo = 0
        var hi = cues.size - 1
        var candidate: SubtitleCue? = null

        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val c = cues[mid]
            when {
                timeMs < c.startMs -> hi = mid - 1
                timeMs >= c.endMs -> lo = mid + 1
                else -> return c  // داخل النطاق
            }
            if (c.startMs <= timeMs) candidate = c
        }
        return candidate?.takeIf { it.contains(timeMs) }
    }

    /**
     * يعيد cue واحد قريب من الوقت (للتشخيص).
     */
    fun debugNearest(timeMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null
        var best = cues[0]
        var bestDist = kotlin.math.abs(cues[0].startMs - timeMs)
        for (c in cues) {
            val d = kotlin.math.abs(c.startMs - timeMs)
            if (d < bestDist) { bestDist = d; best = c }
        }
        return best
    }
}

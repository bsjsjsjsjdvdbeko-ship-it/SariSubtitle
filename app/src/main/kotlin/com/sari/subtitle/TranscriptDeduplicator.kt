package com.sari.subtitle

/** Prevents overlapping STT windows from producing duplicate subtitles. */
class TranscriptDeduplicator(private val similarityThreshold: Double = 0.82) {
    private var previous = ""

    @Synchronized
    fun shouldTranslate(candidate: String): Boolean {
        val cleaned = normalize(candidate)
        if (cleaned.isBlank()) return false
        val old = previous
        if (old.isNotBlank() && similarity(old, cleaned) >= similarityThreshold) return false
        previous = cleaned
        return true
    }

    @Synchronized fun reset() { previous = "" }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val aa = a.split(" ").filter { it.isNotBlank() }.toSet()
        val bb = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return 2.0 * aa.intersect(bb).size / (aa.size + bb.size)
    }
}

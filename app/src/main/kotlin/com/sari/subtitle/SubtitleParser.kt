package com.sari.subtitle

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SubtitleParser {
    fun parse(content: String): List<SubtitleCue> {
        val normalized = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val result = blocks.mapNotNull { parseBlock(it) }.sortedBy { it.startMs }
        return result.fold(mutableListOf()) { acc, cue ->
            if (acc.isEmpty() || cue.startMs >= acc.last().startMs) acc.add(cue)
            acc
        }
    }

    private fun parseBlock(block: String): SubtitleCue? {
        val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val timingIndex = lines.indexOfFirst { it.contains("-->") }
        if (timingIndex < 0) return null
        val parts = lines[timingIndex].split("-->", limit = 2)
        if (parts.size != 2) return null
        val start = parseTime(parts[0].trim()) ?: return null
        val end = parseTime(parts[1].trim().split(Regex("\\s+"))[0]) ?: return null
        val text = lines.drop(timingIndex + 1)
            .joinToString("\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()
        if (text.isBlank() || end <= start) return null
        return SubtitleCue(start, end, text)
    }

    private fun parseTime(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        return runCatching {
            val sec = parts.last().toDouble()
            val minutes = parts[parts.size - 2].toLong()
            val hours = if (parts.size == 3) parts[0].toLong() else 0L
            ((hours * 3600 + minutes * 60) * 1000 + (sec * 1000).toLong())
        }.getOrNull()
    }
}

package com.sari.subtitle.v2

import java.util.Locale

/** Shifts SRT cue timestamps without changing cue text or numbering. */
object SrtTimestampShifter {
    private val timingLine = Regex(
        "^(\\d{1,}:\\d{2}:\\d{2}[,.]\\d{3})\\s+-->\\s+(\\d{1,}:\\d{2}:\\d{2}[,.]\\d{3})$",
        RegexOption.MULTILINE
    )
    private val timestamp = Regex("^(\\d+):(\\d{2}):(\\d{2})([,.])(\\d{3})$")

    fun shift(srt: String, offsetMs: Long): String {
        if (offsetMs <= 0L || srt.isEmpty()) return srt
        return timingLine.replace(srt) { match ->
            "${shiftTimestamp(match.groupValues[1], offsetMs)} --> ${shiftTimestamp(match.groupValues[2], offsetMs)}"
        }
    }

    private fun shiftTimestamp(value: String, offsetMs: Long): String {
        val match = timestamp.matchEntire(value) ?: return value
        val hours = match.groupValues[1].toLongOrNull() ?: return value
        val minutes = match.groupValues[2].toLongOrNull() ?: return value
        val seconds = match.groupValues[3].toLongOrNull() ?: return value
        val millis = match.groupValues[5].toLongOrNull() ?: return value
        val original = (((hours * 60L + minutes) * 60L + seconds) * 1000L + millis)
        val total = if (Long.MAX_VALUE - original < offsetMs) Long.MAX_VALUE else original + offsetMs
        val h = total / 3_600_000L
        val m = (total / 60_000L) % 60L
        val sec = (total / 1_000L) % 60L
        val ms = total % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", h, m, sec, match.groupValues[4], ms)
    }
}

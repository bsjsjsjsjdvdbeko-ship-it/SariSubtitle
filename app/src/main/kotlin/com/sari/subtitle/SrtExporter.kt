package com.sari.subtitle

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/** Exports subtitles into the public Downloads/SariSubtitle folder on Android 10+. */
object SrtExporter {
    fun export(context: Context, cues: List<SubtitleCue>, sessionName: String = "sari-subtitles"): String {
        val safe = sessionName.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "sari-subtitles" }
        val fileName = "$safe.srt"
        val content = cues.sortedBy { it.startElapsedMs }.mapIndexed { i, c ->
            "${i + 1}\n${timestamp(c.startElapsedMs)} --> ${timestamp(c.endElapsedMs)}\n${c.translated.trim()}\n"
        }.joinToString("\n")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SariSubtitle")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("تعذر إنشاء ملف SRT في Downloads/SariSubtitle.")
        try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: error("تعذر فتح ملف SRT للكتابة.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val h = safe / 3_600_000
        val m = (safe % 3_600_000) / 60_000
        val s = (safe % 60_000) / 1_000
        val z = safe % 1_000
        return "%02d:%02d:%02d,%03d".format(h, m, s, z)
    }
}

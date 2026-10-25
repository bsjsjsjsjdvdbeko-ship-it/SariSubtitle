package com.sari.subtitle

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * محلل ملفات SRT.
 * يدعم:
 *  - UTF-8 (مع/بدون BOM)
 *  - Windows-1256 (عربي)
 *  - UTF-16 LE/BE
 *  - تنسيقات زمن: HH:MM:SS,mmm  و  HH:MM:SS.mmm
 */
object SrtParser {
    /** Large enough for long subtitle files while preventing unbounded readBytes() allocations. */
    const val MAX_INPUT_BYTES: Int = 16 * 1024 * 1024

    /**
     * يحلّل InputStream إلى قائمة cues مرتبة، مع حد لحجم الإدخال.
     */
    fun parse(input: InputStream): List<SubtitleCue> = parseText(decode(input))

    /** Decode imported subtitle bytes while genuinely falling back for legacy Arabic encodings. */
    fun decode(bytes: ByteArray): String = decodeToText(bytes)

    /** Read and decode an SRT stream with a hard byte limit. Caller retains stream ownership. */
    fun decode(input: InputStream): String = decode(readBoundedBytes(input, MAX_INPUT_BYTES))

    internal fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                // Defensive fallback for unusual streams that return zero for a non-empty buffer.
                val one = input.read()
                if (one < 0) break
                if (total >= maxBytes) {
                    throw IOException("ملف SRT يتجاوز الحد المسموح (${maxBytes / (1024 * 1024)} MB). اختر ملفًا أصغر.")
                }
                output.write(one)
                total++
                continue
            }
            if (count > maxBytes - total) {
                throw IOException("ملف SRT يتجاوز الحد المسموح (${maxBytes / (1024 * 1024)} MB). اختر ملفًا أصغر.")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    /**
     * يحاول فك الترميز بأفضل شكل.
     */
    private fun decodeToText(bytes: ByteArray): String {
        // BOM detection
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            // UTF-8 with BOM
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }

        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                // UTF-16 LE
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                // UTF-16 BE
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }

        // Use REPORT: String(bytes, UTF_8) silently replaces malformed bytes, so it never
        // reliably reaches the legacy Arabic fallback.
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, charset("windows-1256"))
        }
    }

    /**
     * يحلّل النص.
     */
    private fun parseText(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()

        // توحيد الأرقام العشرية إلى ASCII (يدعم أرقام العربية والفارسية في طوابع SRT).
        val asciiDigits = buildString(content.length) {
            for (ch in content) {
                val digit = if (Character.getType(ch) == Character.DECIMAL_DIGIT_NUMBER.toInt()) Character.digit(ch, 10) else -1
                append(if (digit in 0..9) ('0'.code + digit).toChar() else ch)
            }
        }

        // توحيد نهاية الأسطر
        val normalized = asciiDigits.replace("\r\n", "\n").replace('\r', '\n')

        // نقسم على الأسطر
        val lines = normalized.split('\n')

        var i = 0
        var autoIndex = 1

        while (i < lines.size) {
            // تجاهل الأسطر الفارغة
            while (i < lines.size && lines[i].trim().isEmpty()) i++
            if (i >= lines.size) break

            // السطر 1: الفهرس (قد يكون غائباً)
            var currentIndex = autoIndex
            val maybeIndex = lines[i].trim().toIntOrNull()
            if (maybeIndex != null) {
                currentIndex = maybeIndex
                i++
            }

            if (i >= lines.size) break

            // السطر 2: التوقيت
            val timeLine = lines[i].trim()
            i++

            val times = parseTimeLine(timeLine)
            if (times == null) {
                // تخطي هذا البلوك
                autoIndex++
                continue
            }

            // السطور التالية: النص (حتى سطر فارغ)
            val textBuilder = StringBuilder()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                if (textBuilder.isNotEmpty()) textBuilder.append('\n')
                textBuilder.append(lines[i])
                i++
            }

            val text = textBuilder.toString().trim()
            if (text.isNotEmpty()) {
                cues.add(
                    SubtitleCue(
                        index = currentIndex,
                        startMs = times.first,
                        endMs = times.second,
                        text = text
                    )
                )
            }
            autoIndex++
        }

        // رتّب حسب البداية
        cues.sortBy { it.startMs }
        return cues
    }

    /**
     * يحلّل سطر التوقيت: "00:00:01,000 --> 00:00:04,500"
     * @return Pair(startMs, endMs) أو null
     */
    private fun parseTimeLine(line: String): Pair<Long, Long>? {
        // فاصل: "-->"
        val arrow = line.indexOf("-->")
        if (arrow < 0) return null

        val startStr = line.substring(0, arrow).trim()
        val endStr = line.substring(arrow + 3).trim()

        val start = parseTimestamp(startStr) ?: return null
        val end = parseTimestamp(endStr) ?: return null

        return Pair(start, end)
    }

    /**
     * يحلّل "00:00:01,000" أو "00:00:01.000".
     */
    private fun parseTimestamp(s: String): Long? {
        // قد يحتوي على معلومات إضافية بعد المسافة
        val clean = s.takeWhile { !it.isWhitespace() }
            .replace(',', '.')

        val parts = clean.split(':')
        if (parts.size < 3) return null

        return try {
            val h = parts[0].toLongOrNull() ?: 0
            val m = parts[1].toLongOrNull() ?: 0
            val sec = parts[2].toDoubleOrNull() ?: return null

            val ms = ((h * 3600 + m * 60) * 1000) + (sec * 1000).toLong()
            ms
        } catch (_: Exception) {
            null
        }
    }
}

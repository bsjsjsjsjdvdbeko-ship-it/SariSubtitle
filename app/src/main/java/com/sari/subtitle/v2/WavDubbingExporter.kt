package com.sari.subtitle.v2

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Creates a timeline-aligned PCM WAV from SRT cues using Android TTS. */
object WavDubbingExporter {
    private const val TAG = "SariDubbingExport"
    private const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L
    private const val MAX_SPEECH_RATE = 2.2f
    private const val MERGE_GAP_MS = 300L
    private const val FADE_OUT_MS = 45L

    private data class WavData(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val format: Int, val data: ByteArray) {
        val bytesPerSecond: Long get() = sampleRate.toLong() * channels * bitsPerSample / 8
    }
    private data class CueGroup(val cues: List<SubtitleCue>) {
        val startMs: Long get() = cues.first().startMs.coerceAtLeast(0L)
        val endMs: Long get() = cues.maxOf { max(it.endMs, it.startMs + 1L) }
        val text: String get() = cues.joinToString(" ") { it.text.trim() }.trim()
        val label: String get() = cues.joinToString(",") { it.index.toString() }
    }

    fun export(tts: TextToSpeech, cues: List<SubtitleCue>, cacheDir: File, output: OutputStream,
               onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        require(cues.isNotEmpty()) { "لا توجد مقاطع SRT لتصدير الدبلجة" }
        val ordered = cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
        require(ordered.isNotEmpty()) { "لا توجد مقاطع SRT صالحة للدبلجة" }
        val groups = mergeCloseCues(ordered)
        val lastEnd = ordered.maxOf { max(it.endMs, it.startMs + 1L) }
        val first = File(cacheDir, "sari_dub_${System.currentTimeMillis()}_0.wav")
        val raw = File(cacheDir, "sari_dub_${System.currentTimeMillis()}_timeline.pcm")
        var bytesPerFrame = 0
        try {
            val firstGroup = groups.first()
            val firstSlot = availableSlot(groups, 0)
            val firstRate = rateFor(firstGroup.text, firstSlot)
            tts.setSpeechRate(firstRate)
            synthesizeOne(tts, firstGroup.text, first, "sari-export-first-${System.currentTimeMillis()}")
            val firstWav = readWav(first)
            require(firstWav.format == 1 && firstWav.bitsPerSample == 16) {
                "محرك TTS أنتج WAV غير PCM-16؛ لا يمكن تجميعه بأمان (format=${firstWav.format}, bits=${firstWav.bitsPerSample})"
            }
            bytesPerFrame = firstWav.channels * firstWav.bitsPerSample / 8
            val bytesPerSecond = firstWav.bytesPerSecond
            var totalBytes = (lastEnd * firstWav.sampleRate / 1000L * bytesPerFrame).coerceAtLeast(bytesPerFrame.toLong())
            require(totalBytes <= MAX_OUTPUT_BYTES) { "ملف الدبلجة المتوقع كبير جدًا (أكثر من 512 ميجابايت). صدّر جزءًا أقصر من الفيديو." }
            RandomAccessFile(raw, "rw").use { it.setLength(totalBytes) } // New file is zero-filled: silence stays silence.

            for ((i, group) in groups.withIndex()) {
                val wav = if (i == 0) firstWav else {
                    val cueFile = File(cacheDir, "sari_dub_${System.currentTimeMillis()}_$i.wav")
                    try {
                        val slot = availableSlot(groups, i)
                        val rate = rateFor(group.text, slot)
                        tts.setSpeechRate(rate)
                        synthesizeOne(tts, group.text, cueFile, "sari-export-${i + 1}-${System.nanoTime()}")
                        readWav(cueFile)
                    } finally {
                        cueFile.delete()
                    }
                }
                require(wav.format == 1 && wav.bitsPerSample == 16 && wav.sampleRate == firstWav.sampleRate && wav.channels == firstWav.channels) {
                    "تنسيق صوت TTS تغيّر عند المجموعة ${i + 1} (المقاطع ${group.label})؛ أوقف التصدير لتجنب ملف WAV تالف"
                }
                val slotMs = availableSlot(groups, i)
                val generatedMs = wav.data.size * 1000.0 / bytesPerSecond
                val allowedBytesLong = (slotMs * firstWav.sampleRate / 1000L) * bytesPerFrame
                val allowedBytes = min(wav.data.size.toLong(), allowedBytesLong).toInt().coerceAtLeast(0)
                val clipped = allowedBytes < wav.data.size
                val playable = if (clipped) fadeOutTail(wav.data, allowedBytes, bytesPerFrame, firstWav.sampleRate) else wav.data
                val offset = (group.startMs * firstWav.sampleRate / 1000L) * bytesPerFrame
                val desiredEnd = offset + playable.size
                if (desiredEnd > MAX_OUTPUT_BYTES) throw IllegalStateException("تجاوز ملف الدبلجة حد 512 ميجابايت عند المقاطع ${group.label}")
                if (desiredEnd > totalBytes) {
                    RandomAccessFile(raw, "rw").use { it.setLength(desiredEnd) }
                    totalBytes = desiredEnd
                }
                if (playable.isNotEmpty()) mixPcm16(raw, offset, playable, playable.size)
                val playedMs = playable.size * 1000.0 / bytesPerSecond
                val rate = rateFor(group.text, slotMs)
                Log.i(TAG, "cues=${group.label} provider=Android-TTS rate=${"%.2f".format(rate)} maxRate=$MAX_SPEECH_RATE slotMs=$slotMs generatedTtsMs=${generatedMs.toLong()} placedMs=${playedMs.toLong()} clipped=${if (clipped) 1 else 0} fadeOutMs=${if (clipped) min(FADE_OUT_MS, slotMs).coerceAtLeast(0L) else 0L} merged=${group.cues.size} ${if (clipped) "TTS_CLIPPED_TO_SLOT" else if (generatedMs < slotMs) "SILENCE_RETAINED" else "OK"}")
                for (cue in group.cues) {
                    Log.i(TAG, "cue=${cue.index} group=${group.label} startMs=${cue.startMs} endMs=${cue.endMs} provider=Android-TTS rate=${"%.2f".format(rate)} merged=${group.cues.size} clipped=${if (clipped) 1 else 0}")
                }
                onProgress(i + 1, groups.size, "المقاطع ${group.label} — صوت ${playedMs.toLong()}ms / متاح ${slotMs}ms${if (clipped) " — قص ناعم" else ""}")
            }

            val dataSize = raw.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            output.write(makeWavHeader(firstWav.sampleRate, firstWav.channels, firstWav.bitsPerSample, dataSize))
            FileInputStream(raw).use { input -> input.copyTo(output, 64 * 1024) }
            output.flush()
        } finally {
            tts.setSpeechRate(1.0f)
            first.delete()
            raw.delete()
        }
    }

    /** Merge adjacent cues only when the gap is non-negative and under 300 ms. */
    private fun mergeCloseCues(cues: List<SubtitleCue>): List<CueGroup> {
        val result = mutableListOf<CueGroup>()
        var current = mutableListOf<SubtitleCue>()
        for (cue in cues) {
            val previous = current.lastOrNull()
            val gap = if (previous == null) Long.MAX_VALUE else cue.startMs - previous.endMs
            if (previous != null && gap in 0 until MERGE_GAP_MS) {
                current += cue
            } else {
                if (current.isNotEmpty()) result += CueGroup(current.toList())
                current = mutableListOf(cue)
            }
        }
        if (current.isNotEmpty()) result += CueGroup(current.toList())
        return result
    }

    /** Never lets one synthesized group overlap the next group's start on the output timeline. */
    private fun availableSlot(groups: List<CueGroup>, index: Int): Long {
        val group = groups[index]
        val nextStart = groups.getOrNull(index + 1)?.startMs
        val naturalEnd = group.endMs
        val safeEnd = if (nextStart != null && nextStart > group.startMs) min(naturalEnd, nextStart) else naturalEnd
        return (safeEnd - group.startMs).coerceAtLeast(1L)
    }

    private fun rateFor(text: String, slotMs: Long): Float {
        val seconds = slotMs.coerceAtLeast(1L) / 1000.0
        // Estimate Arabic speech rate; do not slow speech to fill longer slots. Remaining time is silence.
        val estimate = (text.trim().length / (seconds * 14.0)).toFloat()
        return estimate.coerceIn(1.0f, MAX_SPEECH_RATE)
    }

    /** Trims at the slot boundary and applies a short linear fade to avoid an abrupt waveform edge. */
    private fun fadeOutTail(source: ByteArray, allowedBytes: Int, bytesPerFrame: Int, sampleRate: Int): ByteArray {
        val alignedLimit = (allowedBytes / bytesPerFrame) * bytesPerFrame
        if (alignedLimit <= 0) return ByteArray(0)
        val out = source.copyOf(alignedLimit)
        val fadeFrames = min((sampleRate * FADE_OUT_MS / 1000.0).roundToInt(), alignedLimit / bytesPerFrame)
        if (fadeFrames <= 0) return out
        val startByte = alignedLimit - fadeFrames * bytesPerFrame
        var frame = 0
        while (frame < fadeFrames) {
            val gain = (fadeFrames - frame - 1).toDouble() / fadeFrames.toDouble()
            var channelByte = startByte + frame * bytesPerFrame
            val frameEnd = channelByte + bytesPerFrame
            while (channelByte + 1 < frameEnd) {
                val sample = ((out[channelByte + 1].toInt() shl 8) or (out[channelByte].toInt() and 0xff)).toShort().toInt()
                val faded = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[channelByte] = faded.toByte()
                out[channelByte + 1] = (faded shr 8).toByte()
                channelByte += 2
            }
            frame++
        }
        return out
    }

    private fun synthesizeOne(tts: TextToSpeech, text: String, file: File, id: String) {
        file.delete()
        val latch = CountDownLatch(1)
        val status = AtomicInteger(0)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { if (utteranceId == id) { status.set(1); latch.countDown() } }
            override fun onError(utteranceId: String) { if (utteranceId == id) { status.set(-1); latch.countDown() } }
            override fun onError(utteranceId: String, errorCode: Int) { if (utteranceId == id) { status.set(-errorCode.coerceAtLeast(1)); latch.countDown() } }
        })
        val result = tts.synthesizeToFile(text, android.os.Bundle(), file, id)
        check(result == TextToSpeech.SUCCESS) { "رفض محرك TTS إنشاء المقطع: $result" }
        check(latch.await(120, TimeUnit.SECONDS)) { "انتهت مهلة توليد TTS للمقطع: ${text.take(60)}" }
        check(status.get() == 1 && file.exists() && file.length() > 44L) { "فشل synthesizeToFile للمقطع: ${text.take(60)} (status=${status.get()})" }
    }

    private fun readWav(file: File): WavData {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") { "ملف TTS ليس WAV صالحًا: ${file.name}" }
        var offset = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var data: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val begin = offset + 8
            if (size > Int.MAX_VALUE || begin.toLong() + size > bytes.size) break
            when (id) {
                "fmt " -> if (size >= 16) {
                    val b = ByteBuffer.wrap(bytes, begin, size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    format = b.short.toInt() and 0xffff
                    channels = b.short.toInt() and 0xffff
                    sampleRate = b.int
                    b.int
                    b.short
                    bits = b.short.toInt() and 0xffff
                }
                "data" -> data = bytes.copyOfRange(begin, begin + size.toInt())
            }
            offset = begin + size.toInt() + (size.toInt() and 1)
        }
        require(format >= 0 && channels > 0 && sampleRate > 0 && bits > 0 && data != null) { "WAV لا يحتوي على fmt/data صالحين: ${file.name}" }
        return WavData(sampleRate, channels, bits, format, data!!)
    }

    private fun mixPcm16(raw: File, offset: Long, incoming: ByteArray, length: Int) {
        RandomAccessFile(raw, "rw").use { raf ->
            raf.seek(offset)
            val existing = ByteArray(8192)
            var done = 0
            while (done < length) {
                val count = min(existing.size, length - done)
                raf.readFully(existing, 0, count)
                var p = 0
                while (p + 1 < count) {
                    val old = ((existing[p + 1].toInt() shl 8) or (existing[p].toInt() and 0xff)).toShort().toInt()
                    val add = ((incoming[done + p + 1].toInt() shl 8) or (incoming[done + p].toInt() and 0xff)).toShort().toInt()
                    existing[p] = (old + add).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toByte()
                    existing[p + 1] = (((old + add).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())) shr 8).toByte()
                    p += 2
                }
                raf.seek(offset + done)
                raf.write(existing, 0, count)
                done += count
            }
        }
    }

    private fun makeWavHeader(sampleRate: Int, channels: Int, bits: Int, dataSize: Int): ByteArray {
        val bytesPerSample = bits / 8
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII)); put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(channels.toShort()); putInt(sampleRate); putInt(byteRate)
            putShort(blockAlign.toShort()); putShort(bits.toShort())
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
        }.array()
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int) = String(bytes, offset, length, Charsets.US_ASCII)
}

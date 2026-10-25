package com.sari.subtitle

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

/** Creates a timeline-aligned PCM WAV from SRT cues using Android TTS. */
object WavDubbingExporter {
    private const val TAG = "SariDubbingExport"
    private const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L

    private data class WavData(
        val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val format: Int,
        val data: ByteArray, val byteRate: Long, val blockAlign: Int
    ) {
        val bytesPerSecond: Long get() = byteRate
    }

    /** Strict validation used both before exporting and after reading the saved document back. */
    fun validateWav(file: File): String {
        val wav = readWav(file)
        require(wav.format == 1 && wav.bitsPerSample == 16) { "WAV ليس PCM signed 16-bit" }
        require(wav.sampleRate in 8_000..192_000) { "معدل العينة خارج النطاق المقبول: ${wav.sampleRate}" }
        require(wav.channels in 1..2) { "عدد القنوات غير مدعوم: ${wav.channels}" }
        val expectedAlign = wav.channels * (wav.bitsPerSample / 8)
        require(wav.blockAlign == expectedAlign) { "blockAlign غير متوافق مع القنوات وعمق العينة" }
        require(wav.byteRate == wav.sampleRate.toLong() * expectedAlign) { "byteRate غير متوافق مع تنسيق PCM" }
        require(wav.data.isNotEmpty() && wav.data.size % expectedAlign == 0) { "حجم بيانات PCM فارغ أو غير محاذٍ لإطار صوتي" }
        require(file.length() == 44L + wav.data.size) { "حجم الملف لا يطابق ترويسة WAV/حجم data" }
        return "RIFF/WAVE PCM-16 صالح؛ ${wav.sampleRate} Hz، ${wav.channels} قناة، ${wav.data.size} بايت PCM"
    }

    fun export(tts: TextToSpeech, cues: List<SubtitleCue>, cacheDir: File, output: OutputStream,
               onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        require(cues.isNotEmpty()) { "لا توجد مقاطع SRT لتصدير الدبلجة" }
        val ordered = cues.sortedBy { it.startMs }
        val lastEnd = ordered.maxOf { max(it.endMs, it.startMs + 1L) }
        require(lastEnd > 0L) { "توقيتات SRT غير صالحة" }

        val first = File(cacheDir, "sari_dub_${System.currentTimeMillis()}_0.wav")
        val raw = File(cacheDir, "sari_dub_${System.currentTimeMillis()}_timeline.pcm")
        var format: WavData? = null
        var bytesPerFrame = 0
        try {
            // Synthesize the first non-empty cue to discover the TTS engine's PCM format.
            val firstCueIndex = ordered.indexOfFirst { it.text.isNotBlank() }
            require(firstCueIndex >= 0) { "كل مقاطع SRT فارغة" }
            val firstCue = ordered[firstCueIndex]
            val firstDuration = (firstCue.endMs - firstCue.startMs).coerceAtLeast(250L)
            val firstRate = rateFor(firstCue.text, firstDuration)
            tts.setSpeechRate(firstRate)
            synthesizeOne(tts, firstCue.text, first, "sari-export-first-${System.currentTimeMillis()}")
            val firstWav = readWav(first)
            require(firstWav.format == 1 && firstWav.bitsPerSample == 16) {
                "محرك TTS أنتج WAV غير PCM-16؛ لا يمكن تجميعه بأمان (format=${firstWav.format}, bits=${firstWav.bitsPerSample})"
            }
            format = firstWav
            bytesPerFrame = firstWav.channels * firstWav.bitsPerSample / 8
            val bytesPerSecond = firstWav.bytesPerSecond
            var totalBytes = (lastEnd * firstWav.sampleRate / 1000L * bytesPerFrame).coerceAtLeast(bytesPerFrame.toLong())
            require(totalBytes <= MAX_OUTPUT_BYTES) { "ملف الدبلجة المتوقع كبير جدًا (أكثر من 512 ميجابايت). صدّر جزءًا أقصر من الفيديو." }
            RandomAccessFile(raw, "rw").use { it.setLength(totalBytes) }

            for ((i, cue) in ordered.withIndex()) {
                if (cue.text.isBlank()) continue
                val cueFile: File
                val wav: WavData
                if (i == firstCueIndex) {
                    cueFile = first
                    wav = firstWav
                } else {
                    cueFile = File(cacheDir, "sari_dub_${System.currentTimeMillis()}_$i.wav")
                    val duration = (cue.endMs - cue.startMs).coerceAtLeast(250L)
                    val rate = rateFor(cue.text, duration)
                    tts.setSpeechRate(rate)
                    synthesizeOne(tts, cue.text, cueFile, "sari-export-${i + 1}-${System.nanoTime()}")
                    wav = readWav(cueFile)
                }
                val expected = format!!
                require(wav.format == 1 && wav.bitsPerSample == 16 && wav.sampleRate == expected.sampleRate && wav.channels == expected.channels) {
                    "تنسيق صوت TTS تغيّر عند المقطع ${i + 1}؛ أوقف التصدير لتجنب ملف WAV تالف"
                }
                // Align each cue to a whole PCM sample frame (milliseconds do not always map
                // to an integer number of bytes for every sample rate).
                val offset = (cue.startMs.coerceAtLeast(0L) * expected.sampleRate / 1000L) * bytesPerFrame
                val desiredEnd = offset + wav.data.size
                if (desiredEnd > MAX_OUTPUT_BYTES) throw IllegalStateException("تجاوز ملف الدبلجة حد 512 ميجابايت أثناء المقطع ${i + 1}")
                if (desiredEnd > totalBytes) {
                    RandomAccessFile(raw, "rw").use { it.setLength(desiredEnd) }
                    totalBytes = desiredEnd
                }
                val cueBytes = wav.data.size
                if (cueBytes > 0) mixPcm16(raw, offset, wav.data, cueBytes)
                val actualMs = wav.data.size * 1000.0 / bytesPerSecond
                val slotMs = (cue.endMs - cue.startMs).coerceAtLeast(0L)
                Log.i(TAG, "cue=${i + 1}/${ordered.size} provider=Android-TTS rate=${"%.2f".format(if (i == firstCueIndex) firstRate else rateFor(cue.text, slotMs.coerceAtLeast(250L)))} slotMs=$slotMs actualTtsMs=${actualMs.toLong()} startMs=${cue.startMs} endMs=${cue.endMs} ${if (actualMs > slotMs) "TTS_LONGER_THAN_SLOT" else "OK"}")
                onProgress(i + 1, ordered.size, "المقطع ${i + 1}/${ordered.size} — مدة الصوت ${actualMs.toLong()}ms / المدة المتاحة ${slotMs}ms")
                if (cueFile != first) cueFile.delete()
            }

            val fmt = format!!
            val dataSizeLong = raw.length()
            require(dataSizeLong > 0L && dataSizeLong <= MAX_OUTPUT_BYTES && dataSizeLong <= Int.MAX_VALUE - 36L) {
                "حجم بيانات PCM غير صالح لملف WAV قياسي"
            }
            require(dataSizeLong % bytesPerFrame == 0L) { "بيانات PCM الأخيرة لا تنتهي عند حد إطار صوتي" }
            val dataSize = dataSizeLong.toInt()
            val completed = File(cacheDir, "sari_dub_${System.currentTimeMillis()}_validated.wav")
            try {
                completed.outputStream().buffered().use { stream ->
                    stream.write(makeWavHeader(fmt.sampleRate, fmt.channels, fmt.bitsPerSample, dataSize))
                    FileInputStream(raw).use { input -> input.copyTo(stream, 64 * 1024) }
                }
                val validation = validateWav(completed)
                Log.i(TAG, "pre-save validation passed: $validation; engine=${tts.defaultEngine ?: "unknown"}; voice=${tts.voice?.name ?: "unknown"}")
                FileInputStream(completed).use { input -> input.copyTo(output, 64 * 1024) }
                output.flush()
            } finally {
                completed.delete()
            }
        } finally {
            tts.setSpeechRate(1.0f)
            first.delete()
            raw.delete()
        }
    }

    private fun rateFor(text: String, slotMs: Long): Float {
        val seconds = slotMs.coerceAtLeast(250L) / 1000.0
        // Approximate Arabic TTS natural pace, then clamp to a comprehensible range.
        return (text.trim().length / (seconds * 14.0)).toFloat().coerceIn(0.75f, 1.8f)
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
        check(latch.await(120, TimeUnit.SECONDS)) { "انتهت مهلة توليد TTS للمقطع" }
        check(status.get() == 1 && file.exists() && file.length() > 44L) { "فشل synthesizeToFile للمقطع (status=${status.get()})" }
    }

    private fun readWav(file: File): WavData {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") { "ملف TTS ليس WAV صالحًا: ${file.name}" }
        var offset = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var byteRate = 0L
        var blockAlign = 0
        var data: ByteArray? = null
        val declaredRiffSize = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        require(declaredRiffSize == bytes.size.toLong() - 8L) { "حجم RIFF لا يطابق طول الملف: ${file.name}" }
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val begin = offset + 8
            require(size <= Int.MAX_VALUE && begin.toLong() + size <= bytes.size) { "Chunk WAV يتجاوز حدود الملف: $id في ${file.name}" }
            when (id) {
                "fmt " -> if (size >= 16) {
                    val b = ByteBuffer.wrap(bytes, begin, size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    format = b.short.toInt() and 0xffff
                    channels = b.short.toInt() and 0xffff
                    sampleRate = b.int
                    byteRate = b.int.toLong() and 0xffffffffL
                    blockAlign = b.short.toInt() and 0xffff
                    bits = b.short.toInt() and 0xffff
                }
                "data" -> data = bytes.copyOfRange(begin, begin + size.toInt())
            }
            offset = begin + size.toInt() + (size.toInt() and 1)
        }
        require(format >= 0 && channels > 0 && sampleRate > 0 && bits > 0 && data != null) { "WAV لا يحتوي على fmt/data صالحين: ${file.name}" }
        require(byteRate > 0L && blockAlign > 0) { "قيم byteRate/blockAlign مفقودة أو غير صالحة: ${file.name}" }
        val expectedBlockAlign = channels * bits / 8
        require(expectedBlockAlign > 0 && blockAlign == expectedBlockAlign) { "blockAlign لا يطابق تنسيق الصوت: ${file.name}" }
        require(byteRate == sampleRate.toLong() * expectedBlockAlign) { "byteRate لا يطابق معدل العينة: ${file.name}" }
        if (format == 1) require(data!!.size % blockAlign == 0) { "بيانات PCM غير محاذاة لإطار صوتي: ${file.name}" }
        require(offset == bytes.size) { "تخطيط chunks WAV غير مكتمل أو يحتوي بايتات زائدة: ${file.name}" }
        return WavData(sampleRate, channels, bits, format, data!!, byteRate, blockAlign)
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
                    val mixed = (old + add).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    existing[p] = mixed.toByte()
                    existing[p + 1] = (mixed shr 8).toByte()
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

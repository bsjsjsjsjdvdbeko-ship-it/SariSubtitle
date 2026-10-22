package com.sari.subtitle.v2

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import java.util.Locale

/** Genuine on-device STT using Vosk. Audio is decoded with Android MediaCodec; no audio is uploaded. */
class OfflineVoskTranscriber(private val context: Context) {
    data class Output(val text: String, val srt: String, val segmentCount: Int, val language: String)
    private data class Word(val text: String, val start: Double, val end: Double)

    fun transcribe(uri: Uri, language: String, progress: (String) -> Unit = {}): Output {
        val modelDir = VoskModelManager.installedModel(context, language)
            ?: throw IllegalStateException("لا يوجد نموذج Vosk محلي مثبت. افتح الإعدادات ثم نزّل النموذج العربي أو الإنجليزي أولاً.")
        val words = mutableListOf<Word>()
        val fallbackSegments = mutableListOf<Pair<Double, String>>()
        var decodedSeconds = 0.0
        var lastReportedSecond = -1
        var recognizer: Recognizer? = null
        val model = try { Model(modelDir.absolutePath) } catch (e: Exception) {
            throw IllegalStateException("تعذر فتح نموذج Vosk المحلي: ${e.message ?: "ملفات النموذج غير سليمة"}", e)
        }
        try {
            recognizer = Recognizer(model, 16000.0f).apply { setWords(true) }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                var track = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(i)
                    val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) { track = i; format = candidate; break }
                }
                if (track < 0 || format == null) throw IllegalArgumentException("لم يتم العثور على مسار صوت داخل الملف")
                extractor.selectTrack(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("صيغة الصوت غير معروفة")
                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0); codec.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
                    var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                    var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
                    var sourceFrame = 0L
                    var nextOutputFrame = 0.0
                    val outputSamples = ByteArrayOutputStream(16 * 1024)
                    var emittedSamples = 0L

                    fun consumeAvailablePcm(force: Boolean = false) {
                        val bytes = outputSamples.toByteArray()
                        val usable = bytes.size - (bytes.size % 2)
                        if (usable == 0) return
                        // Keep chunks aligned to 16-bit PCM samples and avoid large recognizer calls.
                        var offset = 0
                        val chunkSize = 8192
                        while (usable - offset >= 2) {
                            val count = minOf(chunkSize, usable - offset) and -2
                            val chunk = bytes.copyOfRange(offset, offset + count)
                            if (recognizer!!.acceptWaveForm(chunk, chunk.size)) {
                                collectResult(recognizer!!.result, words, fallbackSegments, emittedSamples / 16000.0)
                            }
                            offset += count
                        }
                        outputSamples.reset()
                        if (offset < usable) outputSamples.write(bytes, offset, usable - offset)
                        if (force && outputSamples.size() >= 2) {
                            val remaining = outputSamples.toByteArray()
                            if (remaining.isNotEmpty() && recognizer!!.acceptWaveForm(remaining, remaining.size)) {
                                collectResult(recognizer!!.result, words, fallbackSegments, emittedSamples / 16000.0)
                            }
                            outputSamples.reset()
                        }
                    }

                    while (!outputDone) {
                        if (!inputDone) {
                            val inputIndex = codec.dequeueInputBuffer(10000)
                            if (inputIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inputIndex)!!
                                val size = extractor.readSampleData(inputBuffer, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val outputIndex = codec.dequeueOutputBuffer(info, 10000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outFormat = codec.outputFormat
                                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                if (sampleRate <= 0 || channels <= 0) throw IllegalStateException("إعدادات الصوت المفكوك غير صالحة")
                            }
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> if (outputIndex >= 0) {
                                val buffer = codec.getOutputBuffer(outputIndex)
                                if (buffer != null && info.size > 0) {
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                                    val bytesPerSample = if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                    val frameBytes = channels * bytesPerSample
                                    while (pcm.remaining() >= frameBytes) {
                                        var sum = 0.0
                                        repeat(channels) {
                                            val value = if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                                                (pcm.float * 32767.0).coerceIn(-32768.0, 32767.0)
                                            } else pcm.short.toDouble()
                                            sum += value
                                        }
                                        val mono = (sum / channels).roundToInt().coerceIn(-32768, 32767)
                                        if (sourceFrame.toDouble() + 0.0001 >= nextOutputFrame) {
                                            outputSamples.write(mono and 0xff); outputSamples.write((mono shr 8) and 0xff)
                                            emittedSamples++
                                            nextOutputFrame += sampleRate.toDouble() / 16000.0
                                        }
                                        sourceFrame++
                                    }
                                    decodedSeconds = sourceFrame.toDouble() / sampleRate
                                    consumeAvailablePcm()
                                    val sec = decodedSeconds.toInt()
                                    if (sec / 10 != lastReportedSecond / 10) {
                                        lastReportedSecond = sec
                                        progress("تفريغ محلي دون إنترنت… ${sec / 60}:${(sec % 60).toString().padStart(2, '0')}")
                                    }
                                }
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                    consumeAvailablePcm(force = true)
                    collectResult(recognizer!!.finalResult, words, fallbackSegments, decodedSeconds)
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    codec.release()
                }
            } finally { extractor.release() }
        } finally {
            try { recognizer?.close() } catch (_: Exception) {}
            try { model.close() } catch (_: Exception) {}
        }

        val cues = wordsToCues(words, fallbackSegments, decodedSeconds)
        if (cues.isEmpty()) throw IllegalStateException("لم يستطع المحرك المحلي تمييز كلام واضح من الصوت")
        val fullText = cues.joinToString(" ") { it.text }.trim()
        val srt = cues.mapIndexed { index, cue ->
            "${index + 1}\n${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text}"
        }.joinToString("\n\n") + "\n"
        val resultLanguage = if (language.lowercase().startsWith("en")) "en" else "ar"
        return Output(fullText, srt, cues.size, resultLanguage)
    }

    private fun collectResult(json: String, words: MutableList<Word>, fallback: MutableList<Pair<Double, String>>, atSeconds: Double) {
        val obj = JSONObject(json)
        val text = obj.optString("text", "").trim()
        val arr = obj.optJSONArray("result")
        var gotWords = false
        if (arr != null) for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val token = w.optString("word", "").trim()
            if (token.isNotEmpty() && w.has("start") && w.has("end")) {
                words += Word(token, w.optDouble("start", atSeconds), w.optDouble("end", atSeconds + 0.5))
                gotWords = true
            }
        }
        if (!gotWords && text.isNotBlank()) fallback += atSeconds to text
    }

    private fun wordsToCues(words: List<Word>, fallback: List<Pair<Double, String>>, duration: Double): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        if (words.isNotEmpty()) {
            var group = mutableListOf<Word>()
            fun flush() {
                if (group.isEmpty()) return
                val start = (group.first().start * 1000).toLong().coerceAtLeast(0L)
                val end = (group.last().end * 1000).toLong().coerceAtLeast(start + 400L)
                cues += SubtitleCue(cues.size + 1, start, end, group.joinToString(" ") { it.text })
                group = mutableListOf()
            }
            for (word in words) {
                if (group.isNotEmpty() && (group.size >= 8 || word.end - group.first().start >= 4.5 || word.start - group.last().end > 1.0)) flush()
                group += word
            }
            flush()
        } else {
            fallback.forEachIndexed { i, item ->
                val start = (item.first * 1000).toLong().coerceAtLeast(0L)
                val next = fallback.getOrNull(i + 1)?.first ?: duration
                val end = (maxOf(start / 1000.0 + 1.0, minOf(next, start / 1000.0 + 6.0)) * 1000).toLong()
                cues += SubtitleCue(cues.size + 1, start, maxOf(start + 500L, end), item.second)
            }
        }
        return cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }.mapIndexed { i, c -> c.copy(index = i + 1) }
    }

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        val millis = safe % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}

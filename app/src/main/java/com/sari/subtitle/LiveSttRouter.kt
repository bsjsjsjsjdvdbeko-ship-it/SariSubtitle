package com.sari.subtitle

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Stage 41: live-chunk routing coordinator.
 * It accepts already-captured PCM16 chunks; it does not own MediaProjection/AudioRecord.
 * This keeps capture permissions/lifecycle separate from routing and makes the component
 * safe to integrate into the existing foreground capture service later.
 */
class LiveSttRouter(private val context: Context) {
    companion object {
        private const val QUALITY_FAILOVER_THRESHOLD = 55
    }
    data class Config(
        val language: String = "auto",
        val selectedProvider: String = SubtitleSettings.STT_AUTO,
        val sampleRateHz: Int = 16_000,
        val channels: Int = 1
    )

    data class LiveCue(
        val sequence: Long,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val language: String,
        val provider: String,
        val isFinal: Boolean = true,
        val revision: Int = 0
    )

    data class Result(
        val cues: List<LiveCue>,
        val droppedSilenceChunks: Int,
        val providerFailures: Int
    )

    private val vad = SpeechActivityDetector()

    fun processChunk(chunk: AudioChunker.Chunk, config: Config): Result {
        val vadResult = vad.analyze(chunk.pcm)
        if (!vadResult.isSpeech) return Result(emptyList(), 1, 0)
        val wav = File.createTempFile("sari_live_", ".wav", context.cacheDir)
        try {
            writeWav(wav, chunk.pcm, config.sampleRateHz, config.channels)
            val uri = Uri.fromFile(wav)
            val routed = route(config, uri)
            val cues = parseAndShiftSrt(routed.srt, chunk.startMs, chunk.sequence, routed.provider, routed.language)
            return Result(cues, 0, 0)
        } catch (e: Exception) {
            return Result(emptyList(), 0, 1)
        } finally {
            wav.delete()
        }
    }

    private data class Routed(val provider: String, val language: String, val srt: String)
    private data class TranscriptionResult(val language: String, val text: String, val srt: String, val segmentCount: Int)

    private fun route(config: Config, uri: Uri): Routed {
        val settings = SubtitleSettings(context)
        val localInstalled = VoskModelManager.isInstalled(context, VoskModelManager.ARABIC) ||
            VoskModelManager.isInstalled(context, VoskModelManager.ENGLISH)
        val selected = config.selectedProvider
        val pool = when (selected) {
            SubtitleSettings.STT_LOCAL -> emptyList()
            SubtitleSettings.STT_DEEPGRAM -> listOf(SubtitleSettings.STT_DEEPGRAM, SubtitleSettings.STT_GROQ)
            SubtitleSettings.STT_GROQ -> listOf(SubtitleSettings.STT_GROQ, SubtitleSettings.STT_DEEPGRAM)
            SubtitleSettings.STT_OPENAI -> listOf(SubtitleSettings.STT_OPENAI)
            else -> listOf(SubtitleSettings.STT_GROQ, SubtitleSettings.STT_DEEPGRAM)
        }.filter { p ->
            when (p) {
                SubtitleSettings.STT_GROQ -> settings.groqApiKey.isNotBlank()
                SubtitleSettings.STT_DEEPGRAM -> settings.deepgramApiKey.isNotBlank()
                SubtitleSettings.STT_OPENAI -> settings.openAiApiKey.isNotBlank()
                else -> false
            }
        }
        val profile = AdaptiveContextStore.get(context)
        val snapshot = AdaptiveRoutingEngine.snapshot(
            context, ProviderScoringEngine.Task.STT,
            sourceLanguage = config.language,
            chunkDurationMs = profile.typicalChunkDurationMs,
            speechSpeedWpm = profile.speechSpeedWpm
        )
        val providerIds = pool.map(::healthId).distinct()
        val decision = ProviderDecisionEngine.decide(
            context = context,
            providers = providerIds,
            configured = { id -> when (id) {
                ProviderConnectionTester.GROQ -> settings.groqApiKey.isNotBlank()
                ProviderConnectionTester.DEEPGRAM -> settings.deepgramApiKey.isNotBlank()
                ProviderConnectionTester.OPENAI -> settings.openAiApiKey.isNotBlank()
                else -> false
            } },
            forceProvider = selected.takeIf { it !in setOf(SubtitleSettings.STT_AUTO, SubtitleSettings.STT_LOCAL) }?.let(::healthId),
            task = ProviderScoringEngine.Task.STT,
            adaptiveContext = snapshot,
            recordTrace = true
        )

        return try {
            val success = ProviderDecisionFailoverController.execute(
                context = context,
                decision = decision,
                qualityThreshold = QUALITY_FAILOVER_THRESHOLD,
                operation = { id ->
                    when (provider(id)) {
                        SubtitleSettings.STT_DEEPGRAM -> DeepgramTranscriptionService(context) { settings.deepgramApiKey }
                            .transcribe(uri, config.language)
                            .let { TranscriptionResult(it.language, it.text, it.srt, it.segmentCount) }
                        SubtitleSettings.STT_OPENAI -> OpenAIWhisperService(context) { settings.openAiApiKey }
                            .transcribe(uri, config.language)
                            .let { TranscriptionResult(it.language, it.text, it.srt, it.segmentCount) }
                        SubtitleSettings.STT_GROQ -> GroqWhisperService(context) { settings.groqApiKey }
                            .transcribe(uri, config.language)
                            .let { TranscriptionResult(it.language, it.text, it.srt, it.segmentCount) }
                        else -> throw IllegalStateException("Provider STT غير مدعوم: $id")
                    }
                },
                validate = { response -> require(response.srt.isNotBlank()) { "استجابة STT فارغة" } },
                quality = { response ->
                    parseAndShiftSrt(response.srt, 0L, 0L, "stt", response.language)
                        .map { LiveQualitySignalEngine.scoreStt(it.text, config.language).score }
                        .takeIf { it.isNotEmpty() }?.average()?.toInt()
                },
                successDetail = { id, latency -> "نجح chunk STT عبر ${provider(id)} خلال ${latency}ms" }
            )
            val selectedProvider = provider(success.provider)
            Routed(selectedProvider, success.value.language, success.value.srt)
        } catch (e: ProviderDecisionFailoverController.ExhaustedException) {
            // Local Vosk remains an offline safety fallback and is not mixed into cloud scoring.
            if (localInstalled && selected != SubtitleSettings.STT_OPENAI) {
                val local = OfflineVoskTranscriber(context).transcribe(uri, config.language)
                Routed(SubtitleSettings.STT_LOCAL, local.language, local.srt)
            } else {
                throw e
            }
        }
    }

    private fun healthId(provider: String): String = when (provider) {
        SubtitleSettings.STT_DEEPGRAM -> ProviderConnectionTester.DEEPGRAM
        SubtitleSettings.STT_OPENAI -> ProviderConnectionTester.OPENAI
        else -> ProviderConnectionTester.GROQ
    }

    private fun provider(id: String): String = when (id) {
        ProviderConnectionTester.DEEPGRAM -> SubtitleSettings.STT_DEEPGRAM
        ProviderConnectionTester.OPENAI -> SubtitleSettings.STT_OPENAI
        else -> SubtitleSettings.STT_GROQ
    }

    private fun parseAndShiftSrt(srt: String, offsetMs: Long, sequence: Long, provider: String, language: String): List<LiveCue> {
        val regex = Regex("(?m)(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})\\s*\\n([^\\n]+(?:\\n(?!\\d+\\n|\\d{2}:)[^\\n]+)*)")
        return regex.findAll(srt).mapIndexedNotNull { index, m ->
            fun ms(h: String, min: String, sec: String, milli: String) = h.toLong() * 3_600_000L + min.toLong() * 60_000L + sec.toLong() * 1_000L + milli.toLong()
            val start = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) + offsetMs
            val end = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8]) + offsetMs
            val text = m.groupValues[9].trim().replace(Regex("\\s+"), " ")
            if (end <= start || text.isBlank()) null else LiveCue(sequence * 10_000 + index, start, end, text, language, provider, isFinal = true, revision = 0)
        }.toList()
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        FileOutputStream(file).use { out ->
            fun le(v: Int) { out.write(byteArrayOf((v and 255).toByte(), ((v shr 8) and 255).toByte(), ((v shr 16) and 255).toByte(), ((v shr 24) and 255).toByte())) }
            out.write("RIFF".toByteArray()); le(36 + pcm.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le(16); out.write(byteArrayOf(1, 0, channels.toByte(), 0))
            out.write(byteArrayOf((sampleRate and 255).toByte(), (sampleRate shr 8 and 255).toByte(), (sampleRate shr 16 and 255).toByte(), (sampleRate shr 24 and 255).toByte()))
            out.write(byteArrayOf((byteRate and 255).toByte(), (byteRate shr 8 and 255).toByte(), (byteRate shr 16 and 255).toByte(), (byteRate shr 24 and 255).toByte()))
            val block = channels * 2; out.write(byteArrayOf(block.toByte(), 0, 16, 0)); out.write("data".toByteArray()); le(pcm.size); out.write(pcm)
        }
    }
}

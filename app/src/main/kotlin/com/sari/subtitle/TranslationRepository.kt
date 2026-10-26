package com.sari.subtitle

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranslationRepository(private val context: Context) {
    private val deduplicator = TranscriptDeduplicator()
    private val memory by lazy { AppDatabase.get(context).translationMemoryDao() }
    private val episodeContext by lazy { EpisodeContextStore(context) }

    suspend fun translateAudio(audioFile: File, capturedAtElapsedMs: Long = android.os.SystemClock.elapsedRealtime(), durationMs: Long = AudioChunker.CHUNK_MS): TranslationEntity = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context).load()

        val preContext = if (settings.contextAwareTranslation) episodeContext.snapshot() else TranslationContext()
        val whisperPrompt = buildWhisperPrompt(settings, preContext)
        val whisperResult = if (settings.provider == SettingsRepository.PROVIDER_OMNIROUTE) {
            if (settings.smartRoutingEnabled || settings.adaptiveRoutingEnabled) {
                AiOrchestrator(context).transcribe(
                    audioFile = audioFile,
                    models = settings.omniRouteSttModels.split(',').map { it.trim() },
                    language = settings.speechLanguage,
                    prompt = whisperPrompt,
                    durationMs = durationMs,
                    quality = when (settings.translationQuality) {
                        SettingsRepository.QUALITY_FAST -> TranslationQuality.FAST
                        SettingsRepository.QUALITY_BALANCED -> TranslationQuality.BALANCED
                        else -> TranslationQuality.HUMAN
                    }
                ).result
            } else {
                OmniRouteClient(settings.omniRouteBaseUrl, settings.omniRouteKey).transcribe(
                    audioFile = audioFile,
                    models = settings.omniRouteSttModels.split(',').map { it.trim() },
                    language = settings.speechLanguage,
                    prompt = whisperPrompt
                )
            }
        } else {
            when (settings.sttProvider) {
                "deepgram" -> DeepgramClient(settings.deepgramKey).transcribe(audioFile, settings.speechLanguage, whisperPrompt)
                "vosk" -> {
                    val modelsRoot = File(context.filesDir, "vosk-models")
                    val modelDir = modelsRoot.listFiles()?.firstOrNull {
                        it.isDirectory && (it.name.startsWith("vosk-model-ar-mgb2") || File(it, "am").exists())
                    } ?: error("نموذج Vosk العربي غير موجود. افتح إعدادات STT وحمّل النموذج أولاً.")
                    VoskClient(modelDir).transcribe(audioFile, settings.speechLanguage, whisperPrompt)
                }
                else -> WhisperClient(settings.speechKey).transcribeDetailed(audioFile, settings.speechLanguage, whisperPrompt)
            }
        }
        val speech = whisperResult.text.trim()
        val sourceLanguage = settings.sourceLanguage.ifBlank { "auto" }
        val targetLanguage = settings.targetLanguage.ifBlank { "ar" }
        if (speech.isBlank() || !deduplicator.shouldTranslate(speech)) {
            return@withContext TranslationEntity(
                sourceText = speech, translatedText = "", sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage, createdAt = System.currentTimeMillis()
            )
        }


        val hash = TextHash.sha256(speech)
        val cached = if (settings.translationMemoryEnabled) memory.find(hash, sourceLanguage, targetLanguage) else null

        val translated = cached?.translatedText ?: run {
            val contextPrompt = if (settings.contextAwareTranslation) episodeContext.snapshot() else TranslationContext()
            val quality = when (settings.translationQuality) {
                SettingsRepository.QUALITY_FAST -> TranslationQuality.FAST
                SettingsRepository.QUALITY_BALANCED -> TranslationQuality.BALANCED
                else -> TranslationQuality.HUMAN
            }
            val result = if (settings.provider == SettingsRepository.PROVIDER_OMNIROUTE) {
                if (settings.smartRoutingEnabled || settings.adaptiveRoutingEnabled) {
                    val features = AudioFeatureAnalyzer.analyze(audioFile)
                    val seconds = (durationMs.coerceAtLeast(1L) / 1000.0)
                    val wordsPerSecond = speech.split(Regex("\\s+")).count { it.isNotBlank() }.toDouble() / seconds
                    AiOrchestrator(context).translate(
                        text = speech,
                        source = sourceLanguage,
                        target = targetLanguage,
                        models = settings.omniRouteTranslationModels.split(',').map { it.trim() },
                        contextPrompt = contextPrompt,
                        quality = quality,
                        speechProfile = SpeechProfile(wordsPerSecond, features.noiseLevel, features)
                    ).text
                } else {
                    OmniRouteClient(settings.omniRouteBaseUrl, settings.omniRouteKey).translate(
                        text = speech,
                        source = sourceLanguage,
                        target = targetLanguage,
                        model = settings.omniRouteTranslationModel,
                        context = contextPrompt,
                        quality = quality
                    )
                }
            } else {
                val provider = when (settings.provider) {
                    SettingsRepository.PROVIDER_GROQ -> TranslationProvider.GROQ
                    SettingsRepository.PROVIDER_GOOGLE -> TranslationProvider.GOOGLE
                    SettingsRepository.PROVIDER_OPENAI -> TranslationProvider.OPENAI
                    else -> TranslationProvider.MYMEMORY
                }
                val translationApiKey = when (provider) { TranslationProvider.GROQ -> settings.speechKey; TranslationProvider.OPENAI -> settings.openAiKey.ifBlank { settings.translationKey }; else -> settings.translationKey }
                TranslationClient(provider, translationApiKey, settings.myMemoryEmail)
                    .translateWithContext(speech, sourceLanguage, targetLanguage, contextPrompt, quality)
            }
            if (settings.translationMemoryEnabled) {
                memory.insert(TranslationMemoryEntity(
                    sourceHash = hash, sourceText = speech, translatedText = result,
                    sourceLanguage = sourceLanguage, targetLanguage = targetLanguage,
                    createdAt = System.currentTimeMillis()
                ))
                memory.trim()
            }
            result
        }

        episodeContext.addTurn(speech, translated)
        val entity = TranslationEntity(
            sourceText = speech,
            translatedText = translated,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            createdAt = System.currentTimeMillis()
        )
        val segmentStart = whisperResult.segments.minOfOrNull { it.startMs }?.toLong() ?: 0L
        val segmentEnd = whisperResult.segments.maxOfOrNull { it.endMs }?.toLong() ?: durationMs
        val cueDuration = (segmentEnd - segmentStart).coerceIn(900L, 6_000L)
        val cueStart = capturedAtElapsedMs + segmentStart
        LiveSubtitleBus.publish(speech, translated, cueStart, cueDuration)
        context.startService(Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_UPDATE)
            .putExtra(OverlayService.EXTRA_ORIGINAL, speech)
            .putExtra(OverlayService.EXTRA_TRANSLATED, translated)
            .putExtra(OverlayService.EXTRA_DURATION_MS, cueDuration))
        AppDatabase.get(context).translationDao().insert(entity)
        entity
    }

    private fun buildWhisperPrompt(settings: AppSettings, context: TranslationContext): String {
        val glossary = (context.glossary.entries.map { "${it.key}=${it.value}" } + settings.glossary.lines().map { it.trim() }.filter { it.isNotBlank() }).joinToString(", ")
        val recent = context.previousSegments.takeLast(4).joinToString(" | ")
        return buildString {
            if (settings.translationQuality == SettingsRepository.QUALITY_HUMAN) append("[HIGH_ACCURACY] ")
            append("Transcribe spoken dialogue accurately. Preserve names, technical terms, places, and recurring words exactly when heard. ")
            if (glossary.isNotBlank()) append("Known terminology: ").append(glossary).append(". ")
            if (settings.contextPrompt.isNotBlank()) append("Additional translation context: ").append(settings.contextPrompt.take(1000)).append(". ")
            if (recent.isNotBlank()) append("Recent context for spelling only: ").append(recent)
        }.take(3_000)
    }

    fun resetEpisodeContext() = episodeContext.clearSession()

    suspend fun clearTranslationMemory() = withContext(Dispatchers.IO) { memory.clear() }
}

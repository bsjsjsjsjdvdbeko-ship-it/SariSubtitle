package com.sari.subtitle

import android.content.Context
import java.util.concurrent.CancellationException

/**
 * Stage 43: text-only live translation router.
 * The router receives one validated STT cue and returns Arabic text while preserving timing.
 * It never receives PCM/audio and never stores credentials or transcripts.
 */
class LiveTranslationRouter(private val context: Context) {
    companion object {
        private const val QUALITY_FAILOVER_THRESHOLD = 55
    }
    data class Result(
        val text: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val provider: String,
        val latencyMs: Long
    )

    fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String = "ar",
        history: List<LiveTranslationContextBuffer.Item> = emptyList()
    ): Result {
        val clean = validate(text)
        val source = normalizeLanguage(sourceLanguage).ifBlank {
            LanguageDetector.detect(clean.take(180))?.lowercase()?.substringBefore('-').orEmpty()
        }.ifBlank { "en" }
        val target = normalizeLanguage(targetLanguage).ifBlank { "ar" }

        // Do not waste a network request when the STT result is already Arabic.
        if (source == target || (target == "ar" && looksArabic(clean) && !looksForeign(clean))) {
            return Result(clean, source, target, "passthrough", 0L)
        }

        val settings = SubtitleSettings(context)
        val contextText = history.takeLast(3).map { item ->
            "${item.sourceLanguage}: ${item.sourceText} → ${item.translatedText}"
        }
        val configured = listOf(ProviderConnectionTester.OMNIROUTE, ProviderConnectionTester.GEMINI)
        val adaptive = AdaptiveContextStore.get(context)
        val snapshot = AdaptiveRoutingEngine.snapshot(
            context = context,
            task = ProviderScoringEngine.Task.TRANSLATION,
            sourceLanguage = source,
            audioDurationMs = null,
            chunkDurationMs = adaptive.typicalChunkDurationMs,
            speechSpeedWpm = adaptive.speechSpeedWpm
        )
        val configuredFn: (String) -> Boolean = { id -> when (id) {
            ProviderConnectionTester.OMNIROUTE -> settings.omniRouteEnabled || settings.hasOmniRouteApiKey() || settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE
            ProviderConnectionTester.GEMINI -> settings.hasGeminiApiKey()
            else -> false
        } }
        val decision = ProviderDecisionEngine.decide(
            context = context,
            providers = configured,
            configured = configuredFn,
            forceProvider = when (settings.translationProvider) {
                SubtitleSettings.TRANSLATION_OMNIROUTE -> ProviderConnectionTester.OMNIROUTE
                SubtitleSettings.TRANSLATION_GEMINI -> ProviderConnectionTester.GEMINI
                else -> null
            },
            task = ProviderScoringEngine.Task.TRANSLATION,
            adaptiveContext = snapshot,
            recordTrace = true
        )

        try {
            val success = ProviderDecisionFailoverController.execute(
                context = context,
                decision = decision,
                qualityThreshold = QUALITY_FAILOVER_THRESHOLD,
                operation = { provider ->
                    when (provider) {
                        ProviderConnectionTester.OMNIROUTE -> OmniRouteTranslationClient(settings)
                            .translateBatch(listOf(clean), source, target, contextText).single()
                        ProviderConnectionTester.GEMINI -> GeminiTranslationService
                            .translateBatch(listOf(clean), source, target, settings.geminiApiKey, contextText).single()
                        else -> throw IllegalStateException("مزود ترجمة مباشر غير مدعوم: $provider")
                    }.trim()
                },
                validate = { translated -> validateTranslated(translated, clean) },
                quality = { translated ->
                    LiveQualitySignalEngine.scoreTranslation(clean, translated, target).score
                },
                successDetail = { provider, latency -> "نجح طلب ترجمة live عبر $provider خلال ${latency}ms" }
            )
            return Result(
                success.value, source, target, success.provider, success.latencyMs
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: ProviderDecisionFailoverController.ExhaustedException) {
            // Continue to the pre-existing secondary text-only fallback chain below.
        }

        // Reuse the existing text-only fallback chain for users who configured Azure/Hugging Face/DeepL.
        return try {
            val started = android.os.SystemClock.elapsedRealtime()
            val translated = SrtTranslationService.translateLiveText(
                text = clean,
                sourceLang = source,
                targetLang = target,
                context = context
            )
            validateTranslated(translated, clean)
            Result(
                translated.trim(), source, target, "text-fallback",
                (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            )
        } catch (fallback: Throwable) {
            throw IllegalStateException(
                "تعذر ترجمة المقطع المباشر: ${fallback.message ?: fallback.javaClass.simpleName}",
                fallback
            )
        }
    }

    private fun validate(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        require(clean.isNotBlank()) { "نص STT فارغ" }
        require(clean.length <= 450) { "مقطع live أطول من الحد المسموح" }
        return clean
    }

    private fun validateTranslated(translated: String, original: String) {
        require(translated.isNotBlank()) { "الترجمة فارغة" }
        require(translated.length <= 1200) { "الترجمة المباشرة طويلة بشكل غير منطقي" }
        require(!translated.equals(original, ignoreCase = true) || looksArabic(translated)) {
            "المزود أعاد النص الأصلي دون ترجمة واضحة"
        }
    }

    private fun normalizeLanguage(value: String): String = value.trim().lowercase().substringBefore('-')
        .takeIf { it.matches(Regex("[a-z]{2,3}")) }.orEmpty()

    private fun looksArabic(text: String): Boolean = Regex("[\\u0600-\\u06FF]").containsMatchIn(text)
    private fun looksForeign(text: String): Boolean = Regex("[A-Za-z]").containsMatchIn(text)
}

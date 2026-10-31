package com.sari.subtitle

import android.content.Context
import android.os.SystemClock

/**
 * Stage 52: real provider benchmark runner.
 *
 * The runner executes actual configured provider calls. It never invents latency or quality.
 * Quality is reference-free structural quality, not WER/CER or semantic truth.
 */
object ProviderBenchmarkEngine {
    data class Case(
        val source: String,
        val sourceLanguage: String,
        val targetLanguage: String = "ar",
        /** Optional ground-truth translation used only for offline reference evaluation. */
        val referenceTranslation: String? = null
    )

    data class ProviderResult(
        val provider: String,
        val samples: Int,
        val successes: Int,
        val failures: Int,
        val averageLatencyMs: Long?,
        val qualityScore: Int?,
        val qualitySamples: Int,
        val qualityReasons: List<String>,
        val referenceBleu: Double? = null,
        val referenceChrf: Double? = null,
        val referenceScore: Int? = null,
        val referenceSamples: Int = 0
    )

    fun benchmarkTranslation(
        context: Context,
        providers: List<String>,
        cases: List<Case>
    ): List<ProviderResult> {
        require(cases.isNotEmpty()) { "يجب توفير حالة benchmark واحدة على الأقل." }
        val settings = SubtitleSettings(context)
        return providers.distinct().map { provider ->
            var successes = 0
            var failures = 0
            var latencySum = 0L
            var qualitySum = 0L
            var qualitySamples = 0
            var bleuSum = 0.0
            var chrfSum = 0.0
            var referenceSamples = 0
            val reasons = linkedSetOf<String>()

            for (testCase in cases) {
                val started = SystemClock.elapsedRealtime()
                try {
                    val translated = when (provider) {
                        ProviderConnectionTester.OMNIROUTE -> OmniRouteTranslationClient(settings)
                            .translateBatch(listOf(testCase.source), testCase.sourceLanguage, testCase.targetLanguage)
                            .single()
                        ProviderConnectionTester.GEMINI -> GeminiTranslationService
                            .translateBatch(listOf(testCase.source), testCase.sourceLanguage, testCase.targetLanguage, settings.geminiApiKey)
                            .single()
                        else -> throw IllegalArgumentException("Benchmark غير مدعوم للمزود: $provider")
                    }.trim()
                    val latency = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                    val signal = LiveQualitySignalEngine.scoreTranslation(
                        testCase.source,
                        translated,
                        testCase.targetLanguage
                    )
                    successes++
                    latencySum += latency
                    ProviderMetricsStore.recordSuccess(context, provider, latency)
                    if (signal.measured) {
                        qualitySum += signal.score
                        qualitySamples++
                        ProviderMetricsStore.recordQuality(context, provider, signal.score)
                    }
                    reasons += signal.reasons

                    testCase.referenceTranslation?.takeIf { it.isNotBlank() }?.let { reference ->
                        val referenceResult = GroundTruthQualityEvaluator.evaluate(reference, translated)
                        bleuSum += referenceResult.bleu
                        chrfSum += referenceResult.chrf
                        referenceSamples++
                    }
                } catch (_: Throwable) {
                    failures++
                    ProviderMetricsStore.recordFailure(context, provider)
                }
            }

            ProviderResult(
                provider = provider,
                samples = cases.size,
                successes = successes,
                failures = failures,
                averageLatencyMs = if (successes > 0) latencySum / successes else null,
                qualityScore = if (qualitySamples > 0) (qualitySum / qualitySamples).toInt() else null,
                qualitySamples = qualitySamples,
                qualityReasons = reasons.toList(),
                referenceBleu = if (referenceSamples > 0) bleuSum / referenceSamples else null,
                referenceChrf = if (referenceSamples > 0) chrfSum / referenceSamples else null,
                referenceScore = if (referenceSamples > 0) {
                    (((bleuSum / referenceSamples) * 0.55 + (chrfSum / referenceSamples) * 0.45) * 100.0)
                        .toInt().coerceIn(0, 100)
                } else null,
                referenceSamples = referenceSamples
            )
        }
    }
}

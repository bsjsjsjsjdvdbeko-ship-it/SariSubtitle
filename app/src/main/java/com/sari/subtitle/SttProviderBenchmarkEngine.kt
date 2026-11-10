package com.sari.subtitle

import android.content.Context
import android.net.Uri
import android.os.SystemClock

/** Runs the same user-selected audio file through each configured prerecorded-STT API. */
object SttProviderBenchmarkEngine {
    data class Result(
        val provider: String,
        val latencyMs: Long?,
        val detectedLanguage: String,
        val transcript: String,
        val wordErrorRate: Double?,
        val characterErrorRate: Double?,
        val error: String? = null
    )

    private data class SttResponse(val language: String, val text: String)

    fun run(context: Context, uri: Uri, language: String, reference: String?, providers: List<String>): List<Result> {
        require(providers.isNotEmpty()) { "لا يوجد مزود STT مهيأ." }
        val settings = SubtitleSettings(context)
        return providers.distinct().map { provider ->
            val started = SystemClock.elapsedRealtime()
            try {
                val response = when (provider) {
                    ProviderConnectionTester.GROQ -> GroqWhisperService(context) { settings.groqApiKey }.transcribe(uri, language).let { SttResponse(it.language, it.text) }
                    ProviderConnectionTester.DEEPGRAM -> DeepgramTranscriptionService(context) { settings.deepgramApiKey }.transcribe(uri, language).let { SttResponse(it.language, it.text) }
                    ProviderConnectionTester.OMNIROUTE -> OmniRouteWhisperService(context, settings).transcribe(uri, language).let { SttResponse(it.language, it.text) }
                    else -> throw IllegalArgumentException("مزود STT غير مدعوم: $provider")
                }
                val latency = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                val ref = reference?.takeIf { it.isNotBlank() }
                Result(provider, latency, response.language, response.text,
                    ref?.let { SttErrorRateEvaluator.wer(it, response.text) },
                    ref?.let { SttErrorRateEvaluator.cer(it, response.text) })
            } catch (t: Throwable) {
                Result(provider, null, "", "", null, null, safeError(t))
            }
        }
    }

    private fun safeError(t: Throwable): String = (t.message ?: t.javaClass.simpleName)
        .replace(Regex("[\\r\\n\\t]+"), " ").take(180)
}

/** Reference-based WER/CER. Values are rates (0.0 = exact match; 1.0 = 100%). */
object SttErrorRateEvaluator {
    private fun normalized(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFKC)
        .replace(Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "")
        .replace(Regex("[\\u0622\\u0623\\u0625]"), "ا")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun wer(reference: String, hypothesis: String): Double = rate(
        normalized(reference).split(Regex("\\s+")).filter(String::isNotBlank),
        normalized(hypothesis).split(Regex("\\s+")).filter(String::isNotBlank)
    )
    fun cer(reference: String, hypothesis: String): Double = rate(
        normalized(reference).replace(" ", "").toList(), normalized(hypothesis).replace(" ", "").toList()
    )
    private fun <T> rate(reference: List<T>, hypothesis: List<T>): Double {
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
        var previous = IntArray(hypothesis.size + 1) { it }
        for (i in reference.indices) {
            val current = IntArray(hypothesis.size + 1)
            current[0] = i + 1
            for (j in hypothesis.indices) {
                val cost = if (reference[i] == hypothesis[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[hypothesis.size].toDouble() / reference.size
    }
}

package com.sari.subtitle

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** Real, low-payload connectivity/authentication checks. No audio or transcript is uploaded. */
object ProviderConnectionTester {
    data class Result(
        val provider: String,
        val state: ProviderHealthStore.State,
        val latencyMs: Long,
        val httpCode: Int,
        val detail: String,
        val retryAfterSec: Long = 0L,
        val quotaRemainingRequests: Long? = null,
        val quotaLimitRequests: Long? = null,
        val quotaResetAtMs: Long? = null
    )

    const val GROQ = "Groq"
    const val DEEPGRAM = "Deepgram"
    const val OPENAI = "OpenAI"
    const val GEMINI = "Gemini"
    const val OMNIROUTE = "OmniRoute"
    const val DEEPL = "DeepL"
    const val AZURE = "Azure Translator"
    const val HUGGING_FACE = "Hugging Face"

    fun test(context: Context, provider: String): Result {
        val settings = SubtitleSettings(context)
        return when (provider) {
            GROQ -> getJson(provider, "https://api.groq.com/openai/v1/models", "Bearer ${settings.groqApiKey}", settings.hasGroqApiKey())
            DEEPGRAM -> getJson(provider, "https://api.deepgram.com/v1/projects", "Token ${settings.deepgramApiKey}", settings.hasDeepgramApiKey())
            OPENAI -> getJson(provider, "https://api.openai.com/v1/models", "Bearer ${settings.openAiApiKey}", settings.hasOpenAiApiKey())
            GEMINI -> getJson(provider, "https://generativelanguage.googleapis.com/v1beta/models?key=${java.net.URLEncoder.encode(settings.geminiApiKey, "UTF-8")}", null, settings.hasGeminiApiKey())
            OMNIROUTE -> testOmniRoute(settings)
            DEEPL -> getJson(provider, "https://api-free.deepl.com/v2/usage", "DeepL-Auth-Key ${settings.deepLApiKey}", settings.hasDeepLApiKey())
            HUGGING_FACE -> getJson(provider, "https://huggingface.co/api/whoami-v2", "Bearer ${settings.huggingFaceToken}", settings.hasHuggingFaceToken())
            else -> Result(provider, ProviderHealthStore.State.ERROR, 0, 0, "مزوّد غير معروف")
        }
    }

    /** Azure Translator needs a text POST to prove the key/region; this sends only a fixed test word. */
    fun testAzure(context: Context): Result {
        val settings = SubtitleSettings(context)
        if (!settings.hasAzureTranslatorKey()) return Result(AZURE, ProviderHealthStore.State.NOT_CONFIGURED, 0, 0, "المفتاح غير مضبوط")
        val region = settings.azureTranslatorRegion.trim().ifBlank { "global" }
        val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=ar"
        return request(AZURE, URL(url), "POST", mapOf(
            "Ocp-Apim-Subscription-Key" to settings.azureTranslatorKey,
            "Ocp-Apim-Subscription-Region" to region,
            "Content-Type" to "application/json"
        ), "[{\"Text\":\"test\"}]", 20_000)
    }

    private fun testOmniRoute(settings: SubtitleSettings): Result = try {
        val started = android.os.SystemClock.elapsedRealtime()
        val message = OmniRouteTranslationClient(settings).testConnection()
        Result(OMNIROUTE, ProviderHealthStore.State.READY,
            max(0L, android.os.SystemClock.elapsedRealtime() - started), 200, message)
    } catch (e: Exception) {
        val parsed = parseHttpCode(e.message.orEmpty())
        Result(OMNIROUTE, if (parsed > 0) ProviderHealthStore.classify(parsed) else ProviderHealthStore.State.NETWORK_ERROR,
            0, parsed, safeMessage(e.message))
    }

    private fun getJson(provider: String, url: String, auth: String?, configured: Boolean): Result {
        if (!configured) return Result(provider, ProviderHealthStore.State.NOT_CONFIGURED, 0, 0, "المفتاح غير مضبوط")
        val headers = if (auth != null) mapOf("Authorization" to auth) else emptyMap()
        return request(provider, URL(url), "GET", headers, null, 20_000)
    }

    private fun request(provider: String, url: URL, method: String, headers: Map<String, String>, body: String?, timeoutMs: Int): Result {
        val started = android.os.SystemClock.elapsedRealtime()
        var code = 0
        return try {
            val connection = NetworkRequestRegistry.register(url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                doInput = true
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) doOutput = true
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                code = connection.responseCode
                val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
                val remaining = connection.getHeaderField("x-ratelimit-remaining-requests")?.toLongOrNull()
                    ?: connection.getHeaderField("x-ratelimit-remaining")?.toLongOrNull()
                val limit = connection.getHeaderField("x-ratelimit-limit-requests")?.toLongOrNull()
                    ?: connection.getHeaderField("x-ratelimit-limit")?.toLongOrNull()
                val resetRaw = connection.getHeaderField("x-ratelimit-reset-requests")
                    ?: connection.getHeaderField("x-ratelimit-reset")
                val resetAt = parseResetAtMs(resetRaw)
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
                val latency = max(0L, android.os.SystemClock.elapsedRealtime() - started)
                val state = ProviderHealthStore.classify(code)
                val detail = if (state == ProviderHealthStore.State.READY) {
                    "اتصال ومصادقة ناجحان"
                } else {
                    extractSafeError(response, code)
                }
                Result(provider, state, latency, code, detail, retryAfter, remaining, limit, resetAt)
            } finally {
                connection.disconnect()
                NetworkRequestRegistry.unregister(connection)
            }
        } catch (e: Exception) {
            val latency = max(0L, android.os.SystemClock.elapsedRealtime() - started)
            Result(provider, if (code > 0) ProviderHealthStore.classify(code) else ProviderHealthStore.State.NETWORK_ERROR,
                latency, code, safeMessage(e.message))
        }
    }

    private fun extractSafeError(body: String, code: Int): String {
        val clean = body.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return "HTTP $code" + if (clean.isBlank()) "" else ": ${clean.take(180)}"
    }

    private fun parseHttpCode(message: String): Int = Regex("HTTP\\s+(\\d{3})").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun parseResetAtMs(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val numeric = value.toDoubleOrNull() ?: return null
        val now = System.currentTimeMillis()
        return when {
            numeric > 1_000_000_000_000 -> numeric.toLong()
            numeric > 1_000_000_000 -> numeric.toLong() * 1000L
            else -> now + (numeric * 1000.0).toLong()
        }
    }

    private fun safeMessage(message: String?): String = message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(180).orEmpty().ifBlank { "تعذر الوصول إلى الخادم" }
}

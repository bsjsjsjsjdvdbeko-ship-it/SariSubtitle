package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * OpenAI-compatible OmniRoute gateway client.
 *
 * OmniRoute stays outside Android and is addressed through HTTPS (or a trusted local
 * development endpoint). It can route to multiple upstream providers and perform
 * server-side fallback. Sari additionally supports a small ordered model fallback
 * list so a single provider outage does not stop live subtitles.
 */
class OmniRouteClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = defaultClient()
) {
    @Volatile var lastResponseMeta: OmniResponseMeta? = null
        private set

    private val baseUrl = baseUrl.trim().trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }

    suspend fun transcribe(
        audioFile: File,
        models: List<String>,
        language: String,
        prompt: String = ""
    ): TranscriptResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح OmniRoute API من الإعدادات." }
        require(audioFile.exists() && audioFile.length() > 0) { "الملف الصوتي فارغ." }
        val candidates = models.map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("deepgram/nova-3") }
        var last: Exception? = null
        for ((index, model) in candidates.withIndex()) {
            try {
                return@withContext executeTranscription(audioFile, model, language, prompt)
            } catch (e: Exception) {
                last = e
                if (index < candidates.lastIndex) delay(250L * (index + 1))
            }
        }
        throw IllegalStateException("تعذر تحويل الصوت عبر OmniRoute وجميع مزودي STT المحددين.", last)
    }

    suspend fun translate(
        text: String,
        source: String,
        target: String,
        model: String,
        context: TranslationContext,
        quality: TranslationQuality
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح OmniRoute API من الإعدادات." }
        require(text.isNotBlank()) { "لا يوجد نص لترجمته." }
        val selectedModel = model.trim().ifBlank {
            if (quality == TranslationQuality.HUMAN) "openai/gpt-oss-120b" else "openai/gpt-oss-20b"
        }
        val system = buildSystemPrompt(source, target, context, quality)
        val payload = JSONObject()
            .put("model", selectedModel)
            .put("temperature", 0.0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", text)))
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Sari-Client", "SariSubtitle-Stage12")
            .header("X-Sari-Task", "live-translation")
            .header("X-Sari-Quality", quality.name.lowercase())
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        executeJson(request) { raw ->
            val choices = JSONObject(raw).optJSONArray("choices") ?: error("OmniRoute لم يُرجع choices.")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
            val result = message?.optString("content").orEmpty().trim()
            if (result.isBlank()) error("OmniRoute أعاد ترجمة فارغة.")
            result
        }
    }

    suspend fun fetchRuntimeMonitoring(): OmniMonitoringSnapshot = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح OmniRoute API من الإعدادات." }
        val health = fetchManagementJson("/api/monitoring/health")
        val limits = fetchManagementJson("/api/rate-limits")
        val budget = fetchManagementJson("/api/usage/budget")
        val telemetry = fetchManagementJson("/api/usage/request-logs")
        val availability = fetchManagementJson("/api/models/availability")
        val resilience = fetchManagementJson("/api/resilience")
        OmniMonitoringSnapshot(health, limits, budget, telemetry, availability, resilience)
    }

    private fun fetchManagementJson(path: String): String? {
        return try {
            val request = Request.Builder().url(baseUrl.removeSuffix("/v1") + path)
                .header("Authorization", "Bearer $apiKey").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: Throwable) { null }
    }

    suspend fun healthCheck(): OmniRouteHealth = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح OmniRoute API من الإعدادات." }
        val request = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Sari-Client", "SariSubtitle-Stage12")
            .get()
            .build()
        val started = System.nanoTime()
        try {
            http.newCall(request).execute().use { response ->
                val latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("OmniRoute (${response.code}): ${raw.take(180)}")
                val count = JSONObject(raw).optJSONArray("data")?.length() ?: 0
                OmniRouteHealth(true, latency, count, null)
            }
        } catch (e: Exception) {
            OmniRouteHealth(false, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), 0, e.message)
        }
    }

    private fun executeTranscription(audioFile: File, model: String, language: String, prompt: String): TranscriptResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply {
                if (language.isNotBlank() && language != "auto") addFormDataPart("language", language)
                if (prompt.isNotBlank()) addFormDataPart("prompt", prompt.take(3_000))
                addFormDataPart("timestamp_granularities[]", "segment")
            }
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Sari-Client", "SariSubtitle-Stage12")
            .header("X-Sari-Task", "live-stt")
            .post(body)
            .build()
        return executeJson(request) { raw -> parseTranscript(raw) }
    }

    private fun parseTranscript(raw: String): TranscriptResult {
        val json = JSONObject(raw)
        val text = json.optString("text").trim()
        if (text.isBlank()) error("OmniRoute لم يُرجع نصاً صوتياً.")
        val segments = mutableListOf<TranscriptSegment>()
        val array = json.optJSONArray("segments")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optDouble("start", Double.NaN)
                val end = item.optDouble("end", Double.NaN)
                val segmentText = item.optString("text").trim()
                if (segmentText.isNotBlank() && start.isFinite() && end.isFinite() && end >= start) {
                    segments += TranscriptSegment(start * 1000.0, end * 1000.0, segmentText)
                }
            }
        }
        return TranscriptResult(text, segments)
    }

    private fun buildSystemPrompt(source: String, target: String, context: TranslationContext, quality: TranslationQuality): String = buildString {
        append("You are a professional live subtitle translator. Translate dialogue from ")
        append(if (source.isBlank() || source == "auto") "the detected source language" else source)
        append(" to ").append(target).append(".\n")
        append("Preserve meaning, intent, names, terminology, tone and emotion. Write natural spoken subtitles, not word-for-word machine translation. Keep it concise and return only the translation.\n")
        if (quality == TranslationQuality.HUMAN) append("Use an editorial-quality final pass: fix pronouns, tense, omissions, additions, awkward Arabic phrasing and terminology drift.\n")
        val glossary = context.glossary.entries.joinToString(", ") { "${it.key}=${it.value}" }
        if (glossary.isNotBlank()) append("Mandatory glossary: ").append(glossary).append(".\n")
        val recent = context.previousSegments.takeLast(4).joinToString(" | ")
        if (recent.isNotBlank()) append("Recent dialogue for context only: ").append(recent).append("\n")
    }.trim()

    private fun <T> executeJson(request: Request, parse: (String) -> T): T {
        val started = System.nanoTime()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            lastResponseMeta = meta(response, null).copy(latencyMs = elapsed)
            if (!response.isSuccessful) {
                lastResponseMeta = meta(response, raw.take(220)).copy(latencyMs = elapsed)
                error("OmniRoute (${response.code}): ${raw.take(220)}")
            }
            return parse(raw)
        }
    }

    private fun meta(response: Response, error: String?): OmniResponseMeta {
        fun longHeader(vararg names: String): Long? = names.firstNotNullOfOrNull { response.header(it)?.toLongOrNull() }
        fun doubleHeader(vararg names: String): Double? = names.firstNotNullOfOrNull { response.header(it)?.toDoubleOrNull() }
        val remaining = longHeader("X-RateLimit-Remaining", "x-ratelimit-remaining", "RateLimit-Remaining")
        val reset = longHeader("X-RateLimit-Reset", "x-ratelimit-reset", "RateLimit-Reset")?.let { if (it < 10_000_000_000L) it * 1000L else it }
        val quotaRemaining = longHeader("X-Quota-Remaining", "x-quota-remaining", "X-Provider-Quota-Remaining")
        val quotaLimit = longHeader("X-Quota-Limit", "x-quota-limit", "X-Provider-Quota-Limit")
        return OmniResponseMeta(
            rateLimitRemaining = remaining,
            rateLimitResetMs = reset,
            quotaRemaining = quotaRemaining,
            quotaLimit = quotaLimit,
            statusCode = response.code,
            error = error,
            providerId = response.header("X-OmniRoute-Provider"),
            effectiveModel = response.header("X-OmniRoute-Model"),
            responseCostUsd = doubleHeader("X-OmniRoute-Response-Cost"),
            fallbackAttempts = response.header("X-OmniRoute-Fallback-Attempts")?.toIntOrNull() ?: 0,
            requestId = response.header("X-OmniRoute-Request-Id") ?: response.header("X-Request-Id"),
            decisionTrace = response.header("X-OmniRoute-Decision")
        )
    }

    suspend fun translateStreaming(text: String, source: String, target: String, model: String, context: TranslationContext, quality: TranslationQuality): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح OmniRoute API من الإعدادات." }
        val system = buildSystemPrompt(source, target, context, quality)
        val payload = JSONObject()
            .put("model", model.trim())
            .put("temperature", 0.0)
            .put("stream", true)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", text)))
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Sari-Client", "SariSubtitle-Stage12")
            .header("X-Sari-Streaming", "true")
            .header("X-OmniRoute-Progress", "true")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val requestStarted = System.nanoTime()
        val streamingHttp = http.newBuilder()
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        streamingHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                lastResponseMeta = meta(response, raw.take(220))
                error("OmniRoute (${response.code}): ${raw.take(220)}")
            }
            val body = response.body ?: error("OmniRoute streaming body is empty.")
            val reader = body.charStream().buffered()
            val out = StringBuilder()
            var firstTokenMs = 0L
            var previousTokenNs = requestStarted
            var maxGapMs = 0L
            var tokenEvents = 0
            var streamCompleted = false
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val payloadLine = line.removePrefix("data:").trim()
                    if (payloadLine.isBlank() || payloadLine == "[DONE]") continue
                    try {
                        val delta = JSONObject(payloadLine)
                            .optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content").orEmpty()
                        if (delta.isNotEmpty()) {
                            val now = System.nanoTime()
                            if (firstTokenMs == 0L) firstTokenMs = TimeUnit.NANOSECONDS.toMillis(now - requestStarted)
                            if (tokenEvents > 0) maxGapMs = max(maxGapMs, TimeUnit.NANOSECONDS.toMillis(now - previousTokenNs))
                            previousTokenNs = now
                            tokenEvents++
                            out.append(delta)
                        }
                    } catch (_: Exception) { }
                }
            } catch (e: java.net.SocketTimeoutException) {
                val partial = out.toString().trim()
                lastResponseMeta = meta(response, "stream_stall_timeout").copy(
                    firstTokenLatencyMs = firstTokenMs,
                    maxInterTokenGapMs = maxGapMs,
                    streamedTokenEvents = tokenEvents,
                    streamCompleted = false,
                    latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStarted)
                )
                error("OmniRoute streaming stalled after ${maxGapMs}ms; partial=${partial.length}")
            }
            streamCompleted = true
            val result = out.toString().trim()
            lastResponseMeta = meta(response, null).copy(
                firstTokenLatencyMs = firstTokenMs,
                maxInterTokenGapMs = maxGapMs,
                streamedTokenEvents = tokenEvents,
                streamCompleted = streamCompleted,
                latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStarted)
            )
            if (result.isBlank()) error("OmniRoute أعاد streaming فارغاً.")
            result
        }
    }

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

data class OmniMonitoringSnapshot(
    val healthJson: String?, val rateLimitsJson: String?, val budgetJson: String? = null,
    val telemetryJson: String? = null, val availabilityJson: String? = null, val resilienceJson: String? = null
)

data class OmniResponseMeta(
    val rateLimitRemaining: Long?, val rateLimitResetMs: Long?, val quotaRemaining: Long?, val quotaLimit: Long?,
    val statusCode: Int?, val error: String?,
    val firstTokenLatencyMs: Long = 0L, val maxInterTokenGapMs: Long = 0L, val streamedTokenEvents: Int = 0,
    val providerId: String? = null, val effectiveModel: String? = null, val responseCostUsd: Double? = null,
    val fallbackAttempts: Int = 0, val requestId: String? = null, val streamCompleted: Boolean = false, val latencyMs: Long = 0L, val decisionTrace: String? = null
)

data class OmniRouteHealth(
    val reachable: Boolean,
    val latencyMs: Long,
    val modelCount: Int,
    val error: String?
)

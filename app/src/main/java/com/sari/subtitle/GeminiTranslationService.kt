package com.sari.subtitle

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** ترجمة نصوص SRT عبر Gemini؛ يرسل النص فقط ولا يرسل الصوت أو توقيتات الفيديو. */
object GeminiTranslationService {
    private const val MAX_BATCH = 8
    // Explicit text-generation models only; never auto-discover unrelated TTS models.
    private val preferredModels = listOf("gemini-3.1-flash-lite", "gemini-3-flash-preview")

    fun translateBatch(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        context: List<String> = emptyList()
    ): List<String> {
        require(apiKey.isNotBlank()) { "أدخل مفتاح Gemini من إعدادات التطبيق." }
        require(texts.isNotEmpty() && texts.size <= MAX_BATCH) { "حجم دفعة Gemini غير صالح." }
        val input = JSONArray()
        texts.forEach { input.put(it) }
        val prompt = """
            You are a professional subtitle translator. Translate every string in the JSON array below
            from language code '$sourceLang' to language code '$targetLang'. Preserve meaning, names,
            tone, and subtitle brevity. Do not add explanations, numbering, markdown, or extra text.
            Return ONLY a JSON object with exactly one property named "translations", whose value
            is an array of strings in the same order and exactly the same length as the input array.
            Do not merge items. Use the optional recent conversation context only to resolve
            pronouns, names, omitted subjects, and terminology. Never translate or repeat the
            context itself; return only the translations for the current input array.
            Recent context (may be empty):
            ${JSONArray(context.take(3))}
            Input JSON array:
            ${input}
        """.trimIndent()

        val request = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json")
                .put("maxOutputTokens", (texts.sumOf { it.length } * 3 + 256).coerceIn(512, 8192)))

        val models = preferredModels
        val failures = mutableListOf<String>()
        for (model in models) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
            try {
                return requestModel(model, request.toString(), apiKey, texts.size)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                val detail = (e.message ?: e.javaClass.simpleName).take(180)
                failures += "$model: $detail"
                // Any failed attempt advances immediately to the configured backup model.
                // Cancellation is handled above and is never swallowed.
            }
        }
        throw IllegalStateException("فشلت نماذج Gemini المتاحة: ${failures.joinToString(" | ")}")
    }

    private fun requestModel(model: String, payload: String, apiKey: String, expectedCount: Int): List<String> {
        val connection = NetworkRequestRegistry.register(URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 45000; doOutput = true
            setRequestProperty("x-goog-api-key", apiKey.trim())
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("Gemini HTTP $code${detail?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            }
            val candidates = JSONObject(body).optJSONArray("candidates") ?: throw IllegalStateException("Gemini لم يُرجع نتائج")
            val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: throw IllegalStateException("Gemini لم يُرجع محتوى قابلًا للترجمة")
            val parts = content.optJSONArray("parts") ?: throw IllegalStateException("رد Gemini فارغ")
            val responseText = buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text", "").orEmpty()) }.trim()
            val cleaned = responseText.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val root = JSONObject(cleaned)
            val translations = root.optJSONArray("translations") ?: throw IllegalStateException("صيغة رد Gemini غير صالحة")
            if (translations.length() != expectedCount) throw IllegalStateException("Gemini أعاد عدد ترجمات غير مطابق للنصوص")
            val result = (0 until translations.length()).map { i ->
                if (translations.isNull(i) || translations.opt(i) !is String) throw IllegalStateException("عنصر ترجمة Gemini ليس نصًا")
                translations.optString(i).trim()
            }
            if (result.any { it.isBlank() }) throw IllegalStateException("Gemini أعاد ترجمة فارغة")
            return result
        } finally { connection.disconnect(); NetworkRequestRegistry.unregister(connection) }
    }

}

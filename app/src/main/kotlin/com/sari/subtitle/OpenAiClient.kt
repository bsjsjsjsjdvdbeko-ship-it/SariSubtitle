package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OpenAI-compatible text translation client. The key is never logged. */
class OpenAiClient(
    private val apiKey: String,
    private val http: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.openai.com/v1"
) {
    suspend fun translate(text: String, source: String, target: String, context: TranslationContext, quality: TranslationQuality): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح OpenAI API في الإعدادات." }
        val model = if (quality == TranslationQuality.HUMAN) "gpt-4o" else "gpt-4o-mini"
        val system = buildString {
            append("Translate subtitle dialogue from ")
            append(if (source.isBlank() || source == "auto") "the detected source language" else source)
            append(" to ").append(target).append(". Preserve meaning, names, terminology, tone and numbers. Return only the translation.\n")
            context.glossary.entries.forEach { (k,v) -> append("Glossary: ").append(k).append(" = ").append(v).append("\n") }
            context.previousTranslations.takeLast(4).forEach { append("Previous: ").append(it.source).append(" => ").append(it.translation).append("\n") }
        }
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", text)))
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OpenAI HTTP ${response.code}: ${raw.take(180)}")
            val result = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (result.isBlank()) error("OpenAI أعاد نص ترجمة فارغاً.")
            result
        }
    }
    companion object {
        private fun defaultClient() = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    }
}

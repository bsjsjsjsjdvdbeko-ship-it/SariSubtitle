package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Translation client with a quality-oriented two-pass Groq mode. */
class TranslationClient(
    private val provider: TranslationProvider,
    private val apiKey: String,
    private val email: String = "",
    private val http: OkHttpClient = WhisperClient.defaultClient()
) {
    suspend fun translate(text: String, source: String, target: String): String =
        translateWithContext(text, source, target, TranslationContext(), TranslationQuality.BALANCED)

    suspend fun translateWithContext(
        text: String,
        source: String,
        target: String,
        context: TranslationContext,
        quality: TranslationQuality = TranslationQuality.HUMAN
    ): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "لا يوجد نص لترجمته." }
        require(target.isNotBlank()) { "حدد لغة الترجمة الهدف." }

        // Live translation is latency-sensitive. For the highest quality mode we use the
        // stronger current Groq production model in one editorial pass instead of two serial
        // requests; this improves quality without creating a second network round-trip.
        singlePass(text, source, target, context, quality)
    }

    private suspend fun singlePass(text: String, source: String, target: String, context: TranslationContext, quality: TranslationQuality): String {
        if (provider == TranslationProvider.OPENAI) {
            require(apiKey.isNotBlank()) { "أدخل مفتاح OpenAI API في الإعدادات." }
            return OpenAiClient(apiKey, http).translate(text, source, target, context, quality)
        }
        val request = when (provider) {
            TranslationProvider.GROQ -> {
                require(apiKey.isNotBlank()) { "أدخل مفتاح Groq API في الإعدادات." }
                val system = buildGroqSystemPrompt(source, target, context)
                val payload = JSONObject()
                    .put("model", if (quality == TranslationQuality.HUMAN) "openai/gpt-oss-120b" else "openai/gpt-oss-20b")
                    .put("temperature", 0.0)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", text)))
                    .toString()
                Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
            TranslationProvider.GOOGLE -> {
                require(apiKey.isNotBlank()) { "مفتاح Google Cloud Translation غير موجود." }
                val payload = JSONObject().put("q", text).put("target", target)
                    .apply { if (source != "auto") put("source", source) }.toString()
                Request.Builder()
                    .url("https://translation.googleapis.com/language/translate/v2?key=${URLEncoder.encode(apiKey, "UTF-8")}")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
            TranslationProvider.MYMEMORY -> {
                val s = if (source == "auto") "en" else source
                val q = URLEncoder.encode(text, "UTF-8")
                val pair = URLEncoder.encode("$s|$target", "UTF-8")
                val e = if (email.isBlank()) "" else "&de=" + URLEncoder.encode(email, "UTF-8")
                Request.Builder().url("https://api.mymemory.translated.net/get?q=$q&langpair=$pair$e").get().build()
            }
        }
        return executeTextRequest(request)
    }

    private fun buildGroqSystemPrompt(source: String, target: String, context: TranslationContext): String {
        return buildString {
            append("You are a professional human subtitle translator. Translate dialogue from ")
            append(if (source.isBlank() || source == "auto") "the detected source language" else source)
            append(" to ").append(target).append(".\n")
            append("Your priority order is: meaning and intent, natural human dialogue, consistency, then literal wording.\n")
            append("Write idiomatic spoken target-language subtitles, not word-for-word machine translation. Preserve names, relationships, emotion, humor, politeness level, and implied meaning. Do not invent facts. Keep the translation concise enough for subtitles without deleting meaningful content. Return only the translation.\n")
            append(appendContext(context))
        }.trim()
    }

    private fun buildReviewPrompt(sourceText: String, draft: String, source: String, target: String, context: TranslationContext): String {
        return buildString {
            append("You are the senior subtitle editor doing the final human-quality pass.\n")
            append("Source language: ").append(if (source.isBlank()) "auto" else source).append("\n")
            append("Target language: ").append(target).append("\n")
            append("Original dialogue:\n").append(sourceText).append("\n\n")
            append("Draft translation:\n").append(draft).append("\n\n")
            append("Edit the draft only when needed. Fix literal/awkward wording, wrong tone, pronouns, names, terminology, tense, omissions, additions, punctuation, and unnatural Arabic phrasing. Keep the speaker's intent and emotional strength. Apply the glossary exactly when applicable. Do not explain changes and do not add notes. If the draft is already correct, return it unchanged. Return ONLY the final subtitle translation.\n")
            append(appendContext(context))
        }.trim()
    }

    private fun appendContext(context: TranslationContext): String {
        val out = StringBuilder()
        val glossary = context.glossary.entries.joinToString("\n") { "- ${it.key} = ${it.value}" }
        val turns = context.previousTranslations.joinToString("\n") {
            if (it.translation.isBlank()) "SRC: ${it.source}" else "SRC: ${it.source}\nTGT: ${it.translation}"
        }
        if (turns.isNotBlank()) out.append("Recent dialogue context (use for meaning and consistency, never copy it into the answer):\n").append(turns).append("\n")
        if (glossary.isNotBlank()) out.append("Mandatory terminology glossary:\n").append(glossary).append("\n")
        if (context.characterNotes.isNotBlank()) out.append("Speaker/name notes:\n").append(context.characterNotes).append("\n")
        return out.toString()
    }

    private suspend fun executeTextRequest(request: Request): String {
        var last: Exception? = null
        repeat(3) { i ->
            try {
                return http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}: ${raw.take(180)}")
                    val result = when (provider) {
                        TranslationProvider.GROQ -> JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                        TranslationProvider.GOOGLE -> JSONObject(raw).getJSONObject("data").getJSONArray("translations")
                            .getJSONObject(0).getString("translatedText").trim()
                        TranslationProvider.MYMEMORY -> {
                            val json = JSONObject(raw)
                            if (json.optString("responseStatus") != "200") error("خدمة MyMemory لم تقبل الطلب: ${json.optString("responseDetails")}")
                            json.getJSONObject("responseData").getString("translatedText").trim()
                        }
                    }
                    if (result.isBlank()) error("خدمة الترجمة أعادت نصاً فارغاً.")
                    result
                }
            } catch (e: Exception) {
                last = e
                if (i < 2) delay(500L * (i + 1))
            }
        }
        throw IllegalStateException("فشلت الترجمة بعد 3 محاولات. تحقق من الإنترنت ومفاتيح الخدمة.", last)
    }
}

data class TranslationContext(
    val previousSegments: List<String> = emptyList(),
    val previousTranslations: List<ContextTurn> = emptyList(),
    val glossary: Map<String, String> = emptyMap(),
    val characterNotes: String = ""
)

enum class TranslationProvider { GROQ, GOOGLE, MYMEMORY, OPENAI, OMNIROUTE }

enum class TranslationQuality { FAST, BALANCED, HUMAN }

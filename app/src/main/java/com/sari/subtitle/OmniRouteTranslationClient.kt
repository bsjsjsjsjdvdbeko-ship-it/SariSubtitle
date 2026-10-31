package com.sari.subtitle

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Text-only OmniRoute gateway client. It never receives audio or SRT timestamps. */
class OmniRouteTranslationClient(private val settings: SubtitleSettings) {
    companion object {
        /** Fixed, user-verified translation model; never fall back to OmniRoute auto routing. */
        const val FIXED_TRANSLATION_MODEL = "xkiro/qwen/qwen3.5-omni-flash:free"
    }

    fun translateBatch(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        context: List<String> = emptyList(),
        modelOverride: String? = null
    ): List<String> {
        require(texts.isNotEmpty() && texts.size <= 16) { "حجم دفعة OmniRoute غير صالح (الحد 16)." }
        val input = JSONArray().apply { texts.forEach { put(it) } }
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
            $input
        """.trimIndent()
        // A deliberate model override is used only for explicit Gemini fallback calls.
        // The primary OmniRoute path is pinned to the user-verified xKiro/Qwen model.
        val selectedModel = modelOverride?.trim()?.takeIf { it.isNotBlank() && !it.equals("auto", true) }
            ?: FIXED_TRANSLATION_MODEL
        val payload = JSONObject()
            .put("model", selectedModel)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are a careful subtitle translator. Output only the requested JSON."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0.2)
        val response = requestJson("/chat/completions", payload.toString(), 45000, 45000)
        val choices = response.optJSONArray("choices") ?: throw IOException("OmniRoute لم يُرجع choices.")
        val content = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")?.trim().orEmpty()
        if (content.isBlank()) throw IOException("OmniRoute أعاد رد ترجمة فارغًا.")
        val arr = parseTranslationsArray(content)
            ?: throw IOException("رد OmniRoute لا يحتوي على JSON صالح لحقل translations؛ لم تُقبل أي نتيجة غير موثقة.")
        if (arr.length() < texts.size) throw IOException("OmniRoute أعاد ${arr.length()} ترجمة بينما المطلوب ${texts.size}؛ لا يمكن إكمال الدفعة بأمان.")
        // Ignore excess model output; only the first N results correspond to original cues.
        val results = (0 until texts.size).map { i ->
            if (arr.isNull(i) || arr.opt(i) !is String) throw IOException("عنصر ترجمة OmniRoute رقم ${i + 1} ليس نصًا.")
            arr.optString(i).trim()
        }
        if (results.any { it.isBlank() }) throw IOException("OmniRoute أعاد ترجمة فارغة.")
        if (targetLang.equals("ar", ignoreCase = true)) {
            val arabic = Regex("[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]")
            val latin = Regex("[A-Za-z]")
            val unchangedForeign = texts.indices.firstOrNull { i ->
                latin.containsMatchIn(texts[i]) && !arabic.containsMatchIn(texts[i]) &&
                    !arabic.containsMatchIn(results[i]) && results[i].equals(texts[i].trim(), ignoreCase = true) &&
                    texts[i].trim().length > 3
            }
            if (unchangedForeign != null) throw IOException("OmniRoute أعاد النص الأجنبي كما هو في المقطع ${unchangedForeign + 1}.")
        }
        return results
    }


    /** Parses a real JSON envelope, tolerating only common surrounding fences/preamble. */
    private fun parseTranslationsArray(raw: String): JSONArray? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val candidates = listOf(cleaned) + run {
            val first = cleaned.indexOf('{')
            val last = cleaned.lastIndexOf('}')
            if (first >= 0 && last > first) listOf(cleaned.substring(first, last + 1)) else emptyList()
        }
        for (candidate in candidates.distinct()) {
            try {
                val root = JSONObject(candidate)
                root.optJSONArray("translations")?.let { return it }
            } catch (_: Exception) { /* try the next strictly JSON candidate */ }
        }
        return null
    }

    fun availableModelIds(): List<String> {
        val response = requestJson("/models", null, 45000, 45000)
        val data = response.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.optString("id")?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    fun findModelId(providerFragment: String): String? = availableModelIds()
        .firstOrNull { it.contains(providerFragment, ignoreCase = true) }

    fun testConnection(): String {
        val count = availableModelIds().size
        return "اتصال OmniRoute ناجح. النماذج الظاهرة: $count"
    }

    private fun requestJson(path: String, payload: String?, connectTimeoutMs: Int, readTimeoutMs: Int): JSONObject {
        val url = endpoint(path)
        val conn = NetworkRequestRegistry.register(URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (payload == null) "GET" else "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            val key = settings.omniRouteApiKey.trim()
            if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (payload != null) OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
            if (code !in 200..299) {
                val detail = try {
                    val root = JSONObject(body)
                    root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: root.optString("message").takeIf { it.isNotBlank() }
                        ?: body.take(220)
                } catch (_: Exception) { body.take(220) }
                throw IOException("OmniRoute HTTP $code${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            return JSONObject(body)
        } finally { conn.disconnect(); NetworkRequestRegistry.unregister(conn) }
    }

    private fun endpoint(path: String): String {
        val base = settings.omniRouteBaseUrl.trim().trimEnd('/')
        require(base.startsWith("http://127.0.0.1:") || base.startsWith("http://localhost:") || base.startsWith("https://")) {
            "عنوان OmniRoute غير صالح. استخدم http://127.0.0.1:20128/v1 لخدمة Termux المحلية."
        }
        return if (base.endsWith("/v1")) base + path else "$base/v1$path"
    }
}

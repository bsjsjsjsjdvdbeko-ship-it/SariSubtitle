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
    fun translateBatch(texts: List<String>, sourceLang: String, targetLang: String): List<String> {
        require(texts.isNotEmpty() && texts.size <= 8) { "حجم دفعة OmniRoute غير صالح." }
        val input = JSONArray().apply { texts.forEach { put(it) } }
        val prompt = """
            You are a professional subtitle translator. Translate every string in the JSON array below
            from language code '$sourceLang' to language code '$targetLang'. Preserve meaning, names,
            tone, and subtitle brevity. Do not add explanations, numbering, markdown, or extra text.
            Return ONLY a JSON object with exactly one property named "translations", whose value
            is an array of strings in the same order and exactly the same length as the input array.
            Do not merge items. Input JSON array:
            $input
        """.trimIndent()
        val payload = JSONObject()
            .put("model", settings.omniRouteTextModel.trim().ifBlank { "auto" })
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are a careful subtitle translator. Output only the requested JSON."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0.2)
        val response = requestJson("/chat/completions", payload.toString(), 45000, 45000)
        val choices = response.optJSONArray("choices") ?: throw IOException("OmniRoute لم يُرجع choices.")
        val content = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")?.trim().orEmpty()
        if (content.isBlank()) throw IOException("OmniRoute أعاد رد ترجمة فارغًا.")
        val clean = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = try { JSONObject(clean).optJSONArray("translations") } catch (_: Exception) { null }
            ?: throw IOException("رد OmniRoute ليس JSON صالحًا لقائمة الترجمات.")
        if (arr.length() != texts.size) throw IOException("OmniRoute أعاد عدد ترجمات غير مطابق للنصوص.")
        val results = (0 until arr.length()).map { arr.optString(it, "").trim() }
        if (results.any { it.isBlank() }) throw IOException("OmniRoute أعاد ترجمة فارغة.")
        return results
    }

    fun testConnection(): String {
        val response = requestJson("/models", null, 45000, 45000)
        val count = response.optJSONArray("data")?.length() ?: 0
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

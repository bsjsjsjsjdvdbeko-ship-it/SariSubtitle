package com.sari.subtitle

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Optional translation backends. Each returns only the translated text; SRT timestamps stay local. */
object AdditionalTranslationProviders {
    fun azure(text: String, source: String, target: String, key: String, region: String): String {
        require(key.isNotBlank()) { "أضف مفتاح Azure Translator في الإعدادات" }
        val query = "api-version=3.0&to=${URLEncoder.encode(target, "UTF-8")}" +
            if (source.equals("auto", true)) "" else "&from=${URLEncoder.encode(source, "UTF-8")}" 
        val conn = NetworkRequestRegistry.register(URL("https://api.cognitive.microsofttranslator.com/translate?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 25000; doOutput = true
            setRequestProperty("Ocp-Apim-Subscription-Key", key.trim())
            setRequestProperty("Ocp-Apim-Subscription-Region", region.trim().ifBlank { "global" })
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val payload = JSONArray().put(JSONObject().put("Text", text)).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Azure Translator HTTP $code")
            val translated = JSONArray(body).optJSONObject(0)?.optJSONArray("translations")?.optJSONObject(0)?.optString("text").orEmpty().trim()
            if (translated.isBlank()) throw IllegalStateException("Azure Translator أعاد نصًا فارغًا")
            translated
        } finally { conn.disconnect(); NetworkRequestRegistry.unregister(conn) }
    }

    fun huggingFace(text: String, source: String, target: String, token: String): String {
        require(token.isNotBlank()) { "أضف رمز Hugging Face في الإعدادات" }
        val src = source.lowercase().take(2); val dst = target.lowercase().take(2)
        require(src.matches(Regex("[a-z]{2}")) && dst.matches(Regex("[a-z]{2}"))) { "لغة المصدر/الهدف غير مدعومة لنموذج Hugging Face" }
        val model = "Helsinki-NLP/opus-mt-$src-$dst"
        val conn = NetworkRequestRegistry.register(URL("https://router.huggingface.co/hf-inference/models/$model").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 40000; doOutput = true
            setRequestProperty("Authorization", "Bearer ${token.trim()}")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val payload = JSONObject().put("inputs", text).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Hugging Face HTTP $code: ${body.take(180)}")
            val arr = JSONArray(body)
            val translated = arr.optJSONObject(0)?.optString("translation_text").orEmpty().trim()
            if (translated.isBlank()) throw IllegalStateException("Hugging Face لم يُرجع ترجمة")
            translated
        } finally { conn.disconnect(); NetworkRequestRegistry.unregister(conn) }
    }
}

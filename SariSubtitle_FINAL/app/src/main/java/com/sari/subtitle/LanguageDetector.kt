package com.sari.subtitle

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * يكتشف لغة النص تلقائياً عبر Google Translate API.
 */
object LanguageDetector {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * يكتشف لغة النص.
     * @return رمز اللغة (مثل "ru"، "en"، "ar")، أو null عند الفشل.
     */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            val sample = text.take(200).replace("\n", " ").replace("\r", " ")
            val encoded = URLEncoder.encode(sample, "UTF-8")
            val urlStr = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=en&dt=t&q=$encoded"
            connection = NetworkRequestRegistry.register(URL(urlStr).openConnection() as HttpURLConnection)
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            if (connection.responseCode !in 200..299) return null
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseLanguage(response)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
            connection?.let { NetworkRequestRegistry.unregister(it) }
        }
    }

    /**
     * يستخرج رمز اللغة من استجابة Google Translate.
     * البنية: [[[...]], ..., "ru", ...]
     */
    private fun parseLanguage(response: String): String? {
        return try {
            val arr = JSONArray(response)
            // العنصر index 2 عادة يحتوي رمز اللغة
            if (arr.length() > 2) {
                val lang = arr.optString(2, "")
                if (lang.isNotBlank() && lang.length in 2..5) return lang
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

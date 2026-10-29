package com.sari.subtitle

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DeepLTranslationService {

    private val timestampPattern = Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->")
    private val arabicRegex = Regex("[\\u0600-\\u06FF]")
    private val cyrillicRegex = Regex("[\\u0400-\\u04FF]")

    /** ترجمة نص واحد: DeepL عند وجود مفتاح، ثم Google Translate ثم MyMemory. */
    fun translateTextOnly(text: String, sourceLang: String, targetLang: String, apiKey: String = ""): String {
        if (text.isBlank()) return text
        if (apiKey.isNotBlank()) {
            val deepL = tryDeepL(text, sourceLang, targetLang, apiKey).trim()
            if (isUsableTranslation(text, deepL, sourceLang, targetLang)) return deepL
        }
        val google = tryGoogleTranslate(text, targetLang).trim()
        if (isUsableTranslation(text, google, sourceLang, targetLang)) return google
        val memory = tryMyMemory(text, sourceLang, targetLang).trim()
        if (isUsableTranslation(text, memory, sourceLang, targetLang)) return android.text.Html.fromHtml(memory, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        throw IllegalStateException("تعذرت الترجمة عبر خدمات الترجمة المتاحة؛ تحقق من الاتصال وحدود الخدمات.")
    }

    private fun isUsableTranslation(original: String, translated: String, sourceLang: String, targetLang: String): Boolean {
        val result = translated.trim()
        if (result.isBlank() || result.contains("???")) return false
        if (!sourceLang.equals(targetLang, ignoreCase = true) && result.equals(original.trim(), ignoreCase = true)) return false
        if (targetLang.equals("ar", ignoreCase = true) || targetLang.startsWith("ar-", ignoreCase = true)) {
            val originalHasLatinWords = Regex("[A-Za-z]{3,}").containsMatchIn(original)
            val hasArabic = Regex("[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]").containsMatchIn(result)
            if (originalHasLatinWords && !hasArabic) return false
        }
        return true
    }

    /**
     * ترجمة موثوقة متعددة المزودين:
     * 1. DeepL (إذا مفتاح موجود)
     * 2. Google Translate (مجاني)
     * 3. MyMemory (احتياطي أخير)
     */
    fun translateSrt(
        source: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\\n[ \\t]*\\n"))

        require(blocks.isNotEmpty() && blocks.any { timestampPattern.containsMatchIn(it) }) {
            "الملف لا يبدو بصيغة SRT صحيحة"
        }

        val translatable = blocks.count { block ->
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            ti >= 0 && lines.drop(ti + 1).any { it.isNotBlank() }
        }

        var completed = 0
        val output = ArrayList<String>(blocks.size)

        for (block in blocks) {
            val lines = block.split('\n').toMutableList()
            val timeIndex = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }

            if (timeIndex < 0 || lines.drop(timeIndex + 1).all { it.isBlank() }) {
                output.add(block)
                continue
            }

            val originalText = lines.drop(timeIndex + 1).joinToString("\n").trim()
            // Never silently publish the source-language cue as the target-language subtitle.
            // translateTextOnly tries DeepL, Google Translate and MyMemory, then throws on failure.
            val translated = translateTextOnly(originalText, sourceLang, targetLang, apiKey)
            if (!isUsableTranslation(originalText, translated, sourceLang, targetLang)) {
                throw IllegalStateException("فشلت ترجمة المقطع رقم ${completed + 1} إلى $targetLang؛ لم يُعرض النص الأصلي كترجمة.")
            }

            while (lines.size > timeIndex + 1) lines.removeAt(lines.lastIndex)
            lines.add(translated)
            output.add(lines.joinToString("\n"))
            completed++
            onProgress(completed, translatable)
        }
        return output.joinToString("\n\n") + "\n"
    }

    /**
     * DeepL — الأساسي (جودة عالية للأوروبية)
     */
    private fun tryDeepL(text: String, sourceLang: String, targetLang: String, apiKey: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api-free.deepl.com/v2/translate")
            connection = NetworkRequestRegistry.register(url.openConnection() as HttpURLConnection)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val source = sourceLang.uppercase().take(2)
            val target = targetLang.uppercase().take(2)
            val postData = "text=${URLEncoder.encode(text, "UTF-8")}" +
                "&source_lang=$source" +
                "&target_lang=$target"

            OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(postData) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) return ""

            val json = JSONObject(response)
            val translations = json.optJSONArray("translations") ?: return ""
            translations.optJSONObject(0)?.optString("text", "") ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            connection?.disconnect()
            connection?.let { NetworkRequestRegistry.unregister(it) }
        }
    }

    /**
     * Google Translate — مجاني (يدعم كل اللغات)
     */
    private fun tryGoogleTranslate(text: String, targetLang: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded")
            connection = NetworkRequestRegistry.register(url.openConnection() as HttpURLConnection)
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return ""

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(response)
            val sentences = arr.optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val item = sentences.optJSONArray(i) ?: continue
                val translated = item.optString(0, "")
                if (translated.isNotBlank()) sb.append(translated)
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        } finally {
            connection?.disconnect()
            connection?.let { NetworkRequestRegistry.unregister(it) }
        }
    }

    /**
     * MyMemory — احتياطي (بدون مفتاح)
     */
    private fun tryMyMemory(text: String, sourceLang: String, targetLang: String): String {
        if (text.length > 450) return "" // حد MyMemory
        var connection: HttpURLConnection? = null
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val pair = URLEncoder.encode("$sourceLang|$targetLang", "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$q&langpair=$pair")
            connection = NetworkRequestRegistry.register(url.openConnection() as HttpURLConnection)
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return ""
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(response)
            if (json.optInt("responseStatus", 200) != 200) return ""
            json.optJSONObject("responseData")?.optString("translatedText", "") ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            connection?.disconnect()
            connection?.let { NetworkRequestRegistry.unregister(it) }
        }
    }

}

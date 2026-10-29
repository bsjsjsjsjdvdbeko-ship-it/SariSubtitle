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
            if (isUsableTranslation(text, deepL)) return deepL
        }
        val google = tryGoogleTranslate(text, targetLang).trim()
        if (isUsableTranslation(text, google)) return google
        val memory = tryMyMemory(text, sourceLang, targetLang).trim()
        if (isUsableTranslation(text, memory)) return android.text.Html.fromHtml(memory, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        throw IllegalStateException("تعذرت الترجمة عبر خدمات الترجمة المتاحة؛ تحقق من الاتصال وحدود الخدمات.")
    }

    private fun isUsableTranslation(original: String, translated: String): Boolean {
        if (translated.isBlank() || translated.contains("???")) return false
        // This method is used only after a cue was routed for translation; do not accept an unchanged foreign sentence as success.
        val latin = Regex("[A-Za-z]")
        val arabic = Regex("[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]")
        if (original.trim().length > 3 && original.trim().equals(translated.trim(), ignoreCase = true) &&
            latin.containsMatchIn(original) && !arabic.containsMatchIn(original)) return false
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
        val blocks = normalized.trim().split(Regex("\n[ \t]*\n"))
        require(blocks.isNotEmpty() && blocks.any { timestampPattern.containsMatchIn(it) }) { "الملف لا يبدو بصيغة SRT صحيحة" }
        data class Cue(val blockIndex: Int, val lines: MutableList<String>, val timeIndex: Int, val text: String)
        val cues = blocks.mapIndexedNotNull { index, block ->
            val lines = block.split('\n').toMutableList()
            val timeIndex = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            if (timeIndex < 0 || lines.drop(timeIndex + 1).all { it.isBlank() }) null
            else Cue(index, lines, timeIndex, lines.drop(timeIndex + 1).joinToString("\n").trim())
        }
        val output = blocks.toMutableList()
        var completed = 0
        for (batch in cues.chunked(8)) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
            val deeplResults = if (apiKey.isNotBlank()) tryDeepLBatch(batch.map { it.text }, sourceLang, targetLang, apiKey) else null
            for ((i, cue) in batch.withIndex()) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                val deepl = deeplResults?.getOrNull(i).orEmpty().trim()
                var translated = deepl.takeIf { isUsableTranslation(cue.text, it) && it != cue.text }.orEmpty()
                if (translated.isBlank()) {
                    val google = tryGoogleTranslate(cue.text, targetLang).trim()
                    translated = google.takeIf { isUsableTranslation(cue.text, it) && it != cue.text }.orEmpty()
                }
                if (translated.isBlank()) {
                    val memory = tryMyMemory(cue.text, sourceLang, targetLang).trim()
                    translated = memory.takeIf { isUsableTranslation(cue.text, it) && it != cue.text }
                        ?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }.orEmpty()
                }
                if (translated.isBlank()) throw IllegalStateException("تعذرت ترجمة المقطع ${cue.blockIndex + 1} عبر DeepL وGoogle وMyMemory")
                while (cue.lines.size > cue.timeIndex + 1) cue.lines.removeAt(cue.lines.lastIndex)
                cue.lines.add(translated)
                output[cue.blockIndex] = cue.lines.joinToString("\n")
                completed++
                onProgress(completed, cues.size)
            }
        }
        return output.joinToString("\n\n") + "\n"
    }

    /** Send up to eight text parameters in one DeepL request; response order matches request order. */
    private fun tryDeepLBatch(texts: List<String>, sourceLang: String, targetLang: String, apiKey: String): List<String>? {
        if (texts.isEmpty()) return emptyList()
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api-free.deepl.com/v2/translate")
            connection = NetworkRequestRegistry.register(url.openConnection() as HttpURLConnection)
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val source = sourceLang.uppercase().take(2)
            val target = targetLang.uppercase().take(2)
            val params = texts.joinToString("&") { "text=${URLEncoder.encode(it, "UTF-8")}" } +
                "&source_lang=$source&target_lang=$target"
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(params) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return null
            val arr = JSONObject(body).optJSONArray("translations") ?: return null
            if (arr.length() != texts.size) return null
            (0 until arr.length()).map { arr.optJSONObject(it)?.optString("text", "").orEmpty() }.takeIf { values -> values.all { it.isNotBlank() } }
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
            null
        } finally {
            connection?.disconnect(); connection?.let { NetworkRequestRegistry.unregister(it) }
        }
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
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
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
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
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
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
            ""
        } finally {
            connection?.disconnect()
            connection?.let { NetworkRequestRegistry.unregister(it) }
        }
    }

}

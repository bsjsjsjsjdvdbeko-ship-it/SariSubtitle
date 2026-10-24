package com.sari.subtitle.v2

import android.text.Html
import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ترجمة SRT من داخل التطبيق.
 * - ترجمة تلقائية: يكتشف اللغة ثم يترجم إلى العربية.
 * - أو ترجمة يدوية: المستخدم يحدد اللغات.
 * - MyMemory API للترجمة (مجاني).
 * - لا يتم إرسال توقيتات SRT إلى الخدمة.
 */
object SrtTranslationService {

    private val timestampPattern = Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->")

    /**
     * ترجمة تلقائية: يكتشف اللغة من أول نص، ثم يترجم إلى الهدف.
     * @param onDetected callback يُستدعى باللغة المكتشفة
     */
    fun translateAuto(
        source: String,
        targetLang: String = "ar",
        onDetected: (String) -> Unit = {},
        context: Context? = null,
        shouldCancel: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\n[ \t]*\n"))
        require(blocks.any { timestampPattern.containsMatchIn(it) }) { "الملف لا يحتوي على مقاطع SRT صالحة" }
        val target = targetLang.lowercase().substringBefore('-')
        val arabicScript = Regex("[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]")
        val latinScript = Regex("[A-Za-z]")
        val grouped = linkedMapOf<String, MutableList<Int>>()
        val originalTexts = mutableListOf<String?>()
        val detectedLanguages = linkedSetOf<String>()
        for ((index, block) in blocks.withIndex()) {
            if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            if (ti < 0 || lines.drop(ti + 1).all { it.isBlank() }) { originalTexts.add(null); continue }
            val text = lines.drop(ti + 1).joinToString("\n").trim()
            originalTexts.add(text)
            val hasArabic = arabicScript.containsMatchIn(text)
            val hasLatin = latinScript.containsMatchIn(text)
            // Avoid translating cues already written in the target script/language.
            if (target == "ar" && hasArabic && !hasLatin) continue
            if (target == "en" && hasLatin && !hasArabic) continue
            var lang = if (hasArabic && !hasLatin) "ar" else null
            if (lang == null && text.length <= 3 && !hasArabic && !hasLatin) continue
            if (lang == null) lang = LanguageDetector.detect(text.take(180))
            if (lang.isNullOrBlank()) lang = if (hasArabic) "ar" else "en"
            lang = lang.lowercase().substringBefore('-')
            // A mixed Arabic/Latin cue may contain an untranslated foreign phrase; translate the cue rather than silently skipping it.
            if (target == "ar" && hasArabic && hasLatin && lang == "ar") lang = "en"
            detectedLanguages.add(lang)
            if (lang == target || (target == "ar" && hasArabic && !hasLatin)) continue
            grouped.getOrPut(lang) { mutableListOf() }.add(index)
        }
        if (grouped.isEmpty()) {
            onDetected(target)
            onProgress(1, 1)
            return source
        }
        if (detectedLanguages.isEmpty()) {
            val first = originalTexts.firstOrNull { !it.isNullOrBlank() }.orEmpty()
            val detected = LanguageDetector.detect(first) ?: "unknown"
            onDetected(detected)
            if (detected == target) return source
            throw IllegalArgumentException("تعذر تحديد لغة المقاطع الأجنبية")
        }
        onDetected(if (detectedLanguages.size == 1) detectedLanguages.first() else "مختلطة: ${detectedLanguages.joinToString(", ")}")
        if (grouped.isEmpty()) { onProgress(1, 1); return source }
        val output = blocks.toMutableList()
        val totalForeign = grouped.values.sumOf { it.size }
        var finished = 0
        for ((language, indexes) in grouped) {
            if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
            val subset = indexes.map { blocks[it] }.joinToString("\r\n\r\n") + "\r\n"
            val translatedSubset = translateSrt(
                source = subset,
                sourceLang = language,
                targetLang = targetLang,
                context = context,
                shouldCancel = shouldCancel,
                onProgress = { done, _ -> onProgress((finished + done).coerceAtMost(totalForeign), totalForeign) }
            )
            val translatedBlocks = translatedSubset.trim().split(Regex("\n[ \t]*\n"))
            if (translatedBlocks.size != indexes.size) throw IllegalStateException("تغيّر عدد مقاطع SRT أثناء الترجمة")
            for ((localIndex, originalIndex) in indexes.withIndex()) output[originalIndex] = translatedBlocks[localIndex]
            finished += indexes.size
            onProgress(finished.coerceAtMost(totalForeign), totalForeign)
        }
        if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
        return output.joinToString("\r\n\r\n") + "\r\n"
    }

    fun translateSrt(
        source: String,
        sourceLang: String,
        targetLang: String,
        context: Context? = null,
        shouldCancel: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        require(sourceLang.isNotBlank() && targetLang.isNotBlank()) {
            "اختر لغة المصدر واللغة المستهدفة"
        }
        val normalized = source.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\\n[ \\t]*\\n"))

        require(blocks.isNotEmpty() && blocks.any { timestampPattern.containsMatchIn(it) }) {
            "الملف لا يبدو بصيغة SRT صحيحة"
        }

        // Keep every original block/timestamp intact; only replace the text lines.
        data class CueWork(val blockIndex: Int, val lines: MutableList<String>, val timeIndex: Int,
                           val text: String, val cacheKey: String)
        val memoryCache = mutableMapOf<String, String>()
        val output = blocks.toMutableList()
        val pending = mutableListOf<CueWork>()
        val total = blocks.count { block ->
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            ti >= 0 && lines.drop(ti + 1).any { it.isNotBlank() }
        }
        var completed = 0
        val settings = context?.let { SubtitleSettings(it) }
        val geminiKey = settings?.geminiApiKey.orEmpty()
        var geminiAvailable = geminiKey.isNotBlank()
        var omniRouteAvailable = settings?.omniRouteEnabled == true || settings?.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE

        for ((index, block) in blocks.withIndex()) {
            if (shouldCancel()) throw java.util.concurrent.CancellationException("ألغى المستخدم العملية")
            val lines = block.split('\n').toMutableList()
            val timeIndex = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            if (timeIndex < 0 || lines.drop(timeIndex + 1).all { it.isBlank() }) continue
            val text = lines.drop(timeIndex + 1).joinToString("\n").trim()
            val key = cacheKey(text, sourceLang, targetLang)
            val cached = memoryCache[text] ?: readPersistentCache(context, key)
            if (!cached.isNullOrBlank()) {
                while (lines.size > timeIndex + 1) lines.removeAt(lines.lastIndex)
                lines.add(cached)
                output[index] = lines.joinToString("\n")
                memoryCache[text] = cached
                completed++
                onProgress(completed, total)
            } else {
                pending.add(CueWork(index, lines, timeIndex, text, key))
            }
        }

        // Gemini receives up to eight subtitle texts per request to reduce API calls on long files.
        for (batch in pending.chunked(8)) {
            if (shouldCancel()) throw java.util.concurrent.CancellationException("ألغى المستخدم العملية")
            val batchTexts = batch.map { it.text }
            var batchResults: List<String>? = null
            val omniFirst = settings?.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE
            fun tryOmniRoute(): List<String>? {
                if (!omniRouteAvailable || settings == null) return null
                if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                return try {
                    OmniRouteTranslationClient(settings).translateBatch(batchTexts, sourceLang, targetLang)
                } catch (e: Exception) {
                    if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                    // Disable this backend for the rest of this file after a failure; avoid retry storms.
                    omniRouteAvailable = false
                    null
                }
            }
            fun tryGemini(): List<String>? {
                if (!geminiAvailable) return null
                if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                return try {
                    GeminiTranslationService.translateBatch(batchTexts, sourceLang, targetLang, geminiKey)
                } catch (e: Exception) {
                    if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                    // Avoid repeatedly hitting an exhausted/invalid Gemini quota for every batch.
                    geminiAvailable = false
                    null
                }
            }
            if (omniFirst) {
                batchResults = tryOmniRoute() ?: tryGemini()
            } else {
                batchResults = tryGemini() ?: tryOmniRoute()
            }

            for ((batchIndex, cue) in batch.withIndex()) {
                if (shouldCancel()) throw java.util.concurrent.CancellationException("ألغى المستخدم العملية")
                val translated = batchResults?.getOrNull(batchIndex)?.takeIf { it.isNotBlank() }
                    ?: translateText(cue.text, sourceLang, targetLang, settings, shouldCancel)
                while (cue.lines.size > cue.timeIndex + 1) cue.lines.removeAt(cue.lines.lastIndex)
                cue.lines.add(translated)
                output[cue.blockIndex] = cue.lines.joinToString("\n")
                memoryCache[cue.text] = translated
                writePersistentCache(context, cue.cacheKey, translated)
                completed++
                onProgress(completed, total)
            }
        }
        return output.joinToString("\r\n\r\n") + "\r\n"
    }

    /**
     * يستخرج أول نص من SRT (للكشف).
     */
    private fun extractFirstText(source: String): String? {
        val normalized = source.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\\n[ \\t]*\\n"))
        for (block in blocks) {
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            if (ti >= 0) {
                val text = lines.drop(ti + 1).joinToString(" ").trim()
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    private fun cacheKey(text: String, sourceLang: String, targetLang: String): String {
        val raw = "$sourceLang|$targetLang|$text".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readPersistentCache(context: Context?, key: String): String? {
        if (context == null) return null
        return try {
            context.getSharedPreferences("srt_translation_cache_v2", Context.MODE_PRIVATE)
                .getString(key, null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun writePersistentCache(context: Context?, key: String, value: String) {
        if (context == null || value.isBlank()) return
        try {
            context.getSharedPreferences("srt_translation_cache_v2", Context.MODE_PRIVATE)
                .edit().putString(key, value).apply()
        } catch (_: Exception) { /* Cache failures must not fail translation. */ }
    }

    private fun translateText(text: String, sourceLang: String, targetLang: String, settings: SubtitleSettings?, shouldCancel: () -> Boolean): String {
        if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
        if (text.isBlank()) return text
        if (text.length > 450) throw IllegalArgumentException("أحد مقاطع الترجمة طويل جداً؛ قصّره أو قسّمه")
        val failures = mutableListOf<String>()
        if (settings != null && settings.azureTranslatorKey.isNotBlank()) {
            try { return AdditionalTranslationProviders.azure(text, sourceLang, targetLang, settings.azureTranslatorKey, settings.azureTranslatorRegion) }
            catch (e: Exception) { if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية"); failures += "Azure: ${e.message.orEmpty().take(100)}" }
        }
        if (settings != null && settings.huggingFaceToken.isNotBlank()) {
            try { return AdditionalTranslationProviders.huggingFace(text, sourceLang, targetLang, settings.huggingFaceToken) }
            catch (e: Exception) { if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية"); failures += "Hugging Face: ${e.message.orEmpty().take(100)}" }
        }
        var attempt = 0
        while (true) {
            try {
                return DeepLTranslationService.translateTextOnly(text, sourceLang, targetLang, settings?.deepLApiKey.orEmpty())
            } catch (e: Exception) {
                if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                attempt++
                val message = e.message.orEmpty()
                val retryable = e is IOException || Regex("HTTP (429|5\\d\\d)", RegexOption.IGNORE_CASE).containsMatchIn(message)
                if (!retryable || attempt >= 2) {
                    failures += "المزودات الأساسية: ${message.take(140)}"
                    throw IllegalStateException("فشلت الترجمة عبر المزودات المتاحة. ${failures.joinToString(" | ")}", e)
                }
                try { Thread.sleep(500L * attempt) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("تم إيقاف انتظار إعادة محاولة الترجمة", interrupted)
                }
            }
        }
    }

    private fun translateTextOnce(text: String, sourceLang: String, targetLang: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val pair = URLEncoder.encode("$sourceLang|$targetLang", "UTF-8")
        val connection = NetworkRequestRegistry.register(
            URL("https://api.mymemory.translated.net/get?q=$q&langpair=$pair").openConnection() as HttpURLConnection
        ).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SariSubtitle/0.3")
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) throw IllegalStateException("لم يصل رد من خدمة الترجمة")

            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("خدمة الترجمة أعادت HTTP $code")

            val json = JSONObject(body)
            val status = json.optInt("responseStatus", 200)
            if (status != 200) {
                throw IllegalStateException(
                    json.optString("responseDetails", "فشلت الترجمة ($status)")
                )
            }
            val translated = json.optJSONObject("responseData")
                ?.optString("translatedText")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("لم تُرجع خدمة الترجمة نصاً")

            return Html.fromHtml(translated, Html.FROM_HTML_MODE_LEGACY).toString()
        } finally {
            connection.disconnect()
            NetworkRequestRegistry.unregister(connection)
        }
    }
}

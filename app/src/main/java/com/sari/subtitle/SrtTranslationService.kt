package com.sari.subtitle

import android.text.Html
import android.content.Context
import android.util.Log
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
        if (context != null) OperationLogStore.add(context, "ترجمة SRT", "اختيار حسب الإعدادات", "Gemini / OmniRoute / بدائل نصية",
            0L, "قيد التنفيذ", "جارٍ معالجة المقاطع", "target=$targetLang")
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        // Vosk outputs may vary in blank-line formatting; identify cue boundaries by timestamps.
        val blocks = splitSrtCuesByTimestamp(normalized)
        require(blocks.isNotEmpty()) { "الملف لا يحتوي على مقاطع SRT صالحة" }
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
            if (context != null) OperationLogStore.add(context, "ترجمة SRT", "بدون طلب مزود", "لا يلزم ترجمة", 0L,
                "نجاح", "لا توجد مقاطع أجنبية تحتاج ترجمة", "target=$targetLang")
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
            val translatedSubset = try {
                translateSrt(
                    source = subset,
                    sourceLang = language,
                    targetLang = targetLang,
                    context = context,
                    shouldCancel = shouldCancel,
                    onProgress = { done, _ -> onProgress((finished + done).coerceAtMost(totalForeign), totalForeign) }
                )
            } catch (e: Exception) {
                if (shouldCancel() || e is java.util.concurrent.CancellationException) {
                    throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                }
                Log.e(TAG, "Bulk translation failed for language=$language cues=${indexes.size}; retrying cue-by-cue", e)
                ""
            }

            // Match returned cues by their timestamp, not by list position/count.
            // This prevents one missing/extra provider cue from shifting all following subtitles.
            val returnedByTimestamp = mutableMapOf<String, MutableList<String>>()
            for (translatedBlock in splitSrtCuesByTimestamp(translatedSubset)) {
                val stamp = cueTimestamp(translatedBlock)
                if (stamp != null) returnedByTimestamp.getOrPut(stamp) { mutableListOf() }.add(translatedBlock)
            }
            val returnedCount = returnedByTimestamp.values.sumOf { it.size }
            if (returnedCount != indexes.size) {
                Log.w(TAG, "Partial SRT response: language=$language expected=${indexes.size} returned=$returnedCount; preserving original timestamps and recovering missing cues individually")
            }

            for ((localIndex, originalIndex) in indexes.withIndex()) {
                if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                val originalBlock = blocks[originalIndex]
                val stamp = cueTimestamp(originalBlock)
                val candidates = if (stamp == null) null else returnedByTimestamp[stamp]
                val matched = candidates?.removeAtOrNull(0)
                var applied = false
                if (matched != null && extractCueText(matched).isNotBlank()) {
                    output[originalIndex] = replaceCueText(originalBlock, matched)
                    applied = true
                } else {
                    // A missing/empty cue must not abort the file or alter any timestamp.
                    // Retry just this cue once; if that also fails, keep its original text.
                    try {
                        val single = translateSrt(
                            source = originalBlock,
                            sourceLang = language,
                            targetLang = targetLang,
                            context = context,
                            shouldCancel = shouldCancel
                        )
                        val recovered = splitSrtCuesByTimestamp(single).firstOrNull { cueTimestamp(it) == stamp }
                        if (recovered != null && extractCueText(recovered).isNotBlank()) {
                            output[originalIndex] = replaceCueText(originalBlock, recovered)
                            applied = true
                            Log.i(TAG, "Recovered missing cue: language=$language cue=${localIndex + 1} originalIndex=${originalIndex + 1}")
                        }
                    } catch (e: Exception) {
                        if (shouldCancel() || e is java.util.concurrent.CancellationException) {
                            throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                        }
                        Log.e(TAG, "Translation failed for cue ${localIndex + 1}/${indexes.size} (original cue ${originalIndex + 1}, language=$language); keeping original text", e)
                    }
                }
                if (!applied) {
                    Log.w(TAG, "Cue ${localIndex + 1}/${indexes.size} (original cue ${originalIndex + 1}, language=$language) was not translated; original text and timestamp preserved")
                }
                finished++
                onProgress(finished.coerceAtMost(totalForeign), totalForeign)
            }
            val extras = returnedByTimestamp.values.sumOf { it.size }
            if (extras > 0) Log.w(TAG, "Ignored $extras extra translated cue(s) for language=$language; original SRT timing was retained")
        }
        if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
        return output.joinToString("\r\n\r\n") + "\r\n"
    }

    private const val TAG = "SrtTranslationService"

    /** Determine cue boundaries from timestamp lines rather than blank-line separators. */
    private fun splitSrtCuesByTimestamp(source: String): List<String> {
        val normalized = source.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = normalized.lines()
        val timeLines = lines.indices.filter { timestampPattern.containsMatchIn(lines[it]) }
        if (timeLines.isEmpty()) return emptyList()
        val starts = timeLines.map { timeIndex ->
            if (timeIndex > 0 && lines[timeIndex - 1].trim().matches(Regex("\\d+"))) timeIndex - 1 else timeIndex
        }
        return starts.indices.map { index ->
            val end = if (index + 1 < starts.size) starts[index + 1] else lines.size
            lines.subList(starts[index], end).joinToString("\n").trim()
        }.filter { timestampPattern.containsMatchIn(it) }
    }

    private fun cueTimestamp(block: String): String? = block
        .replace("\r\n", "\n").replace('\r', '\n')
        .lineSequence().firstOrNull { timestampPattern.containsMatchIn(it) }?.trim()

    private fun extractCueText(block: String): String {
        val lines = block.replace("\r\n", "\n").replace('\r', '\n').lines()
        val ti = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
        return if (ti < 0) "" else lines.drop(ti + 1).joinToString("\n").trim()
    }

    private fun <T> MutableList<T>.removeAtOrNull(index: Int): T? =
        if (index in indices) removeAt(index) else null

    /** Preserve the original cue index/timestamp and replace only its text. */
    private fun replaceCueText(originalBlock: String, translatedBlock: String): String {
        val originalLines = originalBlock.replace("\r\n", "\n").replace('\r', '\n').lines()
        val translatedLines = translatedBlock.replace("\r\n", "\n").replace('\r', '\n').lines()
        val originalTimeIndex = originalLines.indexOfFirst { timestampPattern.containsMatchIn(it) }
        val translatedTimeIndex = translatedLines.indexOfFirst { timestampPattern.containsMatchIn(it) }
        if (originalTimeIndex < 0 || translatedTimeIndex < 0) {
            Log.w(TAG, "Could not locate cue timestamp while replacing translated text; keeping translated block")
            return translatedBlock.trim()
        }
        val header = originalLines.take(originalTimeIndex + 1)
        // Collapse blank lines inside one cue so they cannot look like cue boundaries.
        val translatedText = translatedLines.drop(translatedTimeIndex + 1)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        return (header + translatedText).joinToString("\n")
    }

    fun translateSrt(
        source: String,
        sourceLang: String,
        targetLang: String,
        context: Context? = null,
        shouldCancel: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        if (context != null) OperationLogStore.add(context, "ترجمة SRT", "اختيار حسب الإعدادات", "Gemini / OmniRoute / بدائل نصية",
            0L, "قيد التنفيذ", "جارٍ معالجة المقاطع", "$sourceLang→$targetLang")
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
        if (pending.isEmpty() && context != null) OperationLogStore.add(context, "ترجمة SRT", "ذاكرة التخزين المؤقت", "SRT translation cache", 0L,
            "نجاح", "${total} مقطع", "لم يلزم طلب شبكة")
        for (batch in pending.chunked(8)) {
            if (shouldCancel()) throw java.util.concurrent.CancellationException("ألغى المستخدم العملية")
            val batchStarted = android.os.SystemClock.elapsedRealtime()
            val batchTexts = batch.map { it.text }
            var batchResults: List<String>? = null
            var batchProvider = "none"
            val omniFirst = settings?.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE
            fun tryOmniRoute(): List<String>? {
                if (!omniRouteAvailable || settings == null) return null
                if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                return try {
                    val result = OmniRouteTranslationClient(settings).translateBatch(batchTexts, sourceLang, targetLang)
                    batchProvider = "OmniRoute"
                    result
                } catch (e: Exception) {
                    if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                    Log.e(TAG, "OmniRoute batch failed; configured route will continue to next text provider. source=$sourceLang target=$targetLang batchSize=${batchTexts.size} reason=${e.message}", e)
                    omniRouteAvailable = false
                    null
                }
            }
            fun tryGemini(): List<String>? {
                if (!geminiAvailable) return null
                if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                return try {
                    val result = GeminiTranslationService.translateBatch(batchTexts, sourceLang, targetLang, geminiKey)
                    batchProvider = "Gemini"
                    result
                } catch (e: Exception) {
                    if (shouldCancel()) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                    Log.e(TAG, "Gemini translation batch failed; configured route will continue to next text provider. source=$sourceLang target=$targetLang batchSize=${batchTexts.size} reason=${e.message}", e)
                    geminiAvailable = false
                    null
                }
            }
            // One source of truth for automatic and manually selected SRT translation.
            // Vosk is STT, not a text-translation fallback; it is never called from this route.
            Log.i(TAG, "Translation route=${if (omniFirst) "OmniRoute->Gemini" else if (omniRouteAvailable) "Gemini->OmniRoute" else "Gemini->text-fallbacks"} source=$sourceLang target=$targetLang cues=${batch.size}")
            if (omniFirst) {
                batchResults = tryOmniRoute() ?: tryGemini()
            } else {
                batchResults = tryGemini() ?: tryOmniRoute()
            }

            for ((batchIndex, cue) in batch.withIndex()) {
                if (shouldCancel()) throw java.util.concurrent.CancellationException("ألغى المستخدم العملية")
                val providerResult = batchResults?.getOrNull(batchIndex)?.takeIf { it.isNotBlank() }
                val translated = providerResult ?: try {
                    val fallback = translateText(cue.text, sourceLang, targetLang, settings, shouldCancel)
                    if (batchProvider == "none" || providerResult == null) {
                        Log.i(TAG, "cue=${cue.blockIndex + 1} provider=additional-text-fallback reason=Gemini/OmniRoute returned no cue or failed")
                    }
                    fallback
                } catch (e: Exception) {
                    Log.e(TAG, "Translation failed for cue=${cue.blockIndex + 1} source=$sourceLang target=$targetLang attempted=$batchProvider reason=${e.message}; original cue text retained", e)
                    throw IllegalStateException("فشل ترجمة المقطع رقم ${cue.blockIndex + 1} بعد تجربة $batchProvider والبدائل النصية: ${e.message}", e)
                }
                Log.i(TAG, "cue=${cue.blockIndex + 1} provider=${if (providerResult != null) batchProvider else "additional-text-fallback"} source=$sourceLang target=$targetLang resultChars=${translated.length}")
                while (cue.lines.size > cue.timeIndex + 1) cue.lines.removeAt(cue.lines.lastIndex)
                cue.lines.add(translated)
                output[cue.blockIndex] = cue.lines.joinToString("\n")
                memoryCache[cue.text] = translated
                writePersistentCache(context, cue.cacheKey, translated)
                completed++
                onProgress(completed, total)
            }
            if (context != null) {
                val actualProvider = if (batchProvider != "none" && batchResults != null) batchProvider else "بدائل نصية (Azure/Hugging Face/DeepL)"
                val model = when (actualProvider) {
                    "Gemini" -> "Gemini translation batch"
                    "OmniRoute" -> "configured OmniRoute model"
                    else -> "provider fallback chain"
                }
                OperationLogStore.add(
                    context,
                    "ترجمة SRT",
                    actualProvider,
                    model,
                    (android.os.SystemClock.elapsedRealtime() - batchStarted).coerceAtLeast(0L),
                    "نجاح",
                    "${batch.size} مقطع؛ ${batchResults?.count { !it.isNullOrBlank() } ?: 0} نتائج دفعة",
                    "$sourceLang→$targetLang; fallback cues may use another provider"
                )
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

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
        /** Legacy fallback for installations that have no saved model preference. */
        const val DEFAULT_TRANSLATION_MODEL = "auto"
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
        // Respect the model selected by the user in Translation Providers.
        // "auto" is a supported OmniRoute routing target; do not silently pin
        // requests to a hard-coded model and ignore the saved preference.
        val configuredModel = modelOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: settings.omniRouteTextModel.trim().ifBlank { DEFAULT_TRANSLATION_MODEL }
        val selectedModel = if (configuredModel.equals("auto", ignoreCase = true)) {
            val routingMode = settings.omniRouteRoutingMode
            if (routingMode != "auto/subscription" && !settings.omniRoutePaidFallbackAllowed) {
                throw IOException("تم إيقاف التوجيه الذي قد يستهلك رصيداً مدفوعاً. فعّل الإذن صراحةً من إعدادات OmniRoute أو اختر حصة الاشتراك فقط.")
            }
            routingMode
        } else configuredModel
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

    /**
     * Discover the public OpenAI-compatible model list first, then enrich sparse rows
     * from OmniRoute's documented grouped catalog endpoint when available.
     */
    fun availableModels(): List<OmniRouteModel> {
        val publicResponse = requestJson("/models", null, 45000, 45000)
        val publicModels = parseModelRows(publicResponse)
        val needsEnrichment = publicModels.isEmpty() || publicModels.any { it.capabilities.isEmpty() }
        val catalogModels = if (needsEnrichment) {
            runCatching { parseCatalogRows(requestJson("/api/models/catalog", null, 45000, 45000)) }
                .getOrDefault(emptyList())
        } else emptyList()
        val merged = linkedMapOf<String, OmniRouteModel>()
        (publicModels + catalogModels).forEach { candidate ->
            val previous = merged[candidate.id]
            merged[candidate.id] = when {
                previous == null -> candidate
                candidate.capabilities.size > previous.capabilities.size -> candidate.copy(
                    ownedBy = candidate.ownedBy.ifBlank { previous.ownedBy },
                    isFree = candidate.isFree ?: previous.isFree,
                    authRequired = candidate.authRequired ?: previous.authRequired,
                    priceLabel = candidate.priceLabel ?: previous.priceLabel
                )
                else -> previous.copy(
                    capabilities = if (candidate.capabilities.size > previous.capabilities.size) candidate.capabilities else previous.capabilities,
                    ownedBy = previous.ownedBy.ifBlank { candidate.ownedBy },
                    isFree = previous.isFree ?: candidate.isFree,
                    authRequired = previous.authRequired ?: candidate.authRequired,
                    priceLabel = previous.priceLabel ?: candidate.priceLabel
                )
            }
        }
        return merged.values.sortedBy { it.id.lowercase() }
    }

    private fun parseModelRows(root: JSONObject): List<OmniRouteModel> {
        val rows = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            OmniRouteModelIntelligence.parse(rows.optJSONObject(index) ?: return@mapNotNull null)
        }
    }

    /** Catalog layouts differ between OmniRoute releases; accept documented provider-grouped forms. */
    private fun parseCatalogRows(root: JSONObject): List<OmniRouteModel> {
        val result = mutableListOf<OmniRouteModel>()
        result += parseModelRows(root)
        val providers = root.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val provider = providers.optJSONObject(i) ?: continue
                val providerName = provider.optString("id", provider.optString("name", ""))
                val rows = provider.optJSONArray("models") ?: provider.optJSONArray("data") ?: continue
                for (j in 0 until rows.length()) {
                    val model = rows.optJSONObject(j) ?: continue
                    if (!model.has("owned_by") && providerName.isNotBlank()) model.put("owned_by", providerName)
                    OmniRouteModelIntelligence.parse(model)?.let(result::add)
                }
            }
        }
        val providerMap = root.optJSONObject("providers")
        if (providerMap != null) {
            val keys = providerMap.keys()
            while (keys.hasNext()) {
                val providerName = keys.next()
                val providerValue = providerMap.opt(providerName)
                val rows = when (providerValue) {
                    is JSONArray -> providerValue
                    is JSONObject -> providerValue.optJSONArray("models") ?: providerValue.optJSONArray("data")
                    else -> null
                } ?: continue
                for (j in 0 until rows.length()) {
                    val model = rows.optJSONObject(j) ?: continue
                    if (!model.has("owned_by") && providerName.isNotBlank()) model.put("owned_by", providerName)
                    OmniRouteModelIntelligence.parse(model)?.let(result::add)
                }
            }
        }
        return result.distinctBy { it.id }
    }

    fun availableModelIds(): List<String> = availableModels().map { it.id }

    fun findModelId(providerFragment: String): String? = availableModelIds()
        .firstOrNull { it.contains(providerFragment, ignoreCase = true) }

    fun testConnection(): String {
        val models = availableModels()
        val configuredModel = settings.omniRouteTextModel.trim().ifBlank { DEFAULT_TRANSLATION_MODEL }
        if (!configuredModel.equals("auto", ignoreCase = true) && models.isNotEmpty() &&
            models.none { it.id == configuredModel }) {
            throw IOException(
                "اتصال OmniRoute ناجح، لكن النموذج المحدد غير موجود في /v1/models: $configuredModel. " +
                    "اختر auto أو معرّفاً ظاهراً في قائمة النماذج."
            )
        }
        val freeKnown = models.count { it.isFree == true }
        val paidKnown = models.count { it.isFree == false }
        val unknownCost = models.size - freeKnown - paidKnown
        return "اتصال OmniRoute ناجح. النماذج: ${models.size}؛ مجانية وفق بيانات الخادم: $freeKnown؛ غير مجانية: $paidKnown؛ السعر غير معروف: $unknownCost. وضع التوجيه: ${settings.omniRouteRoutingMode}."
    }

    private fun requestJson(path: String, payload: String?, connectTimeoutMs: Int, readTimeoutMs: Int): JSONObject {
        val url = if (path.startsWith("/api/")) rootEndpoint(path) else endpoint(path)
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

    private fun rootEndpoint(path: String): String {
        val base = settings.omniRouteBaseUrl.trim().trimEnd('/')
        val parsed = runCatching { URL(base) }.getOrElse { throw IllegalArgumentException("عنوان OmniRoute غير صالح.") }
        require((parsed.protocol == "https" || parsed.protocol == "http") && parsed.host.isNotBlank()) {
            "استخدم عنوان HTTP/HTTPS صالحاً لخادم OmniRoute."
        }
        val root = if (base.endsWith("/v1", ignoreCase = true)) base.dropLast(3).trimEnd('/') else base
        return root + path
    }

    private fun endpoint(path: String): String {
        val base = settings.omniRouteBaseUrl.trim().trimEnd('/')
        val parsed = runCatching { URL(base) }.getOrElse { throw IllegalArgumentException("عنوان OmniRoute غير صالح.") }
        require((parsed.protocol == "https" || parsed.protocol == "http") && parsed.host.isNotBlank()) {
            "استخدم عنوان HTTP/HTTPS صالحاً لخادم OmniRoute."
        }
        return if (base.endsWith("/v1")) base + path else "$base/v1$path"
    }
}

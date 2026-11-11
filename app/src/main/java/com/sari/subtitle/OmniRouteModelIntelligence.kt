package com.sari.subtitle

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Metadata is deliberately nullable: /v1/models often omits pricing and capability data. */
data class OmniRouteModel(
    val id: String,
    val ownedBy: String,
    val objectType: String,
    val capabilities: Set<String> = emptySet(),
    val isFree: Boolean? = null,
    val authRequired: Boolean? = null,
    val priceLabel: String? = null
) {
    fun supports(task: String): Boolean {
        val normalized = task.trim().lowercase(Locale.ROOT)
        return when (normalized) {
            "chat", "text", "translation" -> capabilities.any { it in CHAT_CAPABILITIES }
            "stt", "transcription", "audio_input" -> capabilities.any { it in STT_CAPABILITIES }
            "tts", "speech_output" -> capabilities.any { it in TTS_CAPABILITIES }
            "search" -> capabilities.any { it in SEARCH_CAPABILITIES }
            "embedding" -> capabilities.any { it in EMBEDDING_CAPABILITIES }
            "image" -> capabilities.any { it in IMAGE_CAPABILITIES }
            "video" -> capabilities.any { it in VIDEO_CAPABILITIES }
            else -> capabilities.contains(normalized)
        }
    }

    val capabilityLabel: String
        get() {
            if (capabilities.isEmpty()) return "القدرات غير محددة"
            val labels = buildList {
                if (supports("chat")) add("نص/ترجمة")
                if (supports("stt")) add("تفريغ صوت")
                if (supports("tts")) add("توليد صوت")
                if (supports("search")) add("بحث")
                if (supports("embedding")) add("تضمين")
                if (supports("image")) add("صور")
                if (supports("video")) add("فيديو")
            }
            return labels.ifEmpty { capabilities.sorted() }.joinToString("، ")
        }

    companion object {
        val CHAT_CAPABILITIES = setOf("chat", "text", "completion", "completions", "messages", "responses")
        val STT_CAPABILITIES = setOf("audio", "stt", "transcription", "transcriptions", "speech-to-text", "audio/transcriptions")
        val TTS_CAPABILITIES = setOf("tts", "speech", "audio/speech", "text-to-speech")
        val SEARCH_CAPABILITIES = setOf("search", "web-search")
        val EMBEDDING_CAPABILITIES = setOf("embedding", "embeddings")
        val IMAGE_CAPABILITIES = setOf("image", "images", "image-generation")
        val VIDEO_CAPABILITIES = setOf("video", "videos", "video-generation")
    }

    val costLabel: String
        get() = when (isFree) {
            true -> "مجاني حسب بيانات الخادم"
            false -> "ليس مجانياً حسب بيانات الخادم"
            null -> "السعر غير معروف"
        }

    val authLabel: String
        get() = when (authRequired) {
            true -> "يتطلب مصادقة"
            false -> "لا يتطلب مصادقة upstream"
            null -> "المصادقة غير محددة"
        }
}

object OmniRouteModelIntelligence {
    fun parse(item: JSONObject): OmniRouteModel? {
        val id = item.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
        val metadata = item.optJSONObject("metadata") ?: item.optJSONObject("meta") ?: JSONObject()
        val capabilities = buildSet {
            addAll(readStringSet(item.opt("capabilities") ?: metadata.opt("capabilities")))
            addAll(readStringSet(item.opt("supported_modalities") ?: metadata.opt("supported_modalities")))
            addAll(readStringSet(item.opt("supportedEndpoints") ?: item.opt("supported_endpoints")
                ?: metadata.opt("supportedEndpoints") ?: metadata.opt("supported_endpoints")))
            addAll(readStringSet(item.opt("endpoints") ?: metadata.opt("endpoints")))
            val type = item.optString("type", metadata.optString("type", "")).trim().lowercase(Locale.ROOT)
            if (type.isNotBlank()) add(type)
            val objectType = item.optString("object", "").trim().lowercase(Locale.ROOT)
            if (objectType in setOf("embedding", "image", "audio", "video", "rerank")) add(objectType)
            val endpointText = (item.opt("supportedEndpoints") ?: item.opt("supported_endpoints")
                ?: metadata.opt("supportedEndpoints") ?: metadata.opt("supported_endpoints"))?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if ("/chat/completions" in endpointText || "/messages" in endpointText || "/responses" in endpointText) add("chat")
            if ("/audio/transcriptions" in endpointText) add("stt")
            if ("/audio/speech" in endpointText) add("tts")
            if ("/search" in endpointText) add("search")
            if ("/embeddings" in endpointText) add("embedding")
            if ("/images" in endpointText) add("image")
            if ("/videos" in endpointText) add("video")
        }
        val free = readBoolean(item, metadata, "is_free", "isFree", "free")
            ?: readZeroPrice(item, metadata)
        val auth = readBoolean(item, metadata, "auth_required", "authRequired", "requires_auth")
        val price = readPriceLabel(item, metadata)
        return OmniRouteModel(
            id = id,
            ownedBy = item.optString("owned_by", item.optString("provider", "")).trim(),
            objectType = item.optString("object", item.optString("type", "")).trim(),
            capabilities = capabilities,
            isFree = free,
            authRequired = auth,
            priceLabel = price
        )
    }

    private fun readBoolean(primary: JSONObject, metadata: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            val value = if (primary.has(key)) primary.opt(key) else metadata.opt(key)
            when (value) {
                is Boolean -> return value
                is Number -> if (value.toDouble() == 0.0 || value.toDouble() == 1.0) return value.toDouble() == 1.0
                is String -> when (value.trim().lowercase(Locale.ROOT)) {
                    "true", "yes", "1" -> return true
                    "false", "no", "0" -> return false
                }
            }
        }
        return null
    }

    private fun readZeroPrice(primary: JSONObject, metadata: JSONObject): Boolean? {
        val pricing = primary.optJSONObject("pricing") ?: metadata.optJSONObject("pricing") ?: return null
        val keys = when {
            pricing.has("prompt") && pricing.has("completion") -> listOf("prompt", "completion")
            pricing.has("input") && pricing.has("output") -> listOf("input", "output")
            else -> return null
        }
        val values = keys.map { key -> pricing.opt(key)?.toString()?.toDoubleOrNull() ?: return null }
        return values.all { it == 0.0 }
    }

    private fun readPriceLabel(primary: JSONObject, metadata: JSONObject): String? {
        val pricing = primary.optJSONObject("pricing") ?: metadata.optJSONObject("pricing")
        if (pricing != null) return pricing.toString()
        return (primary.opt("price") ?: metadata.opt("price"))?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun readStringSet(value: Any?): Set<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank) }.toSet()
        is String -> value.split(',', ';', ' ').map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).toSet()
        else -> emptySet()
    }
}

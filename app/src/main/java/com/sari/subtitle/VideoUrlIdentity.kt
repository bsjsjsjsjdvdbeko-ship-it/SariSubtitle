package com.sari.subtitle

import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

/** One source of truth for comparing and indexing video URLs across the app. */
object VideoUrlIdentity {
    private val trackingParameters = setOf("si", "feature", "fbclid", "gclid", "ref_src")

    /** Stable ID for X and YouTube; otherwise preserve meaningful query parameters. */
    fun normalize(raw: String): String {
        val cleaned = raw.trim().trimEnd('.', ',', ')', ']', '}', '،')
        if (cleaned.isBlank()) return ""
        return try {
            val uri = Uri.parse(cleaned)
            val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
            if (host.isBlank()) return "raw:$cleaned"

            val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
            val isXHost = host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com")
            val statusId = Regex("(?:^|/)status/(\\d+)(?:/|$)")
                .find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
            if (isXHost && !statusId.isNullOrBlank()) return "x-status:$statusId"

            val isYouTubeHost = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
            if (isYouTubeHost) {
                val videoId = YouTubeCaptionExtractor.getVideoId(cleaned)
                if (!videoId.isNullOrBlank()) return "youtube-id:$videoId"
            }

            val queryPairs = uri.queryParameterNames
                .filterNot { key -> key.lowercase(Locale.ROOT) in trackingParameters || key.startsWith("utm_", ignoreCase = true) }
                .sorted()
                .flatMap { key -> uri.getQueryParameters(key).sorted().map { value ->
                    URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
                } }
            val query = queryPairs.joinToString("&")
            val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
            val port = uri.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
            "url:$host${port?.let { ":$it" }.orEmpty()}$path${if (query.isNotBlank()) "?$query" else ""}"
        } catch (_: Exception) {
            "raw:$cleaned"
        }
    }

    fun canonicalKey(raw: String): String {
        val key = normalize(raw)
        return if (key.startsWith("x-status:") || key.startsWith("youtube-id:")) key else ""
    }

    fun sameVideo(first: String, second: String): Boolean {
        val a = normalize(first)
        val b = normalize(second)
        return a.isNotBlank() && a == b
    }
}

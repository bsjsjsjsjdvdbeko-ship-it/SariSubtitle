package com.sari.subtitle

import android.content.Intent

object ShareLinkHandler {
    fun extract(intent: Intent?): String? {
        intent ?: return null
        val candidates = buildList {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::add)
            intent.dataString?.let(::add)
        }
        return candidates.asSequence()
            .flatMap { extractUrls(it).asSequence() }
            .firstOrNull { VideoPlatformResolver.resolve(it) != null }
    }

    private fun extractUrls(text: String): List<String> =
        Regex("""https?://[^\s<>"']+""").findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '}', ';') }.toList()
}

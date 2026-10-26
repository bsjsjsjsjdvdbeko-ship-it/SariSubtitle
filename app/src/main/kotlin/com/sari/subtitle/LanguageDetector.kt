package com.sari.subtitle

/** Conservative local hint only. Live STT remains responsible for authoritative language detection. */
object LanguageDetector {
    fun detect(text: String): String {
        val t = text.trim()
        if (t.isBlank()) return "auto"
        val arabic = t.count { it in '\u0600'..'\u06ff' }
        val latin = t.count { it in 'A'..'Z' || it in 'a'..'z' }
        return when {
            arabic >= maxOf(3, latin) -> "ar"
            latin > arabic -> "en"
            else -> "auto"
        }
    }
}

package com.sari.subtitle.v2

/**
 * يمثل سطر ترجمة واحد من ملف SRT.
 * يُستخدم في المرحلة القادمة (SRT Parser).
 */
data class SubtitleCue(
    val index: Int,
    val startMs: Long,   // وقت البداية بالمللي ثانية
    val endMs: Long,     // وقت النهاية بالمللي ثانية
    val text: String
) {
    fun contains(ms: Long): Boolean = ms in startMs until endMs
}

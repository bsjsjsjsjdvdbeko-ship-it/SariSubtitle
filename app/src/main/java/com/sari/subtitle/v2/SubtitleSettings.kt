package com.sari.subtitle.v2

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * إعدادات عرض الترجمة.
 * تُحفظ تلقائياً في SharedPreferences.
 */
class SubtitleSettings(context: Context) {

    companion object {
        private const val PREFS = "sari_subtitle_prefs"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_POSITION = "position"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_BG_COLOR = "bg_color"
        private const val KEY_BOLD = "bold"

        // أحجام الخط (بالـ sp)
        val TEXT_SIZES = floatArrayOf(14f, 18f, 22f, 28f, 34f)
        val TEXT_SIZE_LABELS = arrayOf("صغير", "متوسط", "كبير", "كبير جداً", "ضخم")

        // المواضع
        const val POS_BOTTOM = 0
        const val POS_CENTER = 1
        const val POS_TOP = 2
        val POSITION_LABELS = arrayOf("أسفل", "وسط", "أعلى")

        // ألوان النص
        val TEXT_COLORS = intArrayOf(
            Color.WHITE,
            Color.YELLOW,
            Color.CYAN,
            Color.GREEN,
            0xFFFFA500.toInt()  // برتقالي
        )
        val TEXT_COLOR_LABELS = arrayOf("أبيض", "أصفر", "سماوي", "أخضر", "برتقالي")

        // ألوان الخلفية (مع شفافية)
        val BG_COLORS = intArrayOf(
            Color.argb(180, 0, 0, 0),     // أسود شبه شفاف
            Color.argb(120, 0, 0, 0),     // أسود خفيف
            Color.TRANSPARENT,             // شفاف
            Color.argb(180, 0, 0, 80)     // أزرق داكن
        )
        val BG_COLOR_LABELS = arrayOf("أسود قوي", "أسود خفيف", "شفاف", "أزرق")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var textSizeIndex: Int
        get() = prefs.getInt(KEY_TEXT_SIZE, 2)  // كبير افتراضياً
        set(v) = prefs.edit().putInt(KEY_TEXT_SIZE, v).apply()

    var position: Int
        get() = prefs.getInt(KEY_POSITION, POS_BOTTOM)
        set(v) = prefs.edit().putInt(KEY_POSITION, v).apply()

    var textColorIndex: Int
        get() = prefs.getInt(KEY_TEXT_COLOR, 0)
        set(v) = prefs.edit().putInt(KEY_TEXT_COLOR, v).apply()

    var bgColorIndex: Int
        get() = prefs.getInt(KEY_BG_COLOR, 0)
        set(v) = prefs.edit().putInt(KEY_BG_COLOR, v).apply()

    var bold: Boolean
        get() = prefs.getBoolean(KEY_BOLD, true)
        set(v) = prefs.edit().putBoolean(KEY_BOLD, v).apply()

    val textSize: Float get() = TEXT_SIZES[textSizeIndex.coerceIn(0, TEXT_SIZES.size - 1)]
    val textColor: Int get() = TEXT_COLORS[textColorIndex.coerceIn(0, TEXT_COLORS.size - 1)]
    val bgColor: Int get() = BG_COLORS[bgColorIndex.coerceIn(0, BG_COLORS.size - 1)]
}

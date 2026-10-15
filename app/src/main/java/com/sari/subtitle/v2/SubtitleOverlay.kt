package com.sari.subtitle.v2

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * طبقة شفافة لعرض الترجمة فوق WebView.
 * حالياً: نص ثابت للاختبار.
 * لاحقاً: تُحدَّث بـ cue من SRT بناءً على currentTime.
 */
class SubtitleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val textView: TextView

    init {
        // شفافة تماماً — لا تحجب اللمس
        isClickable = false
        isFocusable = false

        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(20, 14, 20, 14)
            // خلفية سوداء شبه شفافة
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            // ظل قوي للنص
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            text = "Sari Subtitle — Test"
            visibility = View.GONE
        }

        // الموضع: أسفل الشاشة مع هامش
        val lp = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 60
            leftMargin = 20
            rightMargin = 20
        }

        addView(textView, lp)
    }

    /**
     * عرض نص الترجمة.
     */
    fun show(text: String) {
        textView.text = text
        textView.visibility = View.VISIBLE
        visibility = View.VISIBLE
    }

    /**
     * إخفاء الترجمة.
     */
    fun hide() {
        textView.visibility = View.GONE
        visibility = View.GONE
    }

    /**
     * تغيير حجم الخط.
     */
    fun setTextSize(sp: Float) {
        textView.textSize = sp
    }
}

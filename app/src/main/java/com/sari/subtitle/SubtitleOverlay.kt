package com.sari.subtitle

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * طبقة شفافة لعرض الترجمة فوق WebView.
 * تدعم الإعدادات: حجم، لون، خلفية، موضع، خط عريض.
 */
class SubtitleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val textView: TextView
    private val subtitleSettings = SubtitleSettings(context.applicationContext)

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downViewX = 0f
    private var downViewY = 0f
    private var dragTouchSlop = 0f

    init {
        isClickable = false
        isFocusable = false

        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(28, 18, 28, 18)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            text = "Sari Subtitle"
            visibility = View.GONE
            isClickable = true
            isFocusable = false
        }

        val lp = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 70
            leftMargin = 20
            rightMargin = 20
        }

        addView(textView, lp)
        dragTouchSlop = context.resources.displayMetrics.density * 8f
        textView.setOnTouchListener { view, event -> handleDragTouch(view, event) }
    }

    private fun handleDragTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downViewX = view.x
                downViewY = view.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (kotlin.math.abs(dx) > dragTouchSlop || kotlin.math.abs(dy) > dragTouchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    moveSubtitleTo(downViewX + dx, downViewY + dy)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    persistCurrentPosition()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                return true
            }
        }
        return true
    }

    private fun moveSubtitleTo(rawX: Float, rawY: Float) {
        val maxX = (width - textView.width - paddingLeft - paddingRight).coerceAtLeast(0)
        val maxY = (height - textView.height - paddingTop - paddingBottom).coerceAtLeast(0)
        val clampedX = rawX.coerceIn(paddingLeft.toFloat(), (paddingLeft + maxX).toFloat())
        val clampedY = rawY.coerceIn(paddingTop.toFloat(), (paddingTop + maxY).toFloat())
        textView.x = clampedX
        textView.y = clampedY
    }

    private fun persistCurrentPosition() {
        val maxX = (width - textView.width - paddingLeft - paddingRight).coerceAtLeast(1)
        val maxY = (height - textView.height - paddingTop - paddingBottom).coerceAtLeast(1)
        subtitleSettings.subtitleDragX = ((textView.x - paddingLeft) / maxX).coerceIn(0f, 1f)
        subtitleSettings.subtitleDragY = ((textView.y - paddingTop) / maxY).coerceIn(0f, 1f)
        subtitleSettings.hasCustomSubtitlePosition = true
    }

    private fun restoreSavedPosition(settings: SubtitleSettings) {
        if (!settings.hasCustomSubtitlePosition) return
        post {
            if (width <= 0 || height <= 0 || textView.width <= 0 || textView.height <= 0) return@post
            val maxX = (width - textView.width - paddingLeft - paddingRight).coerceAtLeast(0)
            val maxY = (height - textView.height - paddingTop - paddingBottom).coerceAtLeast(0)
            val x = paddingLeft + (maxX * settings.subtitleDragX)
            val y = paddingTop + (maxY * settings.subtitleDragY)
            moveSubtitleTo(x, y)
        }
    }

    /**
     * تطبيق الإعدادات.
     */
    fun applySettings(settings: SubtitleSettings) {
        // حجم الخط
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.textSize)

        // لون النص
        textView.setTextColor(settings.textColor)

        // خلفية
        textView.setBackgroundColor(settings.bgColor)

        // عريض
        textView.typeface = if (settings.bold) {
            Typeface.DEFAULT_BOLD
        } else {
            Typeface.DEFAULT
        }

        // ظل: قوي على نص أبيض/فاتح، ضعيف على خلفية شفافة
        val shadowRadius = if (settings.bgColor == Color.TRANSPARENT) 10f else 4f
        textView.setShadowLayer(shadowRadius, 0f, 2f, Color.BLACK)

        // الموضع: إذا سحب المستخدم الترجمة، استخدم الموضع المحفوظ؛ وإلا الوضع التقليدي.
        val lp = textView.layoutParams as LayoutParams
        if (settings.hasCustomSubtitlePosition) {
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.topMargin = 0
            lp.bottomMargin = 0
            lp.leftMargin = 0
            lp.rightMargin = 0
        } else {
            when (settings.position) {
                SubtitleSettings.POS_TOP -> {
                    lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    lp.topMargin = 70
                    lp.bottomMargin = 0
                    lp.leftMargin = 20
                    lp.rightMargin = 20
                }
                SubtitleSettings.POS_CENTER -> {
                    lp.gravity = Gravity.CENTER
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                    lp.leftMargin = 20
                    lp.rightMargin = 20
                }
                else -> {
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    lp.topMargin = 0
                    lp.bottomMargin = 70
                    lp.leftMargin = 20
                    lp.rightMargin = 20
                }
            }
        }
        textView.layoutParams = lp
        restoreSavedPosition(settings)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && subtitleSettings.hasCustomSubtitlePosition) {
            restoreSavedPosition(subtitleSettings)
        }
    }

    fun resetSubtitlePosition() {
        subtitleSettings.resetSubtitlePosition()
        applySettings(subtitleSettings)
    }

    fun show(text: String) {
        textView.text = text
        textView.visibility = View.VISIBLE
        visibility = View.VISIBLE
    }

    fun hide() {
        textView.visibility = View.GONE
        visibility = View.GONE
    }

    /**
     * عرض مؤقت للنص (للمعاينة في الإعدادات).
     */
    fun preview(text: String) {
        val prevVis = textView.visibility
        textView.text = text
        textView.visibility = View.VISIBLE
        visibility = View.VISIBLE
        postDelayed({
            if (prevVis == View.GONE) {
                textView.visibility = View.GONE
                visibility = View.GONE
            }
        }, 2000)
    }
}

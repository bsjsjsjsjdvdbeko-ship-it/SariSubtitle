package com.sari.subtitle

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Subtitle-style overlay. It deliberately avoids a card/header so it looks like a real
 * subtitle track over arbitrary video apps. It does not bypass source-app restrictions.
 */
class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var panel: FrameLayout? = null
    private var translatedText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hide = Runnable { panel?.visibility = View.GONE }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { remove(); stopSelf(); return START_NOT_STICKY }
            ACTION_UPDATE -> {
                val translated = intent.getStringExtra(EXTRA_TRANSLATED).orEmpty().trim()
                if (translated.isBlank()) return START_NOT_STICKY
                show()
                translatedText?.text = translated
                panel?.visibility = View.VISIBLE
                applySettings()
                handler.removeCallbacks(hide)
                val duration = intent.getLongExtra(EXTRA_DURATION_MS, 2_200L).coerceIn(900L, 6_000L)
                handler.postDelayed(hide, duration)
            }
            else -> show()
        }
        return START_NOT_STICKY
    }

    private fun applySettings() {
        serviceScope.launch {
            val settings = try { SettingsRepository(this@OverlayService).load() } catch (_: Exception) { AppSettings() }
            translatedText?.textSize = settings.fontSize.toFloat().coerceIn(12f, 48f)
            translatedText?.alpha = settings.opacity.coerceIn(0, 100) / 100f
            val bg = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            panel?.background = bg
            params?.let { windowParams ->
                val density = resources.displayMetrics.density
                windowParams.gravity = when (settings.subtitlePosition) {
                    "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    "center" -> Gravity.CENTER
                    else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                windowParams.y = if (settings.subtitlePosition == "top") (36 * density).toInt() else if (settings.subtitlePosition == "center") 0 else (48 * density).toInt()
                runCatching { panel?.let { wm?.updateViewLayout(it, windowParams) } }
            }
        }
    }

    private fun show() {
        if (panel != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val side = (24 * density).toInt()
        val bottom = (48 * density).toInt()
        val maxWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()

        translatedText = TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textDirection = View.TEXT_DIRECTION_ANY_RTL
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(7f, 0f, 3f, Color.BLACK)
            includeFontPadding = true
            layoutParams = FrameLayout.LayoutParams(maxWidth, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }

        panel = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(side, 0, side, bottom)
            addView(translatedText)
            setOnTouchListener(object : View.OnTouchListener {
                var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val p = params ?: return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; return true }
                        MotionEvent.ACTION_MOVE -> {
                            p.x = startX + (event.rawX - downX).toInt()
                            p.y = startY + (event.rawY - downY).toInt()
                            runCatching { wm?.updateViewLayout(v, p) }
                            return true
                        }
                    }
                    return true
                }
            })
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
        }
        runCatching { wm?.addView(panel, params) }.onFailure { panel = null; translatedText = null }
        applySettings()
    }

    private fun remove() {
        handler.removeCallbacks(hide)
        runCatching { panel?.let { wm?.removeView(it) } }
        panel = null
        translatedText = null
    }

    override fun onDestroy() { remove(); serviceScope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SHOW = "com.sari.subtitle.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.sari.subtitle.HIDE_OVERLAY"
        const val ACTION_UPDATE = "com.sari.subtitle.UPDATE_OVERLAY"
        const val EXTRA_ORIGINAL = "original"
        const val EXTRA_TRANSLATED = "translated"
        const val EXTRA_DURATION_MS = "duration_ms"
    }
}

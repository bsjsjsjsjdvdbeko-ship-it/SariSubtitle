package com.sari.subtitle.v2

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/** Small internal player for a saved local media URI with its saved SRT. */
class LocalMediaPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SRT = "local_media_srt"
        const val EXTRA_TITLE = "local_media_title"
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var video: VideoView
    private lateinit var subtitle: TextView
    private lateinit var play: Button
    private var cues: List<SubtitleCue> = emptyList()
    private val tick = object : Runnable {
        override fun run() {
            if (!isFinishing && ::video.isInitialized) {
                val ms = video.currentPosition.toLong()
                subtitle.text = cues.firstOrNull { it.contains(ms) }?.text.orEmpty()
                play.text = if (video.isPlaying) "إيقاف مؤقت" else "تشغيل"
                handler.postDelayed(this, 120L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri = intent.data ?: run { finish(); return }
        cues = try { SrtParser.parse(intent.getStringExtra(EXTRA_SRT).orEmpty().byteInputStream(Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
        title = intent.getStringExtra(EXTRA_TITLE) ?: "مشغل Sari Subtitle"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF071326.toInt()) }
        video = VideoView(this)
        root.addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val stage = FrameLayout(this)
        subtitle = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xB8000000.toInt())
            textSize = 22f; gravity = Gravity.CENTER; textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(16, 10, 16, 10)
        }
        stage.addView(subtitle, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(stage, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "−10 ث"; setOnClickListener { video.seekTo((video.currentPosition - 10_000).coerceAtLeast(0)) } }
        play = Button(this).apply { text = "تشغيل"; setOnClickListener { if (video.isPlaying) video.pause() else video.start() } }
        val forward = Button(this).apply { text = "+10 ث"; setOnClickListener { video.seekTo(video.currentPosition + 10_000) } }
        controls.addView(back); controls.addView(play); controls.addView(forward)
        root.addView(controls)
        setContentView(root)
        video.setOnErrorListener { _, what, extra ->
            subtitle.text = "تعذّر تشغيل هذا الملف ($what/$extra). قد يكون تنسيق الفيديو غير مدعوم."
            true
        }
        video.setVideoURI(uri)
        video.setOnPreparedListener { player -> player.isLooping = false; video.start(); handler.removeCallbacks(tick); handler.post(tick) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (::video.isInitialized) { try { video.stopPlayback() } catch (_: Exception) {} }
        super.onDestroy()
    }
}

package com.sari.subtitle

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import android.widget.MediaController
import android.widget.Toast
import java.io.File
import java.io.IOException

/** Player for a user-selected local video with its saved SRT. Every open/play path is guarded. */
class LocalMediaPlayerActivity : Activity() {
    companion object {
        const val EXTRA_SRT = "local_media_srt"
        const val EXTRA_TITLE = "local_media_title"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var video: VideoView? = null
    private var subtitleView: TextView? = null
    private var cues: List<SubtitleCue> = emptyList()
    private var cachedFile: File? = null
    private var tickRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val uri = intent?.data
            if (uri == null || (uri.scheme != "content" && uri.scheme != "file")) {
                showOpenError("لا يمكن فتح هذا الملف: رابط الفيديو المحلي غير صالح.")
                return
            }
            val srt = intent.getStringExtra(EXTRA_SRT).orEmpty()
            cues = try { SrtParser.parse(srt.byteInputStream(Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
            }
            root.addView(TextView(this).apply {
                text = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "مشغّل الفيديو المحلي"
                textSize = 18f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val player = VideoView(this)
            video = player
            root.addView(player, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            subtitleView = TextView(this).apply {
                text = if (cues.isEmpty()) "لا توجد ترجمة صالحة مرتبطة بهذا الفيديو" else ""
                textSize = 19f
                gravity = android.view.Gravity.CENTER
                setPadding(12, 12, 12, 12)
            }
            root.addView(subtitleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(Button(this).apply { text = "رجوع 10 ثوانٍ"; setOnClickListener { safeSeek(-10_000) } })
            controls.addView(Button(this).apply { text = "تشغيل/إيقاف"; setOnClickListener { safeToggle() } })
            controls.addView(Button(this).apply { text = "تقديم 10 ثوانٍ"; setOnClickListener { safeSeek(10_000) } })
            root.addView(controls)
            setContentView(root)

            player.setMediaController(MediaController(this))
            player.setOnPreparedListener { mp ->
                try { mp.isLooping = false; player.start(); startSubtitleTicker() }
                catch (_: Exception) { showOpenError("لا يمكن تشغيل هذا الملف.") }
            }
            player.setOnErrorListener { _, _, _ ->
                // Content providers sometimes expose streams VideoView cannot seek; copy once to cache.
                if (uri.scheme == "content" && cachedFile == null) {
                    try {
                        val copy = copyContentUriToCache(uri)
                        cachedFile = copy
                        player.setVideoURI(Uri.fromFile(copy))
                    } catch (error: Exception) {
                        android.util.Log.e("SariMyFiles", "Failed to recover content URI after playback error", error)
                        showOpenError("تعذّر فتح الفيديو. تحقق من صلاحية الملف وأن صيغته مدعومة.")
                    }
                } else {
                    android.util.Log.e("SariMyFiles", "VideoView reported an unsupported or unreadable video")
                    showOpenError("تعذّر تشغيل الفيديو؛ قد يكون تالفًا أو بصيغة غير مدعومة.")
                }
                true
            }
            try { player.setVideoURI(uri) }
            catch (_: Exception) {
                if (uri.scheme == "content") {
                    try { val copy = copyContentUriToCache(uri); cachedFile = copy; player.setVideoURI(Uri.fromFile(copy)) }
                    catch (_: Exception) { showOpenError("لا يمكن فتح هذا الملف.") }
                } else showOpenError("لا يمكن فتح هذا الملف.")
            }
        } catch (error: Exception) {
            android.util.Log.e("SariMyFiles", "Failed to initialize local media player", error)
            showOpenError("تعذّر فتح الفيديو بسبب خطأ أثناء تهيئة المشغّل. جرّب ملف فيديو آخر.")
        }
    }

    private fun copyContentUriToCache(uri: Uri): File {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح مصدر الفيديو")
        val out = File(cacheDir, "sari_video_${System.currentTimeMillis()}.media")
        input.use { source -> out.outputStream().use { target -> source.copyTo(target) } }
        if (!out.isFile || out.length() < 1024L) { out.delete(); throw IOException("ملف فيديو فارغ أو غير مكتمل") }
        return out
    }

    private fun safeSeek(delta: Int) {
        try {
            val v = video ?: return
            val duration = v.duration.coerceAtLeast(0)
            v.seekTo((v.currentPosition + delta).coerceIn(0, if (duration > 0) duration else Int.MAX_VALUE))
        } catch (_: Exception) { Toast.makeText(this, "تعذّر تغيير موضع الفيديو", Toast.LENGTH_SHORT).show() }
    }

    private fun safeToggle() {
        try { val v = video ?: return; if (v.isPlaying) v.pause() else v.start() }
        catch (error: Exception) {
            android.util.Log.w("SariMyFiles", "Play/pause failed", error)
            Toast.makeText(this, "تعذّر تشغيل الفيديو", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSubtitleTicker() {
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val position = video?.currentPosition?.toLong() ?: 0L
                    val cue = cues.firstOrNull { it.contains(position) }
                    subtitleView?.text = cue?.text.orEmpty()
                } catch (_: Exception) { subtitleView?.text = "" }
                if (!isFinishing && !isDestroyed) handler.postDelayed(this, 120L)
            }
        }
        tickRunnable = runnable
        handler.post(runnable)
    }

    private fun showOpenError(message: String) {
        try {
            Toast.makeText(this, message.ifBlank { "لا يمكن فتح هذا الملف" }, Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            android.util.Log.w("SariMyFiles", "Unable to display player error toast", error)
        }
        try {
            if (!isFinishing) finish()
        } catch (error: Exception) {
            android.util.Log.e("SariMyFiles", "Unable to close failed local player", error)
        }
    }

    override fun onDestroy() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        try { video?.stopPlayback() } catch (_: Exception) {}
        try { cachedFile?.delete() } catch (_: Exception) {}
        super.onDestroy()
    }
}

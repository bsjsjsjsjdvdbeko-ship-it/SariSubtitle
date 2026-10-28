package com.sari.subtitle

import com.sari.subtitle.v2.R
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.IOException
import java.util.Locale

/** Local video player with timeline-synchronised SRT subtitles and defensive lifecycle handling. */
class LocalMediaPlayerActivity : Activity() {
    companion object {
        const val EXTRA_SRT = "local_media_srt"
        const val EXTRA_TITLE = "local_media_title"
        private const val TICK_MS = 200L
        private const val MIN_SUBTITLE_SP = 14f
        private const val MAX_SUBTITLE_SP = 32f
        private const val REQUEST_EXPORT_SRT = 5801
        private const val STATE_FULLSCREEN = "local_player_fullscreen"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var video: VideoView? = null
    private var subtitleView: TextView? = null
    private var positionView: TextView? = null
    private var seekBar: SeekBar? = null
    private var cues: List<SubtitleCue> = emptyList()
    private var cachedFile: File? = null
    private var tickRunnable: Runnable? = null
    private var prepared = false
    private var userSeeking = false
    private var subtitleSizeSp = 19f
    private var lastSubtitleText: String? = null
    private lateinit var playbackStore: PlaybackPositionStore
    private var playbackSourceKey: String? = null
    private var resumePromptShown = false
    private var resumeApplied = false
    private var lastPlaybackSaveMs = 0L
    private var loadedSrt = ""
    private var isFullscreen = false
    private lateinit var subtitleSettings: SubtitleSettings
    private var playerRoot: LinearLayout? = null
    private var titleView: View? = null
    private var positionControlsView: View? = null
    private var seekControlsView: View? = null
    private var playbackControlsView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            subtitleSettings = SubtitleSettings(this)
            applySavedOrientation()
            playbackStore = PlaybackPositionStore(this)
            val uri = intent?.data
            if (uri == null || (uri.scheme != "content" && uri.scheme != "file")) {
                showOpenError(getString(R.string.local_player_invalid_uri))
                return
            }
            loadedSrt = intent.getStringExtra(EXTRA_SRT).orEmpty()
            cues = runCatching { SrtParser.parse(loadedSrt.byteInputStream(Charsets.UTF_8)) }.getOrDefault(emptyList())
            playbackSourceKey = playbackStore.keyFor(uri.toString())

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(0xFF101426.toInt())
            }
            titleView = TextView(this).apply {
                text = intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank)
                    ?: getString(R.string.local_player_title)
                textSize = 18f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                setPadding(dp(4), dp(4), dp(4), dp(10))
            }
            root.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val player = VideoView(this)
            video = player
            root.addView(player, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            subtitleView = TextView(this).apply {
                text = if (cues.isEmpty()) getString(R.string.local_player_no_subtitles) else ""
                textSize = subtitleSizeSp
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC171B2D.toInt())
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setHorizontallyScrolling(false)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setShadowLayer(dp(6).toFloat(), 0f, dp(2).toFloat(), 0xFF000000.toInt())
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setLineSpacing(dp(2).toFloat(), 1.05f)
            }
            root.addView(subtitleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })

            positionView = TextView(this).apply {
                text = "00:00 / 00:00"
                textSize = 12f
                setTextColor(0xFFD5D9EA.toInt())
                gravity = Gravity.CENTER
            }
            positionControlsView = positionView
            root.addView(positionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            seekBar = SeekBar(this).apply {
                max = 1000
                isEnabled = false
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar?) { userSeeking = true }
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && prepared) {
                            val duration = safeDuration()
                            if (duration > 0) {
                                val target = (duration.toLong() * progress / 1000L).toInt()
                                positionView?.text = "${formatTime(target)} / ${formatTime(duration)}"
                                renderSubtitle(target.toLong())
                            }
                        }
                    }
                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        val duration = safeDuration()
                        if (duration > 0) {
                            val target = (duration.toLong() * (bar?.progress ?: 0) / 1000L).toInt()
                            runCatching { video?.seekTo(target) }
                        }
                        userSeeking = false
                    }
                })
            }
            seekControlsView = seekBar
            root.addView(seekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }
            controls.addView(controlButton("−10s") { safeSeek(-10_000) })
            controls.addView(controlButton(getString(R.string.local_player_toggle)) { safeToggle() })
            controls.addView(controlButton("+10s") { safeSeek(10_000) })
            controls.addView(controlButton("A−") { changeSubtitleSize(-2f) })
            controls.addView(controlButton("A+") { changeSubtitleSize(2f) })
            controls.addView(controlButton("💾 تصدير") { exportSrt() })
            controls.addView(controlButton("⛶ ملء الشاشة") { toggleFullscreen() })
            controls.addView(controlButton("↻ تدوير") { toggleRotation() })
            val controlsScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                addView(controls, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            playbackControlsView = controlsScroll
            root.addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            playerRoot = root
            setContentView(root)
            if (savedInstanceState?.getBoolean(STATE_FULLSCREEN, false) == true) {
                toggleFullscreen()
            }

            player.setMediaController(MediaController(this))
            player.setOnPreparedListener { mediaPlayer ->
                try {
                    prepared = true
                    mediaPlayer.isLooping = false
                    seekBar?.isEnabled = true
                    maybeOfferResume(player, uri.toString())
                    if (!resumeApplied) {
                        player.start()
                    }
                    startSubtitleTicker()
                } catch (error: Exception) {
                    android.util.Log.e("SariMyFiles", "Player start failed", error)
                    showOpenError(getString(R.string.local_player_play_failed))
                }
            }
            player.setOnCompletionListener {
                playbackSourceKey?.let(playbackStore::clearByKey)
                stopSubtitleTicker()
                renderSubtitle(Long.MAX_VALUE)
                updateTimeline()
            }
            player.setOnErrorListener { _, what, extra ->
                android.util.Log.w("SariMyFiles", "Video playback error what=$what extra=$extra")
                // Some document providers expose streams that MediaPlayer cannot read directly.
                if (uri.scheme == "content" && cachedFile == null) {
                    try {
                        val copy = copyContentUriToCache(uri)
                        cachedFile = copy
                        prepared = false
                        player.setVideoURI(Uri.fromFile(copy))
                    } catch (error: Exception) {
                        android.util.Log.e("SariMyFiles", "Failed to recover content URI", error)
                        showOpenError(getString(R.string.local_player_video_failed))
                    }
                } else {
                    showOpenError(getString(R.string.local_player_unsupported))
                }
                true
            }
            player.setVideoURI(uri)
        } catch (error: Exception) {
            android.util.Log.e("SariMyFiles", "Failed to initialize local media player", error)
            showOpenError(getString(R.string.local_player_init_failed))
        }
    }

    private fun maybeOfferResume(player: VideoView, source: String) {
        if (resumePromptShown || resumeApplied) return
        val record = playbackStore.get(source) ?: run {
            resumeApplied = true
            return
        }
        val duration = runCatching { player.duration.toLong() }.getOrDefault(0L)
        val position = record.positionMs
        if (position < 5_000L || (duration > 0L && position >= duration - 5_000L)) {
            playbackStore.clear(source)
            resumeApplied = true
            return
        }
        resumePromptShown = true
        val message = "آخر مشاهدة: ${formatTime(position.toInt())}"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("استئناف التشغيل")
            .setMessage("تم حفظ موضع هذا الفيديو عند $message. هل تريد المتابعة من هناك؟")
            .setNegativeButton("بدء من البداية") { _, _ ->
                playbackStore.clear(source)
                resumeApplied = true
                runCatching { player.seekTo(0) }
                player.start()
                renderSubtitle(0L)
                updateTimeline(0)
                startSubtitleTicker()
            }
            .setPositiveButton("استئناف") { _, _ ->
                resumeApplied = true
                val target = if (duration > 0L) position.coerceIn(0L, (duration - 1000L).coerceAtLeast(0L)) else position
                runCatching { player.seekTo(target.toInt()) }
                renderSubtitle(target)
                updateTimeline(target.toInt())
                player.start()
                startSubtitleTicker()
            }
            .setOnCancelListener {
                resumeApplied = true
                player.start()
                startSubtitleTicker()
            }
            .show()
    }

    private fun savePlaybackPosition() {
        if (!::playbackStore.isInitialized) return
        val sourceKey = playbackSourceKey ?: return
        val position = runCatching { video?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        val duration = runCatching { video?.duration?.toLong() ?: 0L }.getOrDefault(0L)
        playbackStore.saveByKey(sourceKey, position, duration)
    }

    private fun exportSrt() {
        if (loadedSrt.isBlank()) {
            Toast.makeText(this, "لا توجد ترجمة SRT لتصديرها", Toast.LENGTH_SHORT).show()
            return
        }
        val rawTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank) ?: "sari_subtitle"
        val safeTitle = rawTitle.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "sari_subtitle" }
        val filename = if (safeTitle.lowercase(Locale.ROOT).endsWith(".srt")) safeTitle else "$safeTitle.srt"
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-subrip"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        try {
            startActivityForResult(create, REQUEST_EXPORT_SRT)
        } catch (error: Exception) {
            android.util.Log.e("SariMyFiles", "Unable to open SRT export picker", error)
            Toast.makeText(this, "تعذر فتح نافذة حفظ ملف SRT", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_SRT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(loadedSrt.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw IOException("تعذر فتح ملف الحفظ")
            Toast.makeText(this, "تم تصدير ملف الترجمة بنجاح", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            android.util.Log.e("SariMyFiles", "SRT export failed", error)
            Toast.makeText(this, "تعذر تصدير ملف SRT", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val visibility = if (isFullscreen) View.GONE else View.VISIBLE
        titleView?.visibility = visibility
        positionControlsView?.visibility = visibility
        seekControlsView?.visibility = visibility
        playbackControlsView?.visibility = visibility
        playerRoot?.setPadding(
            if (isFullscreen) 0 else dp(12),
            if (isFullscreen) 0 else dp(12),
            if (isFullscreen) 0 else dp(12),
            if (isFullscreen) 0 else dp(12)
        )
        if (isFullscreen) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        if (!isFullscreen) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun toggleRotation() {
        val nextLandscape = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        subtitleSettings.preferredLandscape = nextLandscape
        requestedOrientation = if (nextLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun applySavedOrientation() {
        requestedOrientation = if (subtitleSettings.preferredLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun exitFullscreenIfNeeded(): Boolean {
        if (!isFullscreen) return false
        isFullscreen = false
        titleView?.visibility = View.VISIBLE
        positionControlsView?.visibility = View.VISIBLE
        seekControlsView?.visibility = View.VISIBLE
        playbackControlsView?.visibility = View.VISIBLE
        playerRoot?.setPadding(dp(12), dp(12), dp(12), dp(12))
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        return true
    }

    @Deprecated("Deprecated in Android 13; retained for minSdk compatibility")
    override fun onBackPressed() {
        if (exitFullscreenIfNeeded()) return
        super.onBackPressed()
    }

    private fun controlButton(label: String, action: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF1A2140.toInt())
        setPadding(dp(10), 0, dp(10), 0)
        minHeight = dp(44)
        minWidth = 0
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(44)
        ).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun copyContentUriToCache(uri: Uri): File {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح مصدر الفيديو")
        val out = File(cacheDir, "sari_video_${System.currentTimeMillis()}.media")
        input.use { source -> out.outputStream().use { target -> source.copyTo(target) } }
        if (!out.isFile || out.length() < 1024L) {
            out.delete()
            throw IOException("ملف فيديو فارغ أو غير مكتمل")
        }
        return out
    }

    private fun safeDuration(): Int = runCatching { video?.duration ?: 0 }.getOrDefault(0).coerceAtLeast(0)

    private fun safeSeek(delta: Int) {
        try {
            val v = video ?: return
            if (!prepared) return
            val duration = safeDuration()
            val current = v.currentPosition.coerceAtLeast(0)
            val target = (current.toLong() + delta).coerceIn(0L, if (duration > 0) duration.toLong() else Int.MAX_VALUE.toLong()).toInt()
            v.seekTo(target)
            renderSubtitle(target.toLong())
            updateTimeline()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.local_player_seek_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun safeToggle() {
        try {
            val v = video ?: return
            if (!prepared) return
            if (v.isPlaying) v.pause() else v.start()
            if (v.isPlaying) startSubtitleTicker() else stopSubtitleTicker()
        } catch (error: Exception) {
            android.util.Log.w("SariMyFiles", "Play/pause failed", error)
            Toast.makeText(this, getString(R.string.local_player_toggle_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun changeSubtitleSize(delta: Float) {
        subtitleSizeSp = (subtitleSizeSp + delta).coerceIn(MIN_SUBTITLE_SP, MAX_SUBTITLE_SP)
        subtitleView?.textSize = subtitleSizeSp
    }

    private fun startSubtitleTicker() {
        stopSubtitleTicker()
        val runnable = object : Runnable {
            override fun run() {
                val v = video
                if (v == null || isFinishing || isDestroyed || !prepared) return
                try {
                    val position = v.currentPosition.coerceAtLeast(0)
                    renderSubtitle(position.toLong())
                    updateTimeline(position)
                    val now = System.currentTimeMillis()
                    if (now - lastPlaybackSaveMs >= 3_000L) {
                        savePlaybackPosition()
                        lastPlaybackSaveMs = now
                    }
                } catch (error: Exception) {
                    android.util.Log.w("SariMyFiles", "Subtitle sync tick failed", error)
                }
                if (v.isPlaying && !isFinishing && !isDestroyed) handler.postDelayed(this, TICK_MS)
            }
        }
        tickRunnable = runnable
        handler.post(runnable)
    }

    private fun stopSubtitleTicker() {
        tickRunnable?.let(handler::removeCallbacks)
        tickRunnable = null
    }

    private fun renderSubtitle(positionMs: Long) {
        val view = subtitleView ?: return
        val cue = SubtitleCueTimeline.findAt(cues, positionMs)
        val text = cue?.text.orEmpty().ifEmpty {
            if (cues.isEmpty()) getString(R.string.local_player_no_subtitles) else ""
        }
        if (text != lastSubtitleText) {
            view.text = text
            lastSubtitleText = text
        }
    }

    private fun updateTimeline(positionOverride: Int? = null) {
        val duration = safeDuration()
        val position = (positionOverride ?: runCatching { video?.currentPosition ?: 0 }.getOrDefault(0)).coerceAtLeast(0)
        positionView?.text = "${formatTime(position)} / ${formatTime(duration)}"
        if (!userSeeking && duration > 0) {
            seekBar?.progress = ((position.toLong() * 1000L) / duration).toInt().coerceIn(0, 1000)
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FULLSCREEN, isFullscreen)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        savePlaybackPosition()
        try {
            if (video?.isPlaying == true) video?.pause()
        } catch (_: Exception) {}
        stopSubtitleTicker()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (prepared && video?.isPlaying == true) startSubtitleTicker()
    }

    override fun onDestroy() {
        savePlaybackPosition()
        stopSubtitleTicker()
        try { video?.stopPlayback() } catch (_: Exception) {}
        try { cachedFile?.delete() } catch (_: Exception) {}
        super.onDestroy()
    }
}

package com.sari.subtitle

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var urlInput: EditText
    private lateinit var subtitleInput: EditText
    private lateinit var drmLicenseInput: EditText
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private val subtitleHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, AudioCaptureService::class.java)
                .setAction(AudioCaptureService.ACTION_START)
                .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            ContextCompat.startForegroundService(this, intent)
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
            status.text = getString(R.string.player_translation_running)
        } else status.text = getString(R.string.capture_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        urlInput = findViewById(R.id.videoUrlInput)
        subtitleInput = findViewById(R.id.subtitleUrlInput)
        drmLicenseInput = findViewById(R.id.drmLicenseUrlInput)
        status = findViewById(R.id.videoPlayerStatus)
        playerView = findViewById(R.id.videoPlayerView)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.playerBack)
            .setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.playUrlButton).setOnClickListener { playOrRouteUrl() }
        findViewById<MaterialButton>(R.id.loadSubtitleButton).setOnClickListener { playOrRouteUrl() }
        findViewById<MaterialButton>(R.id.startPlayerTranslationButton).setOnClickListener { requestTranslation() }
        findViewById<MaterialButton>(R.id.openSourceButton).setOnClickListener { openSource() }
        urlInput.setText(intent.getStringExtra(EXTRA_URL).orEmpty())
        createPlayer()
        lifecycleScope.launch {
            LiveSubtitleBus.current.collect { item ->
                val view = findViewById<TextView>(R.id.liveSubtitleText)
                subtitleHandler.removeCallbacksAndMessages(null)
                if (item == null || item.translated.isBlank()) {
                    view.visibility = android.view.View.GONE
                } else {
                    view.text = item.translated
                    view.visibility = android.view.View.VISIBLE
                    status.text = "Live subtitle • ${item.latencyMs} ms pipeline latency"
                    subtitleHandler.postDelayed({ view.visibility = android.view.View.GONE }, item.estimatedDurationMs)
                }
            }
        }
        if (urlInput.text.isNotBlank()) playOrRouteUrl()
    }

    private fun createPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val drmConfigured = player?.currentMediaItem?.localConfiguration?.drmConfiguration != null
                    status.text = if (drmConfigured) {
                        getString(R.string.player_drm_error, error.errorCodeName)
                    } else {
                        getString(R.string.player_error, error.errorCodeName)
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) status.text = getString(R.string.player_ready)
                }
            })
        }
    }

    private fun playOrRouteUrl() {
        val raw = urlInput.text.toString().trim()
        val resolved = VideoPlatformResolver.resolve(raw)
        if (resolved == null) {
            urlInput.error = getString(R.string.player_invalid_url)
            return
        }
        if (!resolved.isDirectMedia) {
            status.text = getString(R.string.player_platform_page, VideoPlatformResolver.displayName(resolved.platform))
            startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, resolved.uri.toString()))
            return
        }
        val mediaUri = resolved.uri
        val subtitle = subtitleConfiguration()
        val drmLicenseUri = drmLicenseUriOrNull()
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(mediaUri)
            .setMimeType(VideoPlatformResolver.mimeTypeFor(resolved))

        if (subtitle != null) {
            mediaItemBuilder.setSubtitleConfigurations(listOf(subtitle))
        }

        if (drmLicenseUri != null) {
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(drmLicenseUri)
                    .setMultiSession(false)
                    .build()
            )
            status.text = getString(R.string.player_drm_configured)
        }

        val mediaItem = mediaItemBuilder.build()
        try {
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.playWhenReady = true
            if (drmLicenseUri == null) {
                status.text = if (subtitle != null) getString(R.string.player_subtitle_loaded) else getString(R.string.player_loading)
            }
        } catch (e: Exception) {
            status.text = getString(R.string.player_error, e.message ?: "unknown")
        }
    }

    private fun subtitleConfiguration(): MediaItem.SubtitleConfiguration? {
        val raw = subtitleInput.text.toString().trim()
        if (raw.isBlank()) return null
        val uri = VideoPlatformResolver.resolve(raw)?.uri ?: return null
        val ext = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            else -> return null
        }
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("ar")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private fun drmLicenseUriOrNull(): Uri? {
        val raw = drmLicenseInput.text.toString().trim()
        if (raw.isBlank()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri?.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) {
            drmLicenseInput.error = getString(R.string.player_drm_https_required)
            return null
        }
        return uri
    }

    private fun requestTranslation() {
        if (player?.currentMediaItem == null) {
            Toast.makeText(this, R.string.player_load_first, Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openSource() {
        val raw = urlInput.text.toString().trim()
        if (VideoPlatformResolver.resolve(raw) != null)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
    }

    override fun onDestroy() {
        subtitleHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "video_url" }
}

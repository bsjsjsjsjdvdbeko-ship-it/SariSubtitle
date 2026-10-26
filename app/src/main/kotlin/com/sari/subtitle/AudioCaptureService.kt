package com.sari.subtitle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.AudioPlaybackCaptureConfiguration
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.io.File

/**
 * Captures eligible device playback via MediaProjection and transcribes each overlapping WAV
 * chunk on a background coroutine. Android only permits playback capture for apps/usages that
 * allow capture; this does not bypass DRM or other apps' capture restrictions.
 */
class AudioCaptureService : Service() {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var captureThread: Thread? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var chunker: AudioChunker? = null
    private var translationWorker: kotlinx.coroutines.Job? = null
    private val chunkQueue = Channel<AudioChunk>(capacity = 1)

    override fun onCreate() {
        super.onCreate()
        val channelId = "sari_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Audio capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData == null || resultCode == 0) {
                    Log.e(TAG, "Missing MediaProjection consent result.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!running) startCapture(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val channelId = "sari_capture"
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setContentTitle("Sari Subtitle")
            .setContentText("Capturing eligible device audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val mediaProjection = projection ?: error("MediaProjection could not be created.")
            if (Build.VERSION.SDK_INT >= 29) {
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection was revoked or stopped.")
                        stopCapture()
                    }
                }, android.os.Handler(mainLooper))
            }
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "AudioRecord reported an invalid buffer size: $minBuffer" }
            val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE / 2)
            val audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed." }
            recorder = audioRecord
            chunker = AudioChunker(this)
            TranslationRepository(applicationContext).resetEpisodeContext()
            running = true
            translationWorker?.cancel()
            translationWorker = serviceScope.launch {
                for (chunk in chunkQueue) {
                    try {
                        TranslationRepository(applicationContext).translateAudio(
                            chunk.file, chunk.startElapsedMs, chunk.durationMs
                        )
                    } catch (e: Exception) {
                        if (e !is kotlinx.coroutines.CancellationException) {
                            Log.e(TAG, "Chunk transcription/translation failed", e)
                            startService(Intent(applicationContext, OverlayService::class.java)
                                .setAction(OverlayService.ACTION_UPDATE)
                                .putExtra(OverlayService.EXTRA_ORIGINAL, "Subtitle error")
                                .putExtra(OverlayService.EXTRA_TRANSLATED, e.message ?: "Translation failed"))
                        }
                    } finally {
                        chunk.file.delete()
                    }
                }
            }
            audioRecord.startRecording()
            captureThread = Thread({ captureLoop(audioRecord) }, "SariAudioCapture").apply { start() }
            Log.i(TAG, "Device playback capture started.")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start audio capture", e)
            stopCapture()
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_UPDATE)
                .putExtra(OverlayService.EXTRA_ORIGINAL, "Audio capture failed")
                .putExtra(OverlayService.EXTRA_TRANSLATED, e.message ?: "Check permissions and audio source"))
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val samples = ShortArray(4096)
        var lastSpeechAt = System.currentTimeMillis()
        var overlayHiddenForSilence = false
        while (running && !Thread.currentThread().isInterrupted) {
            val count = try { audioRecord.read(samples, 0, samples.size) } catch (e: Exception) {
                Log.e(TAG, "Audio read failed", e); break
            }
            if (count > 0) {
                val speechPresent = isSpeech(samples, count)
                if (speechPresent) {
                    lastSpeechAt = System.currentTimeMillis()
                    overlayHiddenForSilence = false
                } else if (!overlayHiddenForSilence && System.currentTimeMillis() - lastSpeechAt >= 3_000L) {
                    overlayHiddenForSilence = true
                    startService(Intent(applicationContext, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE))
                }
                chunker?.addSamples(samples, count)
                while (running && chunker?.hasChunkReady() == true) {
                    val chunk = try { chunker?.getNextChunkInfo() } catch (_: Exception) { null } ?: break
                    LiveSubtitleBus.markLatestCapture(chunk.startElapsedMs)
                    if (!chunkQueue.trySend(chunk).isSuccess) {
                        // Keep latency bounded: discard the older queued window and keep the newest one.
                        chunkQueue.tryReceive().getOrNull()?.file?.delete()
                        if (!chunkQueue.trySend(chunk).isSuccess) chunk.file.delete()
                    }
                }
            } else if (count < 0) {
                Log.e(TAG, "AudioRecord.read returned $count")
                break
            }
        }
        if (running) stopCapture()
    }

    fun isSpeech(samples: ShortArray, count: Int, threshold: Double = 700.0): Boolean {
        if (count <= 0) return false
        var sum = 0.0
        for (i in 0 until count) { val v = samples[i].toDouble(); sum += v * v }
        return kotlin.math.sqrt(sum / count) >= threshold
    }

    private fun stopCapture() {
        running = false
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        captureThread?.interrupt()
        captureThread = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        chunker?.clear()
        chunker = null
        while (true) { val pending = chunkQueue.tryReceive().getOrNull() ?: break; pending.file.delete() }
        translationWorker?.cancel()
        translationWorker = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        translationWorker?.cancel()
        while (true) { val pending = chunkQueue.tryReceive().getOrNull() ?: break; pending.file.delete() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val TAG = "SariAudioCapture"
        private const val NOTIFICATION_ID = 2
        private const val SAMPLE_RATE = 16_000
        const val ACTION_START = "com.sari.subtitle.action.START_CAPTURE"
        const val ACTION_STOP = "com.sari.subtitle.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "media_projection_result_code"
        const val EXTRA_RESULT_DATA = "media_projection_result_data"
    }
}

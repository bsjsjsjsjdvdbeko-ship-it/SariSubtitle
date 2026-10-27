package com.sari.subtitle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 42: real Android playback-audio capture pipeline.
 *
 * MediaProjection consent -> AudioPlaybackCapture -> AudioRecord PCM16 ->
 * AudioChunker -> LiveSttRouter.
 *
 * The service owns capture/lifecycle only. STT routing remains in LiveSttRouter.
 */
class LiveCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.sari.subtitle.action.START_LIVE_CAPTURE"
        const val ACTION_STOP = "com.sari.subtitle.action.STOP_LIVE_CAPTURE"

        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_STT_PROVIDER = "stt_provider"

        private const val CHANNEL_ID = "sari_live_capture"
        private const val NOTIFICATION_ID = 41042
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val CHUNK_MS = 2_000L
        private const val OVERLAP_MS = 250L
        private const val QUEUE_CAPACITY = 3

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, resultCode: Int, resultData: Intent, language: String, provider: String) {
            val intent = Intent(context, LiveCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_STT_PROVIDER, provider)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveCaptureService::class.java))
        }
    }

    private data class Work(val chunk: AudioChunker.Chunk, val sessionId: Long)

    private val running = AtomicBoolean(false)
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Sari-LiveCapture").apply { isDaemon = true }
    }
    private val sttExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Sari-LiveSTT").apply { isDaemon = true }
    }
    private val translationExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(1),
        { r -> Thread(r, "Sari-LiveTranslation").apply { isDaemon = true } }
    )
    private val queue = ArrayBlockingQueue<Work>(QUEUE_CAPACITY)
    private val chunker = AudioChunker(SAMPLE_RATE, CHANNELS, CHUNK_MS, OVERLAP_MS)
    private lateinit var router: LiveSttRouter
    private lateinit var translationRouter: LiveTranslationRouter
    private val deduplicator = LiveCueDeduplicator()
    private val stabilizer = LiveTranscriptStabilizer()
    private val translationCoordinator = LiveTranslationCoordinator(maxPending = 1)
    private val translationContext = LiveTranslationContextBuffer(maxItems = 3)
    private val subtitleTimeline = LiveSubtitleTimelineManager()
    private val latencyController = AdaptiveLatencyController()
    private val sessionCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private var sessionId = 0L
    private var highestEmittedSequence = Long.MIN_VALUE
    private var captureStartedElapsedMs = 0L
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var streamingStt: DeepgramStreamingSttSession? = null
    @Volatile private var useStreamingStt = false
    private var workerLanguage = "auto"
    private var workerProvider = SubtitleSettings.STT_AUTO
    private var workerStarted = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            publishStatus("capture_stopped", "تم إيقاف MediaProjection من النظام أو المستخدم")
            stopCaptureInternal()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        router = LiveSttRouter(applicationContext)
        translationRouter = LiveTranslationRouter(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running.get()) return START_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = intent.parcelableExtraCompat(EXTRA_RESULT_DATA)
                workerLanguage = intent.getStringExtra(EXTRA_LANGUAGE)?.trim().orEmpty().ifBlank { "auto" }
                workerProvider = intent.getStringExtra(EXTRA_STT_PROVIDER).orEmpty().ifBlank { SubtitleSettings.STT_AUTO }

                if (resultCode == Int.MIN_VALUE || resultData == null) {
                    publishStatus("capture_error", "بيانات MediaProjection غير مكتملة")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Android 14+ requires the mediaProjection foreground-service type.
                try {
                    val notification = buildNotification("جارٍ التقاط صوت الوسائط…")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    startCapture(resultCode, resultData)
                } catch (t: Throwable) {
                    publishStatus("capture_error", sanitize(t.message ?: t.javaClass.simpleName))
                    stopCaptureInternal()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (!running.compareAndSet(false, true)) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("AudioPlaybackCapture requires Android 10+")
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
            ?: throw IllegalStateException("MediaProjectionManager unavailable")
        val projection = manager.getMediaProjection(resultCode, resultData)
            ?: throw SecurityException("MediaProjection permission was not granted")
        mediaProjection = projection
        isRunning = true
        sessionId = sessionCounter.incrementAndGet()
        highestEmittedSequence = Long.MIN_VALUE
        captureStartedElapsedMs = SystemClock.elapsedRealtime()
        deduplicator.clear()
        stabilizer.clear()
        translationCoordinator.beginSession(sessionId)
        translationContext.beginSession(sessionId)
        subtitleTimeline.beginSession(sessionId)
        latencyController.reset()
        highestEmittedSequence = Long.MIN_VALUE
        projection.registerCallback(projectionCallback, android.os.Handler(mainLooper))

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("AudioRecord.getMinBufferSize failed: $minBuffer")

        val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * 2 * 2)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            audioRecord = null
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        useStreamingStt = shouldUseDeepgramStreaming()
        if (useStreamingStt) {
            startDeepgramStreaming()
        } else {
            startSttWorker()
        }
        record.startRecording()
        publishStatus("capture_started", "AudioPlaybackCapture + AudioRecord يعملان الآن")

        captureExecutor.execute {
            captureLoop(record)
        }
    }

    private fun captureLoop(record: AudioRecord) {
        val readBuffer = ByteArray(SAMPLE_RATE * 2 / 2) // ~500 ms PCM16
        try {
            while (running.get()) {
                val read = record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    if (useStreamingStt && streamingStt?.isActive() == true) {
                        val sent = streamingStt?.sendPcm(readBuffer, read) == true
                        if (!sent && running.get()) {
                            // WebSocket failed between callbacks; switch to the existing
                            // bounded chunk/REST pipeline without stopping AudioRecord.
                            useStreamingStt = false
                            startSttWorker()
                            publishStatus("streaming_fallback", "تعذر إرسال الصوت إلى Deepgram؛ تم التحويل إلى مسار STT الاحتياطي")
                        }
                    } else {
                        val chunks = chunker.append(readBuffer, read)
                        for (chunk in chunks) enqueue(chunk)
                    }
                } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                    publishStatus("capture_error", "AudioRecord فقد الاتصال بمصدر الصوت")
                    break
                } else if (read < 0) {
                    publishStatus("capture_error", "AudioRecord read error=$read")
                    break
                }
            }
        } catch (t: Throwable) {
            if (running.get()) publishStatus("capture_error", sanitize(t.message ?: t.javaClass.simpleName))
        } finally {
            val tail = chunker.flush()
            if (tail != null && running.get()) enqueue(tail)
            if (running.get()) {
                running.set(false)
                publishStatus("capture_stopped", "انتهى التقاط الصوت")
                stopSelf()
            }
        }
    }

    private fun enqueue(chunk: AudioChunker.Chunk) {
        val work = Work(chunk, sessionId)
        if (queue.offer(work)) return
        // Real-time priority: never build an unbounded backlog. Drop the oldest chunk
        // so the newest audio remains close to real time.
        queue.poll()
        queue.offer(work)
        publishStatus("backpressure", "تم إسقاط chunk قديم للحفاظ على زمن الاستجابة")
    }

    private fun shouldUseDeepgramStreaming(): Boolean {
        val settings = SubtitleSettings(applicationContext)
        val keyReady = settings.deepgramApiKey.isNotBlank()
        if (!keyReady) return false

        return when (workerProvider) {
            SubtitleSettings.STT_DEEPGRAM -> true
            SubtitleSettings.STT_AUTO -> {
                // Capability-aware routing: Stage 45 has a real WebSocket implementation
                // for Deepgram. Groq remains in the existing REST/chunk fallback path; it
                // is not falsely advertised as a streaming provider.
                val profile = AdaptiveContextStore.get(applicationContext)
                val snapshot = AdaptiveRoutingEngine.snapshot(
                    applicationContext,
                    ProviderScoringEngine.Task.STT,
                    sourceLanguage = workerLanguage,
                    chunkDurationMs = profile.typicalChunkDurationMs,
                    speechSpeedWpm = profile.speechSpeedWpm
                )
                ProviderRoutingEngine.choose(
                    applicationContext,
                    providers = listOf(ProviderConnectionTester.DEEPGRAM),
                    configured = { it == ProviderConnectionTester.DEEPGRAM && settings.deepgramApiKey.isNotBlank() },
                    task = ProviderScoringEngine.Task.STT,
                    adaptiveContext = snapshot
                ).firstOrNull()?.id == ProviderConnectionTester.DEEPGRAM
            }
            else -> false
        }
    }

    private fun startDeepgramStreaming() {
        val settings = SubtitleSettings(applicationContext)
        if (settings.deepgramApiKey.isBlank()) {
            useStreamingStt = false
            startSttWorker()
            return
        }

        val session = DeepgramStreamingSttSession(
            apiKeyProvider = { SubtitleSettings(applicationContext).deepgramApiKey },
            sourceLanguage = workerLanguage,
            onEvent = { event -> onStreamingTranscript(event, sessionId) },
            onStatus = { status, detail -> publishStatus(status, detail) },
            onFailure = { error ->
                useStreamingStt = false
                startSttWorker()
                try {
                    // Failure is real, but no request latency is fabricated here.
                    // The provider health store already keeps the last measured latency
                    // from its dedicated connection test.
                    ProviderRoutingEngine.recordFailure(
                        applicationContext,
                        ProviderConnectionTester.DEEPGRAM,
                        error,
                        0L
                    )
                } catch (_: Throwable) {}
            }
        )
        streamingStt = session
        if (!session.start()) {
            streamingStt = null
            useStreamingStt = false
            startSttWorker()
        }
    }

    private fun onStreamingTranscript(event: LiveTranscriptStabilizer.Event, workSessionId: Long) {
        if (workSessionId != sessionId || !running.get()) return
        try {
            val decision = stabilizer.accept(event)
            if (decision.action == LiveTranscriptStabilizer.Action.DROP) return
            val stable = decision.event ?: return
            val cue = LiveSttRouter.LiveCue(
                sequence = stable.sequence,
                startMs = stable.startMs,
                endMs = stable.endMs,
                text = stable.text,
                language = stable.language,
                provider = SubtitleSettings.STT_DEEPGRAM,
                isFinal = stable.kind == LiveTranscriptStabilizer.Kind.FINAL,
                revision = stable.revision
            )

            if (!cue.isFinal) {
                publishCue(cue, cue.language)
                return
            }

            if (cue.text.isBlank()) return
            if (cue.sequence < highestEmittedSequence) return
            highestEmittedSequence = cue.sequence
            recordSttQuality(cue)
            submitTranslation(cue, workSessionId)
        } catch (t: Throwable) {
            publishStatus("streaming_event_error", "تعذر تثبيت نتيجة Deepgram الحية: ${sanitize(t.message ?: t.javaClass.simpleName)}")
        }
    }

    private fun startSttWorker() {
        if (workerStarted) return
        workerStarted = true
        sttExecutor.execute {
            while (running.get() || queue.isNotEmpty()) {
                val work = try {
                    queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                if (work.sessionId == sessionId) processWork(work.chunk, work.sessionId)
            }
        }
    }

    private fun processWork(chunk: AudioChunker.Chunk, workSessionId: Long) {
        if (workSessionId != sessionId || !running.get()) return
        try {
            val result = router.processChunk(
                chunk,
                LiveSttRouter.Config(
                    language = workerLanguage,
                    selectedProvider = workerProvider,
                    sampleRateHz = SAMPLE_RATE,
                    channels = CHANNELS
                )
            )
            if (result.droppedSilenceChunks > 0) {
                publishStatus("silence", "تم تجاهل chunk صامت")
            }
            for (cue in result.cues.sortedWith(compareBy<LiveSttRouter.LiveCue> { it.startMs }.thenBy { it.sequence })) {
                if (workSessionId != sessionId || !running.get()) return
                val decision = stabilizer.accept(
                    LiveTranscriptStabilizer.Event(
                        sequence = cue.sequence,
                        startMs = cue.startMs,
                        endMs = cue.endMs,
                        text = cue.text,
                        language = cue.language,
                        provider = cue.provider,
                        kind = if (cue.isFinal) LiveTranscriptStabilizer.Kind.FINAL else LiveTranscriptStabilizer.Kind.PARTIAL,
                        revision = cue.revision
                    )
                )
                if (decision.action == LiveTranscriptStabilizer.Action.DROP) {
                    publishStatus("late_or_duplicate_suppressed", "تم تجاهل نتيجة live قديمة أو مكررة")
                    continue
                }
                val stable = decision.event ?: continue
                if (!stable.kind.equals(LiveTranscriptStabilizer.Kind.FINAL)) {
                    publishCue(cue.copy(text = stable.text), stable.language)
                    continue
                }
                if (!deduplicator.shouldEmit(stable.text, stable.startMs)) {
                    publishStatus("duplicate_suppressed", "تم منع تكرار ناتج عن overlap في chunks")
                    continue
                }
                if (stable.sequence < highestEmittedSequence) {
                    publishStatus("late_result_suppressed", "تم تجاهل نتيجة STT وصلت بعد نتيجة أحدث")
                    continue
                }
                highestEmittedSequence = stable.sequence
                val finalCue = cue.copy(text = stable.text, isFinal = true, revision = stable.revision)
                recordSttQuality(finalCue)
                submitTranslation(finalCue, workSessionId)
            }
            if (result.providerFailures > 0) {
                publishStatus("provider_error", "فشل STT لهذا chunk وتمت معالجة الخطأ دون إيقاف الالتقاط")
            }
            if (!useStreamingStt) {
                val recommended = latencyController.recommendedChunkDurationMs()
                if (recommended != CHUNK_MS) {
                    chunker.reconfigure(recommended, minOf(OVERLAP_MS, recommended / 8L))
                    publishStatus("latency_adapted", "تم ضبط حجم chunk إلى ${recommended}ms بناءً على قياس التأخير الفعلي")
                }
            }
        } catch (t: Throwable) {
            publishStatus("provider_error", "فشل معالجة chunk: ${sanitize(t.message ?: t.javaClass.simpleName)}")
        }
    }


    private fun submitTranslation(cue: LiveSttRouter.LiveCue, workSessionId: Long) {
        if (!running.get() || workSessionId != sessionId) return
        val request = LiveTranslationCoordinator.Request(
            sessionId = workSessionId,
            cue = cue,
            submittedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        val accepted = translationCoordinator.executeLatest(translationExecutor, request) {
            if (workSessionId == sessionId && running.get()) {
                processTranslation(cue, workSessionId, request.submittedAtElapsedMs)
            }
        }
        if (!accepted) {
            publishStatus("translation_backpressure", "تم تجاوز طلب ترجمة قديم للحفاظ على التأخير المنخفض")
        }
    }

    private fun processTranslation(cue: LiveSttRouter.LiveCue, workSessionId: Long, submittedAtElapsedMs: Long) {
        if (workSessionId != sessionId || !running.get()) return
        try {
            val contextSnapshot = translationContext.snapshot(workSessionId)
            val translated = translationRouter.translate(
                text = cue.text,
                sourceLanguage = cue.language,
                targetLanguage = "ar",
                history = contextSnapshot
            )
            val translatedCue = cue.copy(
                text = translated.text,
                language = translated.targetLanguage,
                provider = "${cue.provider}→${translated.provider}"
            )
            recordTranslationQuality(
                provider = translated.provider,
                source = cue.text,
                translated = translated.text
            )
            if (workSessionId != sessionId || !running.get()) return
            val publishGuard = translationCoordinator.acceptForPublish(workSessionId, translatedCue)
            if (!publishGuard.accepted) {
                publishStatus("translation_stale", "تم تجاهل ترجمة وصلت متأخرة: ${publishGuard.reason}")
                return
            }
            publishCue(translatedCue, translated.sourceLanguage, translated.provider)
            translationContext.add(
                workSessionId,
                LiveTranslationContextBuffer.Item(
                    sequence = cue.sequence,
                    sourceLanguage = translated.sourceLanguage,
                    sourceText = cue.text,
                    translatedText = translated.text
                )
            )
            val endToEndMs = (SystemClock.elapsedRealtime() - submittedAtElapsedMs).coerceAtLeast(0L)
            latencyController.recordEndToEnd(endToEndMs)
            publishStatus(
                "translation_success",
                "تمت ترجمة cue حي: ${translated.provider}; provider=${translated.latencyMs}ms; endToEnd=${endToEndMs}ms; target=${latencyController.snapshot().recommendedChunkMs}ms"
            )
        } catch (t: Throwable) {
            publishStatus("translation_error", "تعذرت ترجمة cue حي: ${sanitize(t.message ?: t.javaClass.simpleName)}")
        }
    }

    private fun recordSttQuality(cue: LiveSttRouter.LiveCue) {
        val provider = when (cue.provider) {
            SubtitleSettings.STT_DEEPGRAM -> ProviderConnectionTester.DEEPGRAM
            SubtitleSettings.STT_GROQ -> ProviderConnectionTester.GROQ
            SubtitleSettings.STT_OPENAI -> ProviderConnectionTester.OPENAI
            else -> null
        } ?: return
        runCatching {
            val signal = LiveQualitySignalEngine.scoreStt(cue.text, cue.language)
            if (signal.measured) {
                ProviderMetricsStore.recordQuality(applicationContext, provider, signal.score)
            }
            if (signal.reasons.isNotEmpty()) {
                publishStatus(
                    "stt_quality_signal",
                    "${cue.provider}: score=${signal.score}; signals=${signal.reasons.joinToString(",")}"
                )
            }
        }
    }

    private fun recordTranslationQuality(provider: String, source: String, translated: String) {
        val providerId = when (provider) {
            ProviderConnectionTester.OMNIROUTE -> ProviderConnectionTester.OMNIROUTE
            ProviderConnectionTester.GEMINI -> ProviderConnectionTester.GEMINI
            else -> null
        } ?: return
        runCatching {
            val signal = LiveQualitySignalEngine.scoreTranslation(source, translated, "ar")
            if (signal.measured) {
                ProviderMetricsStore.recordQuality(applicationContext, providerId, signal.score)
            }
            if (signal.reasons.isNotEmpty()) {
                publishStatus(
                    "translation_quality_signal",
                    "$provider: score=${signal.score}; signals=${signal.reasons.joinToString(",")}"
                )
            }
        }
    }

    private fun stopCaptureInternal() {
        if (!running.getAndSet(false) && audioRecord == null && mediaProjection == null) return
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { streamingStt?.stop() } catch (_: Throwable) {}
        streamingStt = null
        useStreamingStt = false
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        chunker.reset()
        latencyController.reset()
        queue.clear()
        deduplicator.clear()
        translationCoordinator.clear()
        translationContext.clear()
        subtitleTimeline.clear()
        workerStarted = false
        isRunning = false
    }

    private fun publishCue(
        cue: LiveSttRouter.LiveCue,
        sourceLanguage: String = cue.language,
        translationProvider: String = ""
    ) {
        val naturalUntil = LiveSubtitleTimingPolicy.displayUntilMs(cue)
        val timing = subtitleTimeline.schedule(
            activeSessionId = sessionId,
            sequence = cue.sequence,
            revision = cue.revision,
            startMs = cue.startMs,
            endMs = cue.endMs,
            naturalDisplayUntilMs = naturalUntil
        )
        if (!timing.accepted || timing.cue == null) {
            publishStatus("timeline_stale", "تم تجاهل cue خارج ترتيب الخط الزمني: ${timing.reason}")
            return
        }

        val scheduled = timing.cue
        val intent = Intent(LiveSubtitleBus.ACTION_CUE).apply {
            setPackage(packageName)
            putExtra(LiveSubtitleBus.EXTRA_SEQUENCE, cue.sequence)
            putExtra(LiveSubtitleBus.EXTRA_START_MS, scheduled.startMs)
            putExtra(LiveSubtitleBus.EXTRA_END_MS, scheduled.endMs)
            putExtra(LiveSubtitleBus.EXTRA_TEXT, cue.text)
            putExtra(LiveSubtitleBus.EXTRA_LANGUAGE, cue.language)
            putExtra(LiveSubtitleBus.EXTRA_PROVIDER, cue.provider)
            putExtra(LiveSubtitleBus.EXTRA_STT_PROVIDER, cue.provider.substringBefore('→'))
            putExtra(LiveSubtitleBus.EXTRA_TRANSLATION_PROVIDER, translationProvider)
            putExtra(LiveSubtitleBus.EXTRA_IS_FINAL, cue.isFinal)
            putExtra(LiveSubtitleBus.EXTRA_REVISION, cue.revision)
            putExtra(LiveSubtitleBus.EXTRA_CAPTURE_STARTED_ELAPSED_MS, captureStartedElapsedMs)
            putExtra(LiveSubtitleBus.EXTRA_SESSION_ID, sessionId)
            putExtra(LiveSubtitleBus.EXTRA_DISPLAY_UNTIL_MS, scheduled.displayUntilMs)
        }
        sendBroadcast(intent)

        timing.previousTrimmedUntilMs?.let { trimmed ->
            publishStatus("timeline_trimmed", "تم تقليص مدة cue السابق إلى ${trimmed}ms لمنع التداخل")
        }
    }

    private fun publishStatus(status: String, detail: String) {
        val intent = Intent(LiveSubtitleBus.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(LiveSubtitleBus.EXTRA_STATUS, status)
            putExtra(LiveSubtitleBus.EXTRA_DETAIL, detail.take(300))
            putExtra(LiveSubtitleBus.EXTRA_SESSION_ID, sessionId)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            41,
            Intent(this, SariSubtitleActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.sari.subtitle.v2.R.mipmap.ic_launcher)
            .setContentTitle("Sari Subtitle — الترجمة المباشرة")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sari Subtitle Live Capture", NotificationManager.IMPORTANCE_LOW).apply {
                description = "التقاط صوت الوسائط للترجمة المباشرة"
            }
        )
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .take(300)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableExtraCompat(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            getParcelableExtra(key)
        }
    }

    override fun onDestroy() {
        stopCaptureInternal()
        captureExecutor.shutdownNow()
        sttExecutor.shutdownNow()
        translationCoordinator.clear()
        translationContext.clear()
        translationExecutor.shutdownNow()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package com.sari.subtitle

import android.os.SystemClock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.ByteString
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 45: real Deepgram Nova-3 streaming STT over WebSocket.
 *
 * Input is raw PCM16 little-endian, mono, 16 kHz. The session emits PARTIAL
 * updates while speech is arriving and emits one FINAL event when Deepgram
 * reports speech_final=true. API keys are never included in logs/events.
 */
class DeepgramStreamingSttSession(
    private val apiKeyProvider: () -> String,
    private val sourceLanguage: String,
    private val onEvent: (LiveTranscriptStabilizer.Event) -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    companion object {
        private const val ENDPOINT = "wss://api.deepgram.com/v1/listen"
        private const val MODEL = "nova-3"
        private const val SAMPLE_RATE = 16_000
        private const val ENDPOINTING_MS = 350
        private const val KEEP_ALIVE_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 15L
        private const val PING_INTERVAL_MS = 15L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.SECONDS)
        .build()

    private val keepAliveExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Sari-Deepgram-KeepAlive").apply { isDaemon = true }
    }

    private val sequenceCounter = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var keepAliveFuture: ScheduledFuture<*>? = null
    private var currentSequence = -1L
    private var currentStartMs = 0L
    private var revision = 0
    private val finalizedFragments = mutableListOf<String>()
    private var openedAtElapsedMs = 0L

    @Synchronized
    fun start(): Boolean {
        if (closed.get()) return false
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            fail(IllegalStateException("مفتاح Deepgram غير مضبوط"))
            return false
        }

        val url = buildUrl(sourceLanguage)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $key")
            .header("User-Agent", "SariSubtitle/0.45")
            .build()

        openedAtElapsedMs = SystemClock.elapsedRealtime()
        socket = client.newWebSocket(request, listener)
        return true
    }

    /** Sends non-empty raw PCM16 audio. Safe to call from the capture thread. */
    fun sendPcm(pcm: ByteArray, length: Int = pcm.size): Boolean {
        if (closed.get() || failed.get()) return false
        val safeLength = length.coerceIn(0, pcm.size)
        if (safeLength <= 0) return false
        return socket?.send(pcm.toByteString(0, safeLength)) == true
    }

    @Synchronized
    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        keepAliveFuture?.cancel(true)
        keepAliveFuture = null
        try {
            socket?.send("{\"type\":\"Finalize\"}")
            socket?.send("{\"type\":\"CloseStream\"}")
        } catch (_: Throwable) {
            // The local lifecycle is already closing; do not leak an exception to capture.
        }
        try { socket?.close(1000, "Sari Subtitle stop") } catch (_: Throwable) {}
        socket = null
        keepAliveExecutor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun isActive(): Boolean = !closed.get() && !failed.get() && socket != null

    private fun buildUrl(language: String): String {
        val builder = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("model", MODEL)
            .addQueryParameter("encoding", "linear16")
            .addQueryParameter("sample_rate", SAMPLE_RATE.toString())
            .addQueryParameter("channels", "1")
            .addQueryParameter("interim_results", "true")
            .addQueryParameter("endpointing", ENDPOINTING_MS.toString())
            .addQueryParameter("punctuate", "true")
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("utterance_end_ms", "1000")
            .addQueryParameter("vad_events", "true")

        val normalized = language.trim().lowercase(Locale.ROOT)
        when {
            normalized == "ar" -> builder.addQueryParameter("language", "ar")
            normalized == "en" -> builder.addQueryParameter("language", "en")
            normalized.isBlank() || normalized == "auto" -> builder.addQueryParameter("language", "multi")
            else -> builder.addQueryParameter("language", normalized)
        }
        return builder.build().toString()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get()) return
            onStatus("streaming_connected", "Deepgram Nova-3 WebSocket متصل")
            keepAliveFuture = keepAliveExecutor.scheduleAtFixedRate({
                if (!closed.get() && !failed.get()) {
                    webSocket.send("{\"type\":\"KeepAlive\"}")
                }
            }, KEEP_ALIVE_MS, KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed.get()) return
            try {
                handleMessage(text)
            } catch (t: Throwable) {
                fail(t)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed.get()) return
            fail(IOExceptionSanitized(t))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get() && !failed.get()) {
                fail(IllegalStateException("Deepgram WebSocket closed: HTTP/$code"))
            }
        }
    }

    @Synchronized
    private fun handleMessage(raw: String) {
        val json = JSONObject(raw)
        when (json.optString("type")) {
            "Results" -> handleResults(json)
            "Error" -> fail(IllegalStateException(sanitize(json.optString("description").ifBlank { "Deepgram streaming error" })))
            "Metadata" -> onStatus("streaming_ready", "Deepgram Nova-3 streaming جاهز")
            "SpeechStarted" -> onStatus("speech_started", "بدأ الكلام")
            "UtteranceEnd" -> onStatus("utterance_end", "انتهت العبارة")
        }
    }

    private fun handleResults(json: JSONObject) {
        val channel = json.optJSONObject("channel") ?: return
        val alternatives = channel.optJSONArray("alternatives") ?: return
        if (alternatives.length() == 0) return
        val alt = alternatives.optJSONObject(0) ?: return
        val transcript = alt.optString("transcript").trim()
        if (transcript.isBlank()) return

        val startMs = (json.optDouble("start", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
        val durationMs = (json.optDouble("duration", 0.0) * 1000.0).toLong().coerceAtLeast(1L)
        val endMs = startMs + durationMs
        val isFinal = json.optBoolean("is_final", false)
        val speechFinal = json.optBoolean("speech_final", false)

        if (currentSequence < 0L || startMs < currentStartMs - 500L ||
            (speechFinal && currentSequence >= 0L)) {
            beginTrack(startMs)
        }

        if (currentSequence < 0L) beginTrack(startMs)

        revision += 1
        if (!isFinal) {
            val combined = combine(finalizedFragments, transcript)
            onEvent(
                LiveTranscriptStabilizer.Event(
                    sequence = currentSequence,
                    startMs = currentStartMs,
                    endMs = maxOf(endMs, currentStartMs + 300L),
                    text = combined,
                    language = detectedLanguage(alt),
                    provider = SubtitleSettings.STT_DEEPGRAM,
                    kind = LiveTranscriptStabilizer.Kind.PARTIAL,
                    revision = revision
                )
            )
            return
        }

        finalizedFragments.add(transcript)
        val combined = finalizedFragments.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (speechFinal) {
            onEvent(
                LiveTranscriptStabilizer.Event(
                    sequence = currentSequence,
                    startMs = currentStartMs,
                    endMs = maxOf(endMs, currentStartMs + 300L),
                    text = combined,
                    language = detectedLanguage(alt),
                    provider = SubtitleSettings.STT_DEEPGRAM,
                    kind = LiveTranscriptStabilizer.Kind.FINAL,
                    revision = revision
                )
            )
            currentSequence = -1L
            currentStartMs = 0L
            revision = 0
            finalizedFragments.clear()
        } else {
            onEvent(
                LiveTranscriptStabilizer.Event(
                    sequence = currentSequence,
                    startMs = currentStartMs,
                    endMs = maxOf(endMs, currentStartMs + 300L),
                    text = combined,
                    language = detectedLanguage(alt),
                    provider = SubtitleSettings.STT_DEEPGRAM,
                    kind = LiveTranscriptStabilizer.Kind.PARTIAL,
                    revision = revision
                )
            )
        }
    }

    private fun beginTrack(startMs: Long) {
        currentSequence = sequenceCounter.incrementAndGet()
        currentStartMs = startMs
        revision = 0
        finalizedFragments.clear()
    }

    private fun combine(finalized: List<String>, current: String): String =
        (finalized + current).joinToString(" ").replace(Regex("\\s+"), " ").trim()

    private fun detectedLanguage(alternative: JSONObject): String {
        val languages = alternative.optJSONArray("languages")
        return languages?.optString(0)?.takeIf { it.isNotBlank() } ?: sourceLanguage
    }

    @Synchronized
    private fun fail(error: Throwable) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        keepAliveFuture?.cancel(true)
        keepAliveFuture = null
        onStatus("streaming_failed", sanitize(error.message ?: error.javaClass.simpleName))
        onFailure(error)
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("(?i)(token|authorization|api[-_ ]?key)\\s*[:=]\\s*[^ ]+"), "$1=<redacted>")
        .take(300)

    private class IOExceptionSanitized(cause: Throwable) :
        java.io.IOException(cause.message?.let {
            it.replace(Regex("[\\r\\n\\t]+"), " ").take(300)
        } ?: cause.javaClass.simpleName, cause)
}

package com.sari.subtitle

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlin.math.roundToInt

/**
 * Stage 40: context-aware routing signals.
 *
 * This class deliberately uses only measured/known signals. It never invents
 * provider accuracy, speech speed, or quota values.
 */
object AdaptiveRoutingEngine {
    enum class NetworkClass { OFFLINE, WIFI, CELLULAR, OTHER, UNKNOWN }

    data class ContextSnapshot(
        val task: ProviderScoringEngine.Task,
        val sourceLanguage: String? = null,
        val audioDurationMs: Long? = null,
        val chunkDurationMs: Long? = null,
        val speechSpeedWpm: Int? = null,
        val networkClass: NetworkClass = NetworkClass.UNKNOWN,
        val metered: Boolean = false
    )

    data class Adjustment(
        val provider: String,
        val points: Int,
        val reasons: List<String>
    )

    fun snapshot(
        context: Context,
        task: ProviderScoringEngine.Task,
        sourceLanguage: String? = null,
        audioDurationMs: Long? = null,
        chunkDurationMs: Long? = null,
        speechSpeedWpm: Int? = null
    ): ContextSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val networkClass = when {
            caps == null -> if (network == null) NetworkClass.OFFLINE else NetworkClass.UNKNOWN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkClass.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkClass.CELLULAR
            else -> NetworkClass.OTHER
        }
        return ContextSnapshot(
            task = task,
            sourceLanguage = sourceLanguage?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
            audioDurationMs = audioDurationMs?.takeIf { it > 0 },
            chunkDurationMs = chunkDurationMs?.takeIf { it > 0 },
            speechSpeedWpm = speechSpeedWpm?.takeIf { it > 0 },
            networkClass = networkClass,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        )
    }

    fun adjustment(provider: String, base: ProviderScoringEngine.ScoreBreakdown, snapshot: ContextSnapshot): Adjustment {
        var points = 0
        val reasons = mutableListOf<String>()
        val latency = base.latency

        // A metered/cellular network makes latency more important. This is a generic
        // network rule; it does not assume one provider is intrinsically faster.
        if (snapshot.metered || snapshot.networkClass == NetworkClass.CELLULAR) {
            val latencyBonus = when {
                latency >= 22 -> 3
                latency >= 18 -> 1
                latency <= 8 -> -2
                else -> 0
            }
            points += latencyBonus
            if (latencyBonus != 0) reasons += "شبكة محدودة: وزن latency=$latencyBonus"
        }

        // For very short live chunks, a measured low-latency provider is preferable.
        // The provider identity is intentionally ignored here.
        if (snapshot.chunkDurationMs != null && snapshot.chunkDurationMs <= 2500L) {
            val bonus = when {
                latency >= 22 -> 2
                latency <= 8 -> -2
                else -> 0
            }
            points += bonus
            if (bonus != 0) reasons += "chunk قصير: latency=$bonus"
        }

        // Fast speech can increase transcription difficulty. Only a measured WPM
        // value is used; no accuracy claim is made from it.
        if (snapshot.task == ProviderScoringEngine.Task.STT && snapshot.speechSpeedWpm != null) {
            val bonus = when {
                snapshot.speechSpeedWpm >= 190 && base.quality > 18 -> 2
                snapshot.speechSpeedWpm >= 190 && base.quality <= 18 -> -1
                else -> 0
            }
            points += bonus
            if (bonus != 0) reasons += "سرعة كلام مقاسة=${snapshot.speechSpeedWpm}wpm"
        }

        // Long media is not assigned a fake provider preference. We only expose the
        // signal for diagnostics unless a provider has measured historical behavior.
        if (snapshot.audioDurationMs != null && snapshot.audioDurationMs >= 10 * 60_000L) {
            reasons += "وسيط طويل=${snapshot.audioDurationMs / 60_000L}min"
        }

        return Adjustment(provider, points.coerceIn(-5, 5), reasons)
    }

    fun rank(
        context: Context,
        providers: List<String>,
        task: ProviderScoringEngine.Task,
        configured: (String) -> Boolean,
        forceProvider: String? = null,
        sourceLanguage: String? = null,
        audioDurationMs: Long? = null,
        chunkDurationMs: Long? = null,
        speechSpeedWpm: Int? = null
    ): List<ProviderRoutingEngine.Candidate> {
        val snapshot = snapshot(context, task, sourceLanguage, audioDurationMs, chunkDurationMs, speechSpeedWpm)
        return ProviderRoutingEngine.rank(
            context = context,
            providers = providers,
            configured = configured,
            forceProvider = forceProvider,
            task = task,
            adaptiveContext = snapshot
        )
    }

    fun srtDurationMs(srt: String): Long? {
        val regex = Regex("(?m)(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})")
        var firstStart: Long? = null
        var lastEnd: Long? = null
        for (match in regex.findAll(srt)) {
            fun toMs(h: String, m: String, sec: String, ms: String): Long =
                h.toLong() * 3_600_000L + m.toLong() * 60_000L + sec.toLong() * 1_000L + ms.toLong()
            val start = toMs(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
            val end = toMs(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
            if (firstStart == null) firstStart = start
            lastEnd = maxOf(lastEnd ?: end, end)
        }
        return if (firstStart != null && lastEnd != null && lastEnd!! > firstStart!!) lastEnd!! - firstStart!! else null
    }

    fun wordsPerMinute(text: String, durationMs: Long): Int? {
        if (durationMs <= 0) return null
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        if (words == 0) return null
        return ((words * 60_000.0 / durationMs).roundToInt()).coerceIn(1, 600)
    }
}

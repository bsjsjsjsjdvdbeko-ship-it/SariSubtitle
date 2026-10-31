package com.sari.subtitle

import kotlin.math.sqrt

/** Lightweight RMS-based VAD for PCM16. It is a gate, not a speech recognizer. */
class SpeechActivityDetector(
    private val thresholdRms: Double = 0.012,
    private val minSpeechRatio: Double = 0.08
) {
    data class Result(val isSpeech: Boolean, val rms: Double, val speechRatio: Double)

    fun analyze(pcm16le: ByteArray): Result {
        if (pcm16le.size < 2) return Result(false, 0.0, 0.0)
        var sumSquares = 0.0
        var voiced = 0
        var samples = 0
        var i = 0
        while (i + 1 < pcm16le.size) {
            val sample = ((pcm16le[i + 1].toInt() shl 8) or (pcm16le[i].toInt() and 0xff)).toShort().toInt()
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            if (kotlin.math.abs(normalized) >= thresholdRms) voiced++
            samples++
            i += 2
        }
        val rms = sqrt(sumSquares / samples.coerceAtLeast(1))
        val ratio = voiced.toDouble() / samples.coerceAtLeast(1)
        return Result(rms >= thresholdRms && ratio >= minSpeechRatio, rms, ratio)
    }
}

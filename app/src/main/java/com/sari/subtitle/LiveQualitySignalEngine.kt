package com.sari.subtitle

import kotlin.math.roundToInt

/**
 * Stage 51: deterministic, reference-free quality signals for live STT/translation.
 *
 * This is NOT WER/CER and never pretends to know semantic correctness without a
 * reference transcript. It only scores observable structural signals from the
 * text already produced by a provider.
 */
object LiveQualitySignalEngine {
    data class SignalResult(
        val score: Int,
        val measured: Boolean,
        val reasons: List<String>
    )

    fun scoreStt(text: String, expectedLanguage: String = "auto"): SignalResult {
        val clean = normalize(text)
        if (clean.isBlank()) return SignalResult(0, true, listOf("empty"))

        var score = 100.0
        val reasons = mutableListOf<String>()
        val tokens = words(clean)

        if (tokens.size >= 3) {
            val unique = tokens.map { it.lowercase() }.toSet().size
            val repetition = 1.0 - unique.toDouble() / tokens.size.toDouble()
            if (repetition > 0.45) {
                score -= 35.0
                reasons += "high_repetition"
            } else if (repetition > 0.25) {
                score -= 15.0
                reasons += "repetition"
            }
        }

        if (expectedLanguage != "auto" && expectedLanguage.isNotBlank()) {
            val lang = normalizeLang(expectedLanguage)
            val mismatch = languageMismatch(clean, lang)
            if (mismatch > 0.55) {
                score -= 35.0
                reasons += "language_script_mismatch"
            } else if (mismatch > 0.30) {
                score -= 15.0
                reasons += "mixed_language_script"
            }
        }

        if (tokens.size > 2 && averageTokenLength(tokens) < 1.7) {
            score -= 15.0
            reasons += "fragmented_tokens"
        }

        if (clean.length > 450) {
            score -= 10.0
            reasons += "unexpected_length"
        }

        return SignalResult(score.coerceIn(0.0, 100.0).roundToInt(), true, reasons)
    }

    fun scoreTranslation(source: String, translated: String, targetLanguage: String = "ar"): SignalResult {
        val src = normalize(source)
        val dst = normalize(translated)
        if (dst.isBlank()) return SignalResult(0, true, listOf("empty_translation"))

        var score = 100.0
        val reasons = mutableListOf<String>()
        val srcTokens = words(src)
        val dstTokens = words(dst)

        if (src.equals(dst, ignoreCase = true) && !looksTarget(dst, targetLanguage)) {
            score -= 55.0
            reasons += "unchanged_source"
        }

        if (dstTokens.size >= 3) {
            val unique = dstTokens.map { it.lowercase() }.toSet().size
            val repetition = 1.0 - unique.toDouble() / dstTokens.size.toDouble()
            if (repetition > 0.50) {
                score -= 30.0
                reasons += "high_repetition"
            } else if (repetition > 0.30) {
                score -= 12.0
                reasons += "repetition"
            }
        }

        if (targetLanguage.equals("ar", ignoreCase = true)) {
            val arabicRatio = arabicCharRatio(dst)
            if (arabicRatio < 0.25) {
                score -= 35.0
                reasons += "low_arabic_script_ratio"
            } else if (arabicRatio < 0.55) {
                score -= 12.0
                reasons += "mixed_arabic_script"
            }
        }

        if (srcTokens.size >= 2 && dstTokens.size >= 2) {
            val ratio = dstTokens.size.toDouble() / srcTokens.size.toDouble()
            if (ratio < 0.20 || ratio > 5.0) {
                score -= 20.0
                reasons += "extreme_length_ratio"
            }
        }

        if (dst.length > 1200) {
            score -= 10.0
            reasons += "unexpected_length"
        }

        return SignalResult(score.coerceIn(0.0, 100.0).roundToInt(), true, reasons)
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun words(value: String): List<String> = value
        .split(Regex("\\s+"))
        .map { it.trim { c -> !c.isLetterOrDigit() && c != '\u060C' } }
        .filter { it.isNotBlank() }

    private fun normalizeLang(value: String): String = value.lowercase().substringBefore('-')

    private fun languageMismatch(text: String, expected: String): Double {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return 1.0
        val mismatch = when (expected) {
            "ar" -> text.count { it.isLetter() && !isArabic(it) }
            "en" -> text.count { it.isLetter() && !it.isAscii() }
            else -> return 0.0
        }
        return mismatch.toDouble() / letters.toDouble()
    }

    private fun looksTarget(text: String, target: String): Boolean = when (normalizeLang(target)) {
        "ar" -> arabicCharRatio(text) >= 0.55
        "en" -> text.count { it.isLetter() && it.isAscii() }.toDouble() /
            text.count { it.isLetter() }.coerceAtLeast(1).toDouble() >= 0.55
        else -> true
    }

    private fun arabicCharRatio(text: String): Double {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return 0.0
        return text.count { it.isLetter() && isArabic(it) }.toDouble() / letters.toDouble()
    }

    private fun isArabic(c: Char): Boolean = c in '\u0600'..'\u06FF'
    private fun Char.isAscii(): Boolean = code in 0..127

    private fun averageTokenLength(tokens: List<String>): Double =
        tokens.map { it.length }.average()
}

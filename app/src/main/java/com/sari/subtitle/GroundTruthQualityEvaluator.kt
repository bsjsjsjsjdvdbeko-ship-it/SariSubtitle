package com.sari.subtitle

/**
 * Stage 54: deterministic reference-based translation evaluation.
 *
 * This class is deliberately dependency-free so it can be unit-tested without Android.
 * It never stores or logs source/candidate/reference text.
 *
 * Scores are reference-based engineering signals, not human equivalence guarantees.
 */
object GroundTruthQualityEvaluator {
    data class Result(
        val bleu: Double,
        val chrf: Double,
        val combined: Int
    )

    fun evaluate(reference: String, candidate: String): Result {
        val ref = tokenize(reference)
        val hyp = tokenize(candidate)
        if (ref.isEmpty() || hyp.isEmpty()) return Result(0.0, 0.0, 0)
        if (ref == hyp) return Result(1.0, 1.0, 100)

        val bleu = corpusLikeBleu(ref, hyp)
        val chrf = chrF(reference, candidate)
        val combined = ((bleu * 0.55 + chrf * 0.45) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
        return Result(bleu, chrf, combined)
    }

    private fun tokenize(text: String): List<String> =
        text.trim()
            .lowercase()
            .replace(ARABIC_DIACRITICS, "")
            .replace(ARABIC_ALEF_FORMS, "ا")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }

    /** Smoothed sentence BLEU-1..4 with brevity penalty, expressed 0..1. */
    private fun corpusLikeBleu(reference: List<String>, hypothesis: List<String>): Double {
        val precisions = (1..4).map { n ->
            val refCounts = ngramCounts(reference, n)
            val hypCounts = ngramCounts(hypothesis, n)
            if (hypCounts.isEmpty()) return@map 1e-9
            var clipped = 0
            var total = 0
            for ((gram, count) in hypCounts) {
                clipped += minOf(count, refCounts[gram] ?: 0)
                total += count
            }
            // Add-one smoothing prevents a single missing 4-gram from collapsing the score.
            (clipped + 1.0) / (total + 1.0)
        }
        val logPrecision = precisions.map { kotlin.math.ln(it.coerceAtLeast(1e-12)) }.average()
        val geometric = kotlin.math.exp(logPrecision)
        val bp = if (hypothesis.size > reference.size) {
            1.0
        } else {
            kotlin.math.exp(1.0 - reference.size.toDouble() / hypothesis.size.toDouble())
        }
        return (bp * geometric).coerceIn(0.0, 1.0)
    }

    /** Character F-score over n=1..6, normalized to 0..1. */
    private fun chrF(reference: String, hypothesis: String): Double {
        val ref = normalizeForChars(reference)
        val hyp = normalizeForChars(hypothesis)
        if (ref.isEmpty() || hyp.isEmpty()) return 0.0

        val scores = (1..6).map { n ->
            val r = charNgramCounts(ref, n)
            val h = charNgramCounts(hyp, n)
            val refTotal = r.values.sum()
            val hypTotal = h.values.sum()
            if (refTotal == 0 || hypTotal == 0) return@map 0.0
            val common = h.entries.sumOf { (gram, count) -> minOf(count, r[gram] ?: 0) }
            val precision = common.toDouble() / hypTotal
            val recall = common.toDouble() / refTotal
            if (precision + recall == 0.0) 0.0 else (2.0 * precision * recall) / (precision + recall)
        }
        return scores.average().coerceIn(0.0, 1.0)
    }

    private fun ngramCounts(tokens: List<String>, n: Int): Map<String, Int> {
        if (tokens.size < n) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (i in 0..tokens.size - n) {
            val key = tokens.subList(i, i + n).joinToString(" ")
            result[key] = (result[key] ?: 0) + 1
        }
        return result
    }

    private fun charNgramCounts(text: String, n: Int): Map<String, Int> {
        if (text.length < n) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (i in 0..text.length - n) {
            val key = text.substring(i, i + n)
            result[key] = (result[key] ?: 0) + 1
        }
        return result
    }

    private fun normalizeForChars(text: String): String =
        text.trim()
            .lowercase()
            .replace(ARABIC_DIACRITICS, "")
            .replace(ARABIC_ALEF_FORMS, "ا")
            .replace(Regex("\\s+"), " ")

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()

    private val ARABIC_DIACRITICS = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val ARABIC_ALEF_FORMS = Regex("[\\u0622\\u0623\\u0625]")
}

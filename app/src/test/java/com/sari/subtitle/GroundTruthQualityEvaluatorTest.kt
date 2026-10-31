package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthQualityEvaluatorTest {
    @Test
    fun identicalTranslationScoresPerfectly() {
        val result = GroundTruthQualityEvaluator.evaluate(
            "مرحبا كيف حالك اليوم",
            "مرحبا كيف حالك اليوم"
        )
        assertEquals(1.0, result.bleu, 1e-9)
        assertEquals(1.0, result.chrf, 1e-9)
        assertEquals(100, result.combined)
    }

    @Test
    fun unrelatedTranslationScoresLowerThanReference() {
        val good = GroundTruthQualityEvaluator.evaluate(
            "تم اكتشاف حركة غير طبيعية في الشبكة",
            "تم اكتشاف حركة غير طبيعية في الشبكة"
        )
        val bad = GroundTruthQualityEvaluator.evaluate(
            "تم اكتشاف حركة غير طبيعية في الشبكة",
            "افتح الفيديو وانتظر قليلا"
        )
        assertTrue(good.combined > bad.combined)
        assertTrue(bad.combined < 70)
    }

    @Test
    fun arabicAlefNormalizationDoesNotDestroyMatch() {
        val result = GroundTruthQualityEvaluator.evaluate(
            "هذا اختبار للترجمة",
            "هٰذا اختبار للترجمة"
        )
        assertTrue(result.combined >= 90)
    }

    @Test
    fun emptyCandidateIsZero() {
        val result = GroundTruthQualityEvaluator.evaluate("مرحبا", "")
        assertEquals(0, result.combined)
    }
}

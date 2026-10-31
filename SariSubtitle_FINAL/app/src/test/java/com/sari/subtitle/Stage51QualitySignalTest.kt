package com.sari.subtitle

import org.junit.Assert.assertTrue
import org.junit.Test

class Stage51QualitySignalTest {
    @Test
    fun repeatedSttTextGetsLowerStructuralScore() {
        val result = LiveQualitySignalEngine.scoreStt("hello hello hello hello", "en")
        assertTrue(result.measured)
        assertTrue(result.score < 90)
    }

    @Test
    fun arabicTranslationGetsTargetScriptScore() {
        val result = LiveQualitySignalEngine.scoreTranslation("hello world", "مرحبا بالعالم", "ar")
        assertTrue(result.score >= 80)
    }

    @Test
    fun unchangedForeignTranslationGetsPenalized() {
        val result = LiveQualitySignalEngine.scoreTranslation("hello world", "hello world", "ar")
        assertTrue(result.score < 70)
    }

    @Test
    fun cleanEnglishSttDoesNotTriggerLanguageMismatch() {
        val result = LiveQualitySignalEngine.scoreStt("this is a normal sentence", "en")
        assertTrue(result.score >= 85)
    }
}

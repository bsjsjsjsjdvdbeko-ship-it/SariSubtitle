package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SttErrorRateEvaluatorTest {
    @Test fun identicalArabicTextHasZeroError() {
        assertEquals(0.0, SttErrorRateEvaluator.wer("مرحبا كيف حالك", "مرحبا كيف حالك"), 0.0001)
        assertEquals(0.0, SttErrorRateEvaluator.cer("مرحبا كيف حالك", "مرحبا كيف حالك"), 0.0001)
    }
    @Test fun insertionIsCountedInWer() {
        assertEquals(1.0 / 3.0, SttErrorRateEvaluator.wer("مرحبا كيف حالك", "مرحبا يا كيف حالك"), 0.0001)
    }
    @Test fun arabicAlefVariantsAreNormalized() {
        assertEquals(0.0, SttErrorRateEvaluator.wer("أهلاً بكم", "اهلاً بكم"), 0.0001)
    }
    @Test fun emptyReferenceIsHandledWithoutDivisionByZero() {
        assertEquals(1.0, SttErrorRateEvaluator.wer("", "كلمة"), 0.0001)
        assertEquals(0.0, SttErrorRateEvaluator.cer("", ""), 0.0001)
    }
}

package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiQualityEvaluatorTest {
    @Test fun emptyTranscriptIsRejected() {
        val result = TranscriptQualityEvaluator.evaluate("", 5000, AudioFeatures(0.1, 0.1, 0.5, 0.5))
        assertFalse(result.accepted)
    }

    @Test fun normalTranscriptIsAccepted() {
        val result = TranscriptQualityEvaluator.evaluate("Hello everyone", 5000, AudioFeatures(0.1, 0.08, 0.7, 0.5))
        assertTrue(result.accepted)
    }

    @Test fun emptyTranslationIsRejected() {
        assertFalse(TranslationQualityEvaluator.evaluate("", "hello", "ar").accepted)
    }

    @Test fun validTranslationIsAccepted() {
        assertTrue(TranslationQualityEvaluator.evaluate("مرحبا بالجميع", "Hello everyone", "ar").accepted)
    }
}

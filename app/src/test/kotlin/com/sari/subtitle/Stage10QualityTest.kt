package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Stage10QualityTest {
    @Test
    fun humanQualityIsHighestMode() {
        assertEquals(2, SettingsRepository.QUALITY_HUMAN)
        assertTrue(SettingsRepository.QUALITY_HUMAN > SettingsRepository.QUALITY_BALANCED)
    }

    @Test
    fun contextKeepsSourceAndTranslationTogether() {
        val turn = ContextTurn("Where are you?", "أين أنت؟")
        val context = TranslationContext(previousTranslations = listOf(turn))
        assertEquals("Where are you?", context.previousTranslations.first().source)
        assertEquals("أين أنت؟", context.previousTranslations.first().translation)
    }
}

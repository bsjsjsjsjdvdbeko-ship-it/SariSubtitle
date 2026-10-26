package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Stage14SettingsSnapshotTest {
    @Test fun defaultsPreserveExpectedProvidersAndAppearance() {
        val s = AppSettings()
        assertEquals("groq", s.sttProvider)
        assertEquals(30, s.fontSize)
        assertEquals(82, s.opacity)
        assertEquals("bottom", s.subtitlePosition)
    }

    @Test fun snapshotCopyPreservesUnrelatedSettings() {
        val original = AppSettings(speechKey = "not-a-real-key", deepgramKey = "dummy", adaptiveRoutingEnabled = true)
        val changed = original.copy(targetLanguage = "ar")
        assertEquals(original.speechKey, changed.speechKey)
        assertEquals(original.deepgramKey, changed.deepgramKey)
        assertTrue(changed.adaptiveRoutingEnabled)
        assertEquals("ar", changed.targetLanguage)
    }
}

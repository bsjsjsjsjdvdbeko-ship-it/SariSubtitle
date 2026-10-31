package com.sari.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechActivityDetectorTest {
    @Test fun silence_is_not_speech() {
        assertFalse(SpeechActivityDetector().analyze(ByteArray(32000)).isSpeech)
    }

    @Test fun strong_pcm_signal_is_speech() {
        val pcm = ByteArray(32000)
        var i = 0
        while (i < pcm.size) { pcm[i] = 0x55; if (i + 1 < pcm.size) pcm[i + 1] = 0x55; i += 2 }
        assertTrue(SpeechActivityDetector().analyze(pcm).isSpeech)
    }
}

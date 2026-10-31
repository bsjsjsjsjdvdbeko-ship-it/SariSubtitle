package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChunkerTest {
    @Test fun emits_two_second_chunks_with_overlap() {
        val c = AudioChunker(16_000, 1, 2_000, 250)
        val pcm = ByteArray(16_000 * 2 * 2 + 16_000 * 2 / 2)
        val chunks = c.append(pcm)
        assertTrue(chunks.size >= 2)
        assertEquals(2_000L, chunks[0].durationMs)
        assertEquals(1_750L, chunks[1].startMs)
    }

    @Test fun flushes_partial_aligned_audio() {
        val c = AudioChunker()
        val part = ByteArray(16_000)
        c.append(part)
        val chunk = c.flush()
        assertEquals(500L, chunk?.durationMs)
    }
}

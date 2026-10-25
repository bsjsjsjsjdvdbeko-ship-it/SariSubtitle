package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.charset.Charset

class SrtParserTest {
    @Test
    fun parsesUtf8Arabic() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n".toByteArray(Charsets.UTF_8)
        val cue = SrtParser.parse(bytes.inputStream()).single()
        assertEquals("مرحبا", cue.text)
        assertEquals(1_000L, cue.startMs)
        assertEquals(2_000L, cue.endMs)
    }

    @Test
    fun fallsBackToWindows1256ForLegacyArabic() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n".toByteArray(Charset.forName("windows-1256"))
        assertEquals("مرحبا", SrtParser.parse(bytes.inputStream()).single().text)
    }

    @Test
    fun parsesUtf16LeWithBom() {
        val body = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
        assertEquals("Hello", SrtParser.parse(bytes.inputStream()).single().text)
    }

    @Test
    fun normalizesArabicIndicTimestampDigits() {
        val bytes = "١\n٠٠:٠٠:٠١,٠٠٠ --> ٠٠:٠٠:٠٢,٠٠٠\nاختبار\n".toByteArray(Charsets.UTF_8)
        val cue = SrtParser.parse(bytes.inputStream()).single()
        assertEquals(1, cue.index)
        assertEquals(1_000L, cue.startMs)
        assertEquals(2_000L, cue.endMs)
    }

    @Test
    fun rejectsInputStreamLargerThanConfiguredLimit() {
        try {
            SrtParser.readBoundedBytes("123456789".byteInputStream(), 8)
            fail("Expected oversized input to be rejected")
        } catch (_: IOException) {
            // Expected: input is rejected before it can be accumulated without a limit.
        }
    }

    @Test
    fun decodesStreamWithoutChangingArabicText() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n"
        assertEquals(text.trim(), SrtParser.decode(text.byteInputStream()).trim())
    }

    @Test
    fun rejectsInvalidSrtWithoutThrowing() {
        assertTrue(SrtParser.parse("not an srt".byteInputStream()).isEmpty())
    }
}

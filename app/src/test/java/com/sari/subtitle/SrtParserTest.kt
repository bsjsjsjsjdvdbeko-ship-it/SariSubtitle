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
    fun rejectsOutOfRangeTimestampFields() {
        val invalid = "1\n00:61:00,000 --> 00:61:01,000\nInvalid minutes\n\n" +
            "2\n00:00:61,000 --> 00:01:02,000\nInvalid seconds\n"
        assertTrue(SrtParser.parse(invalid.byteInputStream()).isEmpty())
    }

    @Test
    fun rejectsReversedAndZeroLengthCueIntervals() {
        val invalid = "1\n00:00:03,000 --> 00:00:02,000\nReversed\n\n" +
            "2\n00:00:02,000 --> 00:00:02,000\nZero length\n"
        assertTrue(SrtParser.parse(invalid.byteInputStream()).isEmpty())
    }

    @Test
    fun acceptsOneAndTwoDigitFractionsAndNormalizesToMilliseconds() {
        val text = "1\n00:00:01.2 --> 00:00:02.34\nFractions\n"
        val cue = SrtParser.parse(text.byteInputStream()).single()
        assertEquals(1_200L, cue.startMs)
        assertEquals(2_340L, cue.endMs)
    }

    @Test
    fun rejectsMalformedAndOverflowingTimestamps() {
        val invalid = "1\n00:00:01,1234 --> 00:00:02,000\nToo many fraction digits\n\n" +
            "2\n999999999999999999999999:00:00,000 --> 00:00:02,000\nOverflow\n"
        assertTrue(SrtParser.parse(invalid.byteInputStream()).isEmpty())
    }

    @Test
    fun rejectsInvalidSrtWithoutThrowing() {
        assertTrue(SrtParser.parse("not an srt".byteInputStream()).isEmpty())
    }
}

package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleParserTest {
    @Test fun parsesSrtAndSortsCues() {
        val cues = SubtitleParser.parse(
            "2\n00:00:03,000 --> 00:00:04,000\nثانٍ\n\n1\n00:00:01,000 --> 00:00:02,500\nأول\n"
        )
        assertEquals(2, cues.size)
        assertEquals("أول", cues[0].text)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3000L, cues[1].startMs)
    }

    @Test fun parsesWebVttAndMultilineText() {
        val cues = SubtitleParser.parse(
            "WEBVTT\n\n00:00:01.500 --> 00:00:03.000\nHello\nمرحبا\n"
        )
        assertEquals(1, cues.size)
        assertEquals("Hello\nمرحبا", cues[0].text)
        assertTrue(cues[0].endMs > cues[0].startMs)
    }
}

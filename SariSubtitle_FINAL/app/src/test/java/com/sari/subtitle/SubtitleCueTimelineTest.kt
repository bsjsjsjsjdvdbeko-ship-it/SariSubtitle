package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleCueTimelineTest {
    private val cues = listOf(
        SubtitleCue(1, 1_000L, 2_000L, "first"),
        SubtitleCue(2, 2_500L, 4_000L, "second"),
        SubtitleCue(3, 5_000L, 6_000L, "third")
    )

    @Test fun returnsCueAtInclusiveStartAndExclusiveEnd() {
        assertEquals("first", SubtitleCueTimeline.findAt(cues, 1_000L)?.text)
        assertEquals("first", SubtitleCueTimeline.findAt(cues, 1_999L)?.text)
        assertNull(SubtitleCueTimeline.findAt(cues, 2_000L))
    }

    @Test fun findsLatestCueAndReturnsNullBetweenCues() {
        assertEquals("second", SubtitleCueTimeline.findAt(cues, 3_000L)?.text)
        assertNull(SubtitleCueTimeline.findAt(cues, 4_500L))
        assertEquals("third", SubtitleCueTimeline.findAt(cues, 5_500L)?.text)
    }

    @Test fun handlesEmptyAndNegativePositions() {
        assertNull(SubtitleCueTimeline.findAt(emptyList(), 1_000L))
        assertNull(SubtitleCueTimeline.findAt(cues, -1L))
        assertNull(SubtitleCueTimeline.findAt(cues, 0L))
    }
}

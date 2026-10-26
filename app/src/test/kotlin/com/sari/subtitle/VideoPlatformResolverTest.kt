package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPlatformResolverTest {
    @Test fun recognizesPlatformsAndDirectMedia() {
        assertEquals(VideoPlatform.YOUTUBE, VideoPlatformResolver.resolve("https://www.youtube.com/watch?v=x")?.platform)
        assertEquals(VideoPlatform.INSTAGRAM, VideoPlatformResolver.resolve("https://instagram.com/reel/x/")?.platform)
        assertEquals(VideoPlatform.X, VideoPlatformResolver.resolve("https://x.com/user/status/1")?.platform)
        assertEquals(VideoPlatform.DIRECT, VideoPlatformResolver.resolve("https://example.com/video.mp4")?.platform)
        assertTrue(VideoPlatformResolver.resolve("https://example.com/video.m3u8")?.isDirectMedia == true)
        assertNull(VideoPlatformResolver.resolve("javascript:alert(1)"))
    }
}

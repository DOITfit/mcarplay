package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityTest {
    @Test
    fun defaultScaleKeepsNativeCap() {
        assertEquals(1920, VideoQuality.maxLongSide(scalePercent = 100, lowEnd = false))
        assertEquals(1920, VideoQuality.maxLongSide(scalePercent = 160, lowEnd = false))
    }

    @Test
    fun scalePercentCapsRequestedLongSide() {
        assertEquals(960, VideoQuality.maxLongSide(scalePercent = 50, lowEnd = false))
        assertEquals(1248, VideoQuality.maxLongSide(scalePercent = 65, lowEnd = false))
    }

    @Test
    fun lowEndModeCapsAt720pEvenAtFullScale() {
        assertEquals(1280, VideoQuality.maxLongSide(scalePercent = 100, lowEnd = true))
        assertEquals(960, VideoQuality.maxLongSide(scalePercent = 50, lowEnd = true))
    }

    @Test
    fun tinyScaleStillProducesUsableWidth() {
        assertEquals(320, VideoQuality.maxLongSide(scalePercent = 1, lowEnd = false))
    }
}

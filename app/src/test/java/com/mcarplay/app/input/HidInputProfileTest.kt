package com.mcarplay.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 描述符缓存必须与逐报文解析得到同一结果（触摸热路径依赖它避免重复扫描）。 */
class HidInputProfileTest {
    private fun hex(text: String): ByteArray =
        text
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** 奥迪 eso_playback Consumer Control 描述符（与 ConsumerKeysTest 同一原文）。 */
    private val consumerDescriptor =
        hex(
            "05 0c 09 01 a1 01 15 00 25 08 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a b3 00 0a b4 00 0a b5 00 0a b6 " +
                "00 0a b9 00 0a bc 00 75 08 95 01 81 00 c0",
        )

    @Test
    fun consumerDescriptorIsCachedOnce() {
        val profile = HidInputProfile.from(consumerDescriptor)
        assertNotNull(profile.keyMap)
        assertEquals(
            listOf(0x0000, 0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC),
            profile.keyMap!!.usages,
        )
        assertNull(profile.touchFormat)
        assertFalse(profile.navigation)
    }

    @Test
    fun missingDescriptorFallsBackToNullProfile() {
        val profile = HidInputProfile.from(null)
        assertNull(profile.keyMap)
        assertNull(profile.touchFormat)
        assertFalse(profile.navigation)
    }
}

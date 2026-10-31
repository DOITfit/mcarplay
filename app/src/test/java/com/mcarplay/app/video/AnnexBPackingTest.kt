package com.mcarplay.app.video

import com.mcarplay.app.airplay.StreamFrameCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 长度前缀 NAL 打包：线格式与精确分配（不含尾部多余字节）。 */
class AnnexBPackingTest {
    private fun sender() =
        VideoSender(
            host = "127.0.0.1",
            dataPort = 5000,
            frameCipher = StreamFrameCipher.AesCtr(ByteArray(16), ByteArray(16)),
            width = 1280,
            height = 720,
            log = {},
        )

    @Test
    fun packsBigEndianLengthPrefixesWithoutTrailingSlack() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11) // level 3.1 → 补到 4.0
        val idr = byteArrayOf(0x65, 1, 2)
        val packed = sender().packLengthPrefixed(listOf(sps, idr))

        assertEquals(4 + sps.size + 4 + idr.size, packed.size)
        assertEquals(sps.size, ByteBuffer.wrap(packed, 0, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(40, packed[4 + 3].toInt() and 0xFF)
        assertEquals(0x67, packed[4].toInt() and 0xFF)
        assertEquals(idr.size, ByteBuffer.wrap(packed, 4 + sps.size, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertArrayEquals(idr, packed.copyOfRange(8 + sps.size, packed.size))
    }

    @Test
    fun emptyNalListPacksToEmptyArray() {
        assertEquals(0, sender().packLengthPrefixed(emptyList()).size)
    }
}

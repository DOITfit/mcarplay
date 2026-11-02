package com.mcarplay.app.audio

import org.junit.Assert.assertFalse
import org.junit.Test

class CarLifeAudioBridgeTest {
    @Test
    fun unavailableSinkFailsStartWithoutRunningWorker() {
        val sink =
            object : PcmSink {
                var started = false
                var stopped = false

                override fun startSink(): Boolean {
                    started = true
                    return false
                }

                override fun submitPcm(pcm: ByteArray) = Unit

                override fun stopSink() {
                    stopped = true
                }
            }
        val bridge = CarLifeAudioBridge(sink, 48_000, log = {})
        assertFalse(bridge.start())
        assertFalse(sink.stopped)
        bridge.stop()
    }
}

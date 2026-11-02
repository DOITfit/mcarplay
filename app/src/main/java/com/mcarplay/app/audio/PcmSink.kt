package com.mcarplay.app.audio

/**
 * 车机声音的最终输出端：AirPlay RTP（[AudioSender]）或车机蓝牙 A2DP
 * （[BluetoothAudioSink]）。[CarLifeAudioBridge] 只负责重采样/混音/压低，
 * 不关心音频最终走哪条链路。
 */
interface PcmSink {
    /** 启动输出端；返回 false 表示当前不可用（调用方应回退到另一条链路）。 */
    fun startSink(): Boolean

    /** 提交一段 48kHz 立体声 s16 LE PCM。不得阻塞混音线程。 */
    fun submitPcm(pcm: ByteArray)

    /** 停止并释放输出端；必须幂等。 */
    fun stopSink()
}

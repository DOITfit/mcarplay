package com.mcarplay.app.audio

import android.content.Context

/**
 * 车机声音输出链路偏好。
 *
 * - AirPlay（默认）：CarWith 原生 PCM → RTP/UDP 发到 CarPlay 接收端。
 * - 车机蓝牙 A2DP：CarWith 原生 PCM 在本机重采样/混音后，用 [AudioTrack]
 *   定向到车机 A2DP；CarPlay 只推视频。需要接收端在 CarPlay 会话期间保持
 *   A2DP 连接（部分车机为打开无线 CarPlay 会主动断开 A2DP，此时回退 AirPlay）。
 */
object AudioOutputPrefs {
    const val BLUETOOTH_DEFAULT = false

    const val LOW_LATENCY_DEFAULT = false

    /** A2DP 输出采样率：CarWith 媒体常见 44.1kHz，混音器统一重采样到 48kHz。 */
    const val SAMPLE_RATE = 48_000

    private const val PREFS = "settings"
    private const val KEY_BLUETOOTH = "audio_output_bluetooth"
    private const val KEY_LOW_LATENCY = "audio_low_latency"

    /**
     * AirPlay 音频低延迟档：把接收端音频缓冲从 250ms 下限降到 100ms。
     * 默认关；Wi-Fi 抖动大时可能欠载/断音，仅作为端到端延迟实验开关。
     */
    fun lowLatency(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOW_LATENCY, LOW_LATENCY_DEFAULT)

    fun setLowLatency(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOW_LATENCY, enabled)
            .apply()
    }

    fun bluetooth(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLUETOOTH, BLUETOOTH_DEFAULT)

    fun setBluetooth(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BLUETOOTH, enabled)
            .apply()
    }
}

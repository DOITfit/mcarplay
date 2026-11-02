package com.mcarplay.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.mcarplay.app.bt.TargetDevicePrefs
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 CarWith 原生 PCM（已由 [CarLifeAudioBridge] 重采样/混音）定向播到车机
 * 蓝牙 A2DP，而不是 AirPlay。
 *
 * 只选择与「目标车机」地址匹配的 A2DP 输出；未指定目标时取第一个 A2DP。
 * 找不到 A2DP 就返回启动失败，让调用方回退 AirPlay——绝不能把音乐播到
 * 蓝牙耳机或手机扬声器上。
 *
 * 写入在独立线程进行（[AudioTrack.WRITE_BLOCKING]），队列只保留约 8 个
 * 40ms 块，避免 A2DP 链路抖动时把延迟堆到秒级。
 */
class BluetoothAudioSink(
    private val context: Context,
    private val sampleRate: Int,
    private val log: (String) -> Unit,
) : PcmSink {
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_LIMIT)
    private var track: AudioTrack? = null
    private var writer: Thread? = null

    override fun startSink(): Boolean {
        if (running.get()) return true
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val device = findTargetA2dp(manager) ?: return false
        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBuffer <= 0) {
            log("AUDIO: A2DP 缓冲参数无效（minBuffer=$minBuffer）——回退 AirPlay")
            return false
        }
        val output =
            runCatching {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    ).setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_FRAMES_PER_BUFFER * FRAME_BYTES))
                    .build()
            }.getOrElse {
                log("AUDIO: A2DP AudioTrack 创建失败 - ${it.message}")
                return false
            }
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { output.release() }
            log("AUDIO: A2DP AudioTrack 未初始化（state=${output.state}）——回退 AirPlay")
            return false
        }
        if (!runCatching { output.setPreferredDevice(device) }.getOrDefault(false)) {
            runCatching { output.release() }
            log("AUDIO: 无法把 AudioTrack 定向到车机 A2DP——回退 AirPlay")
            return false
        }
        track = output
        startWriter(output)
        output.play()
        log("AUDIO: 声音输出=车机蓝牙 A2DP（${sampleRate}Hz 立体声）；CarPlay 本次仅推视频")
        return true
    }

    override fun submitPcm(pcm: ByteArray) {
        if (!running.get() || pcm.isEmpty()) return
        // 队列满时丢最旧的一块：A2DP 链路卡顿时宁可短促丢音，也不要累积延迟。
        val block = pcm.copyOf()
        if (!queue.offer(block)) {
            queue.poll()
            queue.offer(block)
        }
    }

    override fun stopSink() {
        if (!running.getAndSet(false)) {
            runCatching { track?.release() }
            track = null
            return
        }
        queue.clear()
        writer?.interrupt()
        writer = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }

    private fun startWriter(output: AudioTrack) {
        running.set(true)
        writer =
            Thread(
                {
                    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
                    try {
                        while (running.get()) {
                            val pcm = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                            val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) {
                                log("AUDIO: A2DP 写入失败（$written）——停止蓝牙输出")
                                break
                            }
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (error: Exception) {
                        log("AUDIO: A2DP 输出异常 - ${error.message}")
                    } finally {
                        running.set(false)
                    }
                },
                "bt-audio-out",
            ).apply {
                isDaemon = true
                start()
            }
    }

    private fun findTargetA2dp(manager: AudioManager): AudioDeviceInfo? {
        val outputs =
            runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
                .getOrElse {
                    log("AUDIO: 枚举音频输出失败 - ${it.message}")
                    return null
                }
        val a2dp = outputs.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (a2dp.isEmpty()) {
            log("AUDIO: 车机 A2DP 未连接（无线 CarPlay 常会主动断开蓝牙音频）——回退 AirPlay")
            return null
        }
        val target = TargetDevicePrefs.get(context)
        if (target == null) return a2dp.first()
        val matched = a2dp.firstOrNull { it.address.equals(target.address, ignoreCase = true) }
        if (matched == null) {
            log("AUDIO: 已连接的 A2DP 设备中没有目标车机——回退 AirPlay")
        }
        return matched
    }

    private companion object {
        /** 8 个约 40ms 的块 ≈ 320ms 上限。 */
        const val QUEUE_LIMIT = 8

        const val POLL_MILLIS = 100L

        /** AudioTrack 内部缓冲约 2048 帧（48k 下 ~43ms），允许两层。 */
        const val SAMPLE_FRAMES_PER_BUFFER = 2048

        const val FRAME_BYTES = 4
    }
}

# 车机蓝牙（A2DP）声音输出

## 功能

「设置 → 显示 → 声音输出到车机蓝牙」打开后：

1. 连接弹窗仍需选择「开启声音」；
2. CarWith 在 9240/9241/9242 送来的原生 PCM 仍先在本机重采样/混音/导航压低；
3. 混音结果不再走 AirPlay RTP，而是写入 `AudioTrack`，并把输出设备定向到
   与「目标车机」地址匹配的 **Bluetooth A2DP**；
4. 本次 CarPlay 会话只有视频流（不协商 AirPlay 音频流），避免双声。

实现：[BluetoothAudioSink.kt](../app/src/main/java/com/mcarplay/app/audio/BluetoothAudioSink.kt)、
[CarLifeAudioBridge.kt](../app/src/main/java/com/mcarplay/app/audio/CarLifeAudioBridge.kt)、
[AudioOutputPrefs.kt](../app/src/main/java/com/mcarplay/app/audio/AudioOutputPrefs.kt)。

## 前提与失败回退

- 车机必须在 CarPlay 会话期间保持 **A2DP 连接**，且「目标车机」地址与 A2DP 输出设备匹配；
  未指定目标时取第一个 A2DP。
- 无线 CarPlay 的 iAP2 建立阶段，部分车机（奥迪等）要求先断开 HFP/A2DP；mcarplay 的
  `BtProfiles` 也可能主动断开。A2DP 不在时启动失败，自动回退 AirPlay，并记录
  `AUDIO: 车机蓝牙 A2DP 不可用——回退 AirPlay`。
- 不要把它当成手机本机音乐播放器：它重定向的是 **CarWith 原生音频**（媒体 + 导航提示
  混音后的 PCM），不是任意第三方播放器的输出。
- A2DP 的端到端延迟由车机编码器/缓冲决定，通常明显高于 AirPlay；对导航提示与音乐
  同步要求高的场景建议保持 AirPlay。

## 验证状态

本功能只做了编译、静态检查与 JVM 单测；尚未在实车上验证 A2DP 与无线 CarPlay 的共存。
需要记录：车型/车机固件、A2DP 是否在会话期间保持、声音是否正常、切换 AirPlay/BT 的
对比，以及是否出现 CarPlay 拆链。不要在没有日志的情况下声称某车机支持。

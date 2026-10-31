# CarWith APK 编码路径静态分析（2026-11-01）

本文是对随任务提供的 `CarWith-4.0.23-20261016(104000023).apk` 的**静态分析**记录，不是实车证据，
也不能单独作为某款车机“已支持/已优化”的结论。

- 包名：`com.miui.carlink`
- versionName：`4.0.23-20261016`（versionCode `104000023`）
- SHA-256：`23bc945cdca484e8a5e52325ccfa33b91dcd09b540000ee994fc9799b4d1b54d`
- 工具：jadx 1.5.2（`--no-res`），只读分析；APK 未纳入仓库。

## 1. CarLife HU → CarWith 的编码参数

CarWith 的 CarLife 控制器在 `com.xiaomi.ucar.carlife.k`：

- `carlife_encode_format` 分支读取 HU 发来的 JSON：`width`、`height`、`real_width`、`real_height`
  （由 CarLife SDK 把 HU 的 `VIDEO_ENCODER_INIT` 转换成该 Intent）。
- `q()` 根据这些尺寸与 CarWith 自己保存的 `current/default resolution` 决定最终 `width/height`；
  HU 改变请求尺寸时可触发 `auto adapt to CarLife recommended resolution`。
- `user_setting_framerate` 来自 CarWith 自己的 SharedPreferences，默认值 **1**；
  `com.carwith.common.utils.u2.b()` 的映射是 `0→15, 1→20, 2→30, 3→60`。
  因此**默认编码帧率是 20fps**，与`docs/CARWITH-ENTRY-FINDINGS.md` 中实测 19–20fps 一致。
  该值由 CarWith 设置页（15/20/30/60）控制，HU 请求里的 `fps` 字段不会覆盖它。
- 编码 Bundle 还写入 `i-frame-interval=2`，并从 CarWith 自己的设置读取
  `video_profile` / `video_level` / `video_rate` / `video_model`（默认均为 `-1`/`3`，即不覆盖硬件默认）。
- 某些通道下 CarWith 会把虚拟屏尺寸 clamp 到 `1024x576`。

结论：**HU 能影响编码分辨率，不能通过该协议改 CarWith 的帧率或码率**；降低分辨率是降低码率/解码负载的
主要手段。

## 2. EasyConn/MiCast 的 EncodeQuality（不是 CarLife HU 路径）

CarWith 内还有 EasyConn 的 `net.easyconn.carman.utils.EncodeQuality`：

- `HIGH`：60fps，使用屏幕/编码器原始尺寸；
- `MIDDLE`：36fps，1920×1080；
- `LOW`：36fps，1280×720。

它由 EasyConn 自己的设置/SharedPreferences 控制，与本项目使用的 CarLife 七路协议不是同一条控制路径；
分析它可以帮助理解 CarWith 的画质档位，但不能由 mcarplay 直接设置。

## 3. 对 mcarplay 的优化结论

1. `VIDEO_ENCODER_INIT` 的 `width/height` 是有效的画质杠杆：低配车机应请求更小的长边。
   本项目把「显示 → 分辨率缩放」同时用于约束该请求（默认 100% 仍为 1920 长边），
   50–67% 可把 1920×1080 降到约 960×540 / 1280×720。
2. 不应承诺由 mcarplay 提高 CarWith 的帧率：CarWith 默认 20fps，要 30/60 必须改 CarWith 自身设置。
   低配车机反而应保持 20fps，避免解码器/网络过载。
3. 码率由 CarWith 的 `video_rate` 或硬件默认决定；降低分辨率会按比例降低实际码率。
4. 如果 `VIDEO_INIT_DONE` 返回的面积大于请求值，mcarplay 会记录一条日志提示用户
   调低「分辨率缩放」或 CarWith 自身画质设置。

以上结论仅来自 APK 静态分析；实际收益需要在目标车机上记录分辨率、帧率、掉帧和主观流畅度后，
再补入 `docs/CARWITH-ENTRY-FINDINGS.md` 的日期化设备证据。

## 4. 已落地的 mcarplay 低配模式

- `CompatibilityPrefs.lowEndMode`（默认关）可在「显示」面板打开「低配车机模式」：
  向 CarWith 请求的编码长边不超过 **1280**（约 720p），合成器目标帧率不超过 **30fps**；
  两者都在下一次连接建立时生效。
- 未开启时仍按「分辨率缩放」计算请求长边（默认 100% → 1920），行为与之前一致。
- 若 `VIDEO_INIT_DONE` 返回的面积仍大于请求值，日志会提示调低缩放/低配模式或 CarWith 自身画质设置。

## 5. 2026-11-01 现场日志（APK sha256 d1b863…，CarWith 4.0.23 / CarLife 8.8.5）

- 接收端：WinPlay（Roylyl，`/info` display=1280×720@60，model 已脱敏）。
- `DISPLAY: 流 1280x720 …@30fps` → `CARLIFE: 请求编码尺寸 1280x720` →
  `VIDEO_INIT_DONE 1920x1080@30`：**CarWith 仍按自身保存的 1920×1080 编码，未采纳 HU 请求**。
  这是低配接收端最主要的额外解码/缩放负担。
- 实际转发约 34fps；`sourceFrames` 与 `videoFrames` 在 20s 采样点均为 476，说明 mcarplay
  没有丢帧，帧率由 CarWith 源决定。
- `AUDIO: 外部 PCM 发送线程落后过多，跳过 49 帧`（约 1.04s）出现一次，时间紧邻一次
  诊断报告导出；因此音频外部发送线程改为 `THREAD_PRIORITY_AUDIO`。
- 日志在 29.99s 后没有新视频/触摸记录，用户在 ~35s 导出报告；未能证明是接收端停止消费
  还是用户停止操作。若复现，应同时记录接收端画面状态与 `dumpsys`。

因此：mcarplay 的 `VIDEO_ENCODER_INIT` 请求在 CarWith 4.0.23 上不可靠；低配车机的第一
优先级仍是把 CarWith 自身分辨率改成 1280×720（设置 → 连接信息 → 分辨率），mcarplay 的
「低配车机模式」只是请求与兜底提示，不能保证 CarWith 采纳。

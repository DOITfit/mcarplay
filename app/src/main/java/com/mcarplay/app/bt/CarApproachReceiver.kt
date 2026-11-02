package com.mcarplay.app.bt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.mcarplay.app.MainActivity
import com.mcarplay.app.SessionController
import com.mcarplay.app.service.CarPlayService
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.DebugFileLog

/** 靠近车机时由接收器放进 Intent 的标记：主界面据此重新武装「启动后自动连接」。 */
const val EXTRA_CAR_APPROACH = "com.mcarplay.app.extra.CAR_APPROACH"

/**
 * 靠近车机自动启动（移植自 CarPToCarL 2026-10-30）。
 *
 * **静默检测**：只用系统 ACL_CONNECTED 广播（车机靠近时自己回连手机），不扫描、不轮询、
 * 不起常驻前台服务。清单静态注册，进程没起来也能收。
 *
 * **命中后只拉起主界面**：整条管线（CarLife 接管、虚拟屏/编码器、启动自检）挂在
 * Activity 上，直接在后台 `SessionController.startConnection` 会出现「握手成功但车机
 * 收不到数据」。所以这里把界面带到前台，带 [EXTRA_CAR_APPROACH]，由前台走与手动
 * 点「启动连接」完全相同的流程。
 *
 * 系统静默拒绝后台启动时不会抛异常；3.5 秒后核对 [AppUiPresence]，没到前台就发通知
 * 提示用户开「显示在其他应用上层」。通知只在失败时出现。
 */
class CarApproachReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(context, intent)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected(context, intent)
        }
    }

    private fun onConnected(
        context: Context,
        intent: Intent,
    ) {
        val target = TargetDevicePrefs.get(context) ?: return
        val address = addressOf(context, intent)
        if (address != null && address.equals(target.address, ignoreCase = true)) {
            CarApproachPrefs.setAclConnected(context, true)
        }
        val sessionRunning = SessionController.isSessionRunning() || SessionController.sessionStarting.get()
        val decision =
            CarApproachPolicy.onAclConnected(
                eventAddress = address,
                targetAddress = target.address,
                enabled = CarApproachPrefs.enabled(context) && AutoConnectPrefs.enabled(context),
                sessionRunning = sessionRunning,
                suppressed = CarApproachPrefs.suppressed(context),
                lastTriggerAt = CarApproachPrefs.lastTriggerAt(context),
                now = System.currentTimeMillis(),
            )
        if (decision != CarApproachPolicy.Decision.TRIGGER) {
            if (decision == CarApproachPolicy.Decision.SUPPRESSED ||
                decision == CarApproachPolicy.Decision.SESSION_RUNNING
            ) {
                DebugFileLog.append(
                    context,
                    "APPROACH: 车机已靠近，但" +
                        (if (decision == CarApproachPolicy.Decision.SESSION_RUNNING) "已有会话" else "刚手动断开过") +
                        "——跳过本次自动启动",
                )
            }
            return
        }

        CarApproachPrefs.markTriggered(context)
        val launched = launchApp(context)
        DebugFileLog.append(
            context,
            "APPROACH: 检测到目标车机靠近——" +
                if (launched) "已拉起 App，交给前台自动发起连接" else "拉起 App 被系统拒绝（需允许「自启动/后台弹界面」）",
        )
    }

    private fun onDisconnected(
        context: Context,
        intent: Intent,
    ) {
        val target = TargetDevicePrefs.get(context) ?: return
        val address = addressOf(context, intent) ?: return
        if (!address.equals(target.address, ignoreCase = true)) return
        CarApproachPrefs.setAclConnected(context, false)
        if (CarApproachPrefs.suppressed(context) || CarApproachPrefs.lastTriggerAt(context) > 0L) {
            CarApproachPrefs.setSuppressed(context, false)
            CarApproachPrefs.markTriggered(context, 0L)
            DebugFileLog.append(context, "APPROACH: 车机蓝牙链路断开——已复位，下次靠近可再次自动启动")
        }
        // 链路断开 = 人离开/熄火：沿用既有「下车自动收摊」；若策略要求保留网络会话，
        // CarPlayService 内部会自己判断。
        runCatching {
            context.startService(
                Intent(context, CarPlayService::class.java)
                    .setAction(CarPlayService.ACTION_AUTO_STOP_SESSION),
            )
        }
    }

    private fun launchApp(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            DebugFileLog.append(
                context,
                "APPROACH: 未授予「显示在其他应用上层」——系统可能静默拒绝后台打开界面",
            )
        }
        val launchedAt = System.currentTimeMillis()
        val launch =
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra(EXTRA_CAR_APPROACH, true)
            }
        val ok =
            runCatching { context.startActivity(launch) }
                .onFailure {
                    DebugFileLog.append(
                        context,
                        "APPROACH: 后台拉起 App 被系统拒绝（${it.javaClass.simpleName}）——请允许「自启动/后台弹界面」",
                    )
                }.isSuccess
        if (ok) verifyBroughtUp(context, launchedAt)
        return ok
    }

    private fun verifyBroughtUp(
        context: Context,
        launchedAt: Long,
    ) {
        Bg.run("approach-verify") {
            Thread.sleep(3_500L)
            if (AppUiPresence.lastBringUpAt >= launchedAt) return@run
            DebugFileLog.append(context, "APPROACH: 系统没有让 App 到前台（后台启动被拦）——已发通知提示授权")
            runCatching { notifyLaunchBlocked(context) }
        }
    }

    private fun notifyLaunchBlocked(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                BLOCKED_CHANNEL_ID,
                "靠近车机提示",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "系统拦下后台打开界面时的提示"
                setShowBadge(false)
                setSound(null, null)
            },
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val hint =
            "系统拒绝了「后台打开界面」，本次没能自动连上。请到 设置 → 应用 → 特殊访问权限 → " +
                "显示在其他应用上层，允许本应用；或点这里手动打开。"
        manager.notify(
            BLOCKED_NOTIFICATION_ID,
            NotificationCompat
                .Builder(context, BLOCKED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("车机已靠近，但系统没让 App 到前台")
                .setContentText(hint)
                .setStyle(NotificationCompat.BigTextStyle().bigText(hint))
                .setContentIntent(open)
                .addAction(0, "打开界面", open)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun addressOf(
        context: Context,
        intent: Intent,
    ): String? =
        runCatching { deviceOf(intent)?.address }.getOrElse { error ->
            if (error is SecurityException) {
                DebugFileLog.append(context, "APPROACH: 缺少蓝牙权限，无法识别靠近/离开的车机（请在 App 内授权）")
            }
            null
        }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val BLOCKED_CHANNEL_ID = "carplay_approach"
        const val BLOCKED_NOTIFICATION_ID = 2
    }
}

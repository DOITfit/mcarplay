package com.mcarplay.app.bt

import android.content.Context

/**
 * 「靠近车机自动启动」偏好（移植自 CarPToCarL 2026-10-30）。
 *
 * 与 [AutoConnectPrefs] 是同一件事的两层：
 * - 本类决定**怎么被唤醒**（监听系统蓝牙 ACL，不扫描、不轮询）；
 * - [AutoConnectPrefs] 决定**唤醒后要不要发起连接**。
 */
object CarApproachPrefs {
    /** 默认开：只有已指定目标车机时才可能触发，不会误连测试台/耳机。 */
    const val DEFAULT_ENABLED = true

    private const val PREFS = "settings"
    private const val KEY_ENABLED = "car_approach_autostart"
    private const val KEY_SILENT_UI = "car_approach_silent_ui"
    private const val KEY_LAST_TRIGGER = "car_approach_last_trigger_at"
    private const val KEY_SUPPRESSED = "car_approach_suppressed"
    private const val KEY_SUPPRESSED_AT = "car_approach_suppressed_at"
    private const val KEY_ACL_CONNECTED = "car_approach_acl_connected"

    fun enabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /**
     * 触发后是否把界面退到后台（默认关）。
     *
     * 管线（CarLife 接管、虚拟屏/编码器、启动自检）挂在 Activity 上，不能直接后台连；
     * 「静默」= 界面完整建好后再按 Home，连接和状态留在通知栏。
     */
    fun silentUi(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SILENT_UI, false)

    fun setSilentUi(
        context: Context,
        silent: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SILENT_UI, silent)
            .apply()
    }

    fun lastTriggerAt(context: Context): Long =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_TRIGGER, 0L)

    fun markTriggered(
        context: Context,
        at: Long = System.currentTimeMillis(),
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_TRIGGER, at)
            .apply()
    }

    /** 目标车机 ACL 当前是否连着（落盘：收摊判断可能发生在新进程里）。 */
    fun aclConnected(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACL_CONNECTED, false)

    fun setAclConnected(
        context: Context,
        connected: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACL_CONNECTED, connected)
            .apply()
    }

    /** 用户手动断开/取消后的静默窗口（带时限，避免人在车里永远唤不醒）。 */
    fun suppressed(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarApproachPolicy.suppressionActive(
            suppressed = prefs.getBoolean(KEY_SUPPRESSED, false),
            suppressedAt = prefs.getLong(KEY_SUPPRESSED_AT, 0L),
            now = now,
        )
    }

    fun setSuppressed(
        context: Context,
        suppressed: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SUPPRESSED, suppressed)
            .putLong(KEY_SUPPRESSED_AT, if (suppressed) at else 0L)
            .apply()
    }
}

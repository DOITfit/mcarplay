package com.mcarplay.app.bt

/**
 * 「靠近车机自动启动」的判定逻辑（纯函数，便于单测）。
 *
 * 静默约束：只听系统 ACL 广播（车机靠近时自己回连手机），**不扫描、不轮询、不主动探测**。
 */
object CarApproachPolicy {
    /** 同一次靠近最多触发一次：车机偶发断连重连时不反复拉起。 */
    const val RETRIGGER_INTERVAL_MILLIS = 90_000L

    /** 用户手动断开/取消后的静默窗口；ACL 断开（人离开）时提前解除。 */
    const val SUPPRESS_AFTER_MANUAL_STOP_MILLIS = 10 * 60_000L

    enum class Decision {
        TRIGGER,
        DISABLED,
        NO_TARGET,
        NOT_TARGET,
        SESSION_RUNNING,
        SUPPRESSED,
        THROTTLED,
    }

    fun suppressionActive(
        suppressed: Boolean,
        suppressedAt: Long,
        now: Long,
    ): Boolean {
        if (!suppressed) return false
        if (suppressedAt <= 0L) return true
        val elapsed = now - suppressedAt
        // 系统时间被往回调时 elapsed 为负：当作刚置位，继续安静。
        if (elapsed < 0L) return true
        return elapsed < SUPPRESS_AFTER_MANUAL_STOP_MILLIS
    }

    fun onAclConnected(
        eventAddress: String?,
        targetAddress: String?,
        enabled: Boolean,
        sessionRunning: Boolean,
        suppressed: Boolean,
        lastTriggerAt: Long,
        now: Long,
    ): Decision {
        if (!enabled) return Decision.DISABLED
        if (targetAddress.isNullOrBlank()) return Decision.NO_TARGET
        val address = eventAddress?.takeIf { it.isNotBlank() } ?: return Decision.NOT_TARGET
        if (!address.equals(targetAddress, ignoreCase = true)) return Decision.NOT_TARGET
        if (sessionRunning) return Decision.SESSION_RUNNING
        if (suppressed) return Decision.SUPPRESSED
        if (lastTriggerAt > 0L) {
            val elapsed = now - lastTriggerAt
            // 用 `<` 而不是区间：系统时间回拨时 elapsed 为负，也按「刚触发过」处理。
            if (elapsed < RETRIGGER_INTERVAL_MILLIS) return Decision.THROTTLED
        }
        return Decision.TRIGGER
    }
}

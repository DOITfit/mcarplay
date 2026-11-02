package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarApproachPolicyTest {
    private val now = 1_000_000L
    private val target = "AA:BB:CC:DD:EE:FF"

    private fun decide(
        address: String? = target,
        targetAddress: String? = target,
        enabled: Boolean = true,
        sessionRunning: Boolean = false,
        suppressed: Boolean = false,
        lastTriggerAt: Long = 0L,
        at: Long = now,
    ) = CarApproachPolicy.onAclConnected(
        eventAddress = address,
        targetAddress = targetAddress,
        enabled = enabled,
        sessionRunning = sessionRunning,
        suppressed = suppressed,
        lastTriggerAt = lastTriggerAt,
        now = at,
    )

    @Test
    fun targetAclTriggers() {
        assertEquals(CarApproachPolicy.Decision.TRIGGER, decide())
    }

    @Test
    fun disabledOrMissingTargetDoesNothing() {
        assertEquals(CarApproachPolicy.Decision.DISABLED, decide(enabled = false))
        assertEquals(CarApproachPolicy.Decision.NO_TARGET, decide(targetAddress = null))
        assertEquals(CarApproachPolicy.Decision.NO_TARGET, decide(targetAddress = " "))
    }

    @Test
    fun otherDeviceOrMissingAddressIsNotTarget() {
        assertEquals(CarApproachPolicy.Decision.NOT_TARGET, decide(address = "11:22:33:44:55:66"))
        assertEquals(CarApproachPolicy.Decision.NOT_TARGET, decide(address = null))
    }

    @Test
    fun runningSessionAndSuppressionWin() {
        assertEquals(CarApproachPolicy.Decision.SESSION_RUNNING, decide(sessionRunning = true))
        assertEquals(CarApproachPolicy.Decision.SUPPRESSED, decide(suppressed = true))
    }

    @Test
    fun retriggerIsThrottledForNinetySeconds() {
        assertEquals(
            CarApproachPolicy.Decision.THROTTLED,
            decide(lastTriggerAt = now - CarApproachPolicy.RETRIGGER_INTERVAL_MILLIS + 1),
        )
        assertEquals(
            CarApproachPolicy.Decision.TRIGGER,
            decide(lastTriggerAt = now - CarApproachPolicy.RETRIGGER_INTERVAL_MILLIS),
        )
    }

    @Test
    fun clockRollbackCountsAsJustTriggered() {
        assertEquals(CarApproachPolicy.Decision.THROTTLED, decide(lastTriggerAt = now + 60_000L))
    }

    @Test
    fun suppressionExpiresAndClockRollbackKeepsQuiet() {
        assertTrue(CarApproachPolicy.suppressionActive(true, suppressedAt = now, now = now + 1_000L))
        assertFalse(
            CarApproachPolicy.suppressionActive(
                true,
                suppressedAt = now,
                now = now + CarApproachPolicy.SUPPRESS_AFTER_MANUAL_STOP_MILLIS,
            ),
        )
        assertTrue(
            CarApproachPolicy.suppressionActive(
                true,
                suppressedAt = now,
                now = now - 60_000L,
            ),
        )
        assertFalse(CarApproachPolicy.suppressionActive(false, suppressedAt = now, now = now))
        assertTrue(CarApproachPolicy.suppressionActive(true, suppressedAt = 0L, now = now))
    }
}

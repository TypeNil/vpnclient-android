package dev.typenil.vpnclient.core.engine.singbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficReadinessTest {
    @Test fun `post-readiness statuses stay valid and a new session resets the latch`() {
        val gate = TrafficReadiness()
        assertFalse(gate.accept(false)) // Pre-readiness zeros stay hidden.
        assertTrue(gate.accept(true)) // First available status latches.
        assertTrue(gate.accept(false)) // Idle/resume snapshots keep the chart fed.
        assertTrue(gate.accept(false))
        gate.reset()
        assertFalse(gate.accept(false)) // A fresh session must not inherit readiness.
        assertTrue(gate.accept(true))
    }
}

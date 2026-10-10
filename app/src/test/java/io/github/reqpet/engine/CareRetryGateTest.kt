package io.github.reqpet.engine

import io.github.reqpet.engine.task.CareRetryGate
import org.junit.Assert.assertEquals
import org.junit.Test

class CareRetryGateTest {
    @Test
    fun failuresBackOffAndSessionResetClearsDelay() {
        val gate = CareRetryGate()
        assertEquals(0L, gate.waitMillis(1000L))
        gate.failed(1000L, 0L)
        assertEquals(180_000L, gate.waitMillis(1000L))
        gate.failed(1000L, 1000L)
        assertEquals(361_000L, gate.waitMillis(1000L))
        repeat(10) { gate.failed(1000L, 0L) }
        assertEquals(1_800_000L, gate.waitMillis(1000L))
        gate.reset()
        assertEquals(0L, gate.waitMillis(1000L))
    }
}

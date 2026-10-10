package io.github.reqpet.engine.task

import io.github.reqpet.engine.utils.randomJitter

internal class CareRetryGate {
    private var failures = 0
    private var retryAt = 0L

    fun waitMillis(now: Long): Long = (retryAt - now).coerceAtLeast(0L)

    fun failed(now: Long, jitterMillis: Long = randomJitter(0L, 30_000L)) {
        failures = (failures + 1).coerceAtMost(5)
        val delay = (180_000L shl (failures - 1)).coerceAtMost(1_800_000L)
        retryAt = now + delay + jitterMillis.coerceIn(0L, 30_000L)
    }

    fun reset() {
        failures = 0
        retryAt = 0L
    }
}

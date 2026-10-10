package io.github.reqpet.engine

import io.github.reqpet.engine.task.PetPkTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetPkTimingTest {
    @Test
    fun longerBattleIsNotTruncatedToFiveSeconds() {
        assertEquals(60000L, PetPkTask.settlementDelayMillis(60L))
    }

    @Test
    fun shortBattleRespectsPluginSettlementFloor() {
        assertEquals(6800L, PetPkTask.settlementDelayMillis(5L))
        assertEquals(6800L, PetPkTask.settlementDelayMillis(0L))
    }

    @Test
    fun largeDurationCannotOverflowIntoImmediateSettlement() {
        assertTrue(PetPkTask.settlementDelayMillis(Long.MAX_VALUE) > 60000L)
    }
}

package io.github.reqpet.engine

import io.github.reqpet.engine.task.BathCareRunner
import io.github.reqpet.engine.task.BathOperations
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BathCareRunnerTest {
    private class Operations : BathOperations {
        var inventoryCode = 0
        var balance = 0
        var purchases = 0
        var bought = 0
        var baths = 0
        var clean = 0
        var bathCode = 0
        var progress = 10
        var visiblePurchase = true
        override suspend fun configs() = 0 to listOf(QQPetDirectBridge.BathItemConfig("2010104", "香皂", 5, 10, 500))
        override suspend fun inventory() = inventoryCode to mapOf("2010104" to balance)
        override suspend fun buy(itemId: String, count: Int): Triple<Int, Int, String?> {
            purchases++
            bought = count
            if (visiblePurchase) balance += count
            return Triple(0, 1, null)
        }
        override suspend fun bath(itemId: String): QQPetDirectBridge.BathResult {
            baths++
            if (bathCode != 0) return QQPetDirectBridge.BathResult(bathCode, -1, 0, -1, false)
            clean += progress
            balance--
            return QQPetDirectBridge.BathResult(0, clean, progress, balance, false)
        }
        override suspend fun pause() {}
    }

    private fun runner(operations: Operations) = BathCareRunner(operations, TaskLogger { _, _ -> })

    @Test
    fun buysOnlyActualGapEvenWhenMallDefaultIs500() = runBlocking {
        val operations = Operations()
        val result = runner(operations).run(0, 100, 60)
        assertEquals(0, result.code)
        assertEquals(6, operations.bought)
        assertEquals(1, operations.purchases)
        assertEquals(6, operations.baths)
        assertEquals(60, result.newClean)
    }

    @Test
    fun unknownInventoryAndAttributesDoNotBuy() = runBlocking {
        val operations = Operations().apply { inventoryCode = -99 }
        assertEquals(-99, runner(operations).run(0, 100, 60).code)
        assertEquals(-104, runner(operations).run(-1, 100, 60).code)
        assertEquals(0, operations.purchases)
        assertEquals(0, operations.baths)
    }

    @Test
    fun failedBathWithStockNeverTriggersPurchase() = runBlocking {
        val operations = Operations().apply { balance = 500; bathCode = -99 }
        assertEquals(-99, runner(operations).run(0, 100, 60).code)
        assertEquals(0, operations.purchases)
        assertEquals(1, operations.baths)
    }

    @Test
    fun missingDeliveryAndNoProgressStopImmediately() = runBlocking {
        val missing = Operations().apply { visiblePurchase = false }
        assertEquals(-104, runner(missing).run(0, 100, 60).code)
        assertEquals(1, missing.purchases)
        assertEquals(0, missing.baths)
        val stalled = Operations().apply { balance = 500; progress = 0 }
        assertEquals(-104, runner(stalled).run(0, 100, 60).code)
        assertEquals(1, stalled.baths)
        assertEquals(0, stalled.purchases)
    }

    @Test
    fun stopsAfterOnePurchaseOrStepLimitWithoutReportingSuccess() = runBlocking {
        val short = Operations().apply { progress = 1 }
        assertEquals(-104, runner(short).run(0, 100, 60).code)
        assertEquals(1, short.purchases)
        assertEquals(6, short.baths)
        val stocked = Operations().apply { progress = 1; balance = 500 }
        assertEquals(-104, runner(stocked).run(0, 100, 60).code)
        assertEquals(12, stocked.baths)
    }
}

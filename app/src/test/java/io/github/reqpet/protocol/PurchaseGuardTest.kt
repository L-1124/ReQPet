package io.github.reqpet.protocol

import org.junit.Assert.*
import org.junit.Test

class PurchaseGuardTest {
    internal class MemoryStore : PurchaseGuard.Store {
        val states = mutableMapOf<String, PurchaseGuard.State>()
        var writable = true
        override fun read(uin: String) = states[uin] ?: PurchaseGuard.State()
        override fun write(uin: String, state: PurchaseGuard.State): Boolean {
            if (!writable) return false
            states[uin] = state
            return true
        }
    }

    @Test
    fun allItemsShareDailyBudgetAndCooldown() {
        val store = MemoryStore()
        val guard = PurchaseGuard(store)
        val first = guard.reserve("12345", "bath:2010104", 6, 1000L, "2026-10-10").first!!
        guard.complete(first, PurchaseGuard.Outcome.SUCCESS)
        assertNull(guard.reserve("12345", "food:9990032", 4, 1001L, "2026-10-10").first)
        val later = 1000L + PurchaseGuard.COOLDOWN_MILLIS
        val second = guard.reserve("12345", "food:9990032", 4, later, "2026-10-10").first!!
        guard.complete(second, PurchaseGuard.Outcome.SUCCESS)
        assertEquals(10, store.read("12345").count)
        var nextAttempt = later
        repeat(4) { index ->
            nextAttempt += PurchaseGuard.COOLDOWN_MILLIS
            val item = if (index % 2 == 0) "bath:2010104" else "food:9990032"
            val reservation = guard.reserve("12345", item, 10, nextAttempt, "2026-10-10").first!!
            guard.complete(reservation, PurchaseGuard.Outcome.SUCCESS)
        }
        assertEquals(50, store.read("12345").count)
        assertNull(guard.reserve("12345", "bath:2010104", 1,
            nextAttempt + PurchaseGuard.COOLDOWN_MILLIS, "2026-10-10").first)
        assertNotNull(guard.reserve("67890", "bath:2010104", 10, later, "2026-10-10").first)
    }

    @Test
    fun unknownOrderSurvivesRestartAndDayChangeUntilInventoryConfirmsIncrease() {
        val store = MemoryStore()
        val first = PurchaseGuard(store).reserve("12345", "food:9990032", 5, 1000L,
            "2026-10-10", baseline = 3).first!!
        PurchaseGuard(store).complete(first, PurchaseGuard.Outcome.UNKNOWN)
        val restarted = PurchaseGuard(store)
        assertNull(restarted.reserve("12345", "bath:2010104", 1, 100_000_000L, "2026-10-11").first)
        restarted.observeInventory("12345", "bath:2010104", 500)
        restarted.observeInventory("67890", "food:9990032", 500)
        restarted.observeInventory("12345", "food:9990032", 7)
        assertEquals(5, store.read("12345").pendingCount)
        restarted.observeInventory("12345", "food:9990032", 8)
        assertEquals(0, store.read("12345").pendingCount)
        assertNotNull(restarted.reserve("12345", "bath:2010104", 10, 100_000_000L, "2026-10-11").first)
    }

    @Test
    fun confirmedRejectionReleasesBudgetButKeepsCooldown() {
        val store = MemoryStore()
        val guard = PurchaseGuard(store)
        val first = guard.reserve("12345", "bath:2010104", 10, 1000L, "2026-10-10").first!!
        guard.complete(first, PurchaseGuard.Outcome.REJECTED)
        assertEquals(0, store.read("12345").count)
        assertNull(guard.reserve("12345", "food:9990032", 10, 1001L, "2026-10-10").first)
        val second = guard.reserve("12345", "food:9990032", 5,
            1000L + PurchaseGuard.COOLDOWN_MILLIS, "2026-10-10").first!!
        guard.complete(first, PurchaseGuard.Outcome.SUCCESS)
        assertEquals(second.itemId, store.read("12345").pendingItemId)
    }

    @Test
    fun unsafeQuantityAndStorageFailureNeverReserve() {
        val store = MemoryStore()
        val guard = PurchaseGuard(store)
        for (count in listOf(-1, 0, 11, 500, Int.MAX_VALUE)) {
            assertNull(guard.reserve("12345", "food:9990032", count).first)
        }
        assertTrue(store.states.isEmpty())
        store.writable = false
        assertNull(guard.reserve("12345", "food:9990032", 5).first)
        val broken = PurchaseGuard(object : PurchaseGuard.Store {
            override fun read(uin: String): PurchaseGuard.State = error("unreadable")
            override fun write(uin: String, state: PurchaseGuard.State) = false
        })
        assertNull(broken.reserve("12345", "food:9990032", 5).first)
    }

    @Test
    fun clockRollbackDoesNotResetDailyQuota() {
        val store = MemoryStore().apply {
            states["12345"] = PurchaseGuard.State(day = "2026-10-10", count = 50, lastAttemptMillis = 1000L)
        }
        val guard = PurchaseGuard(store)
        assertNull(guard.reserve("12345", "food:9990032", 1, 100_000_000L, "2026-10-09").first)
    }
}

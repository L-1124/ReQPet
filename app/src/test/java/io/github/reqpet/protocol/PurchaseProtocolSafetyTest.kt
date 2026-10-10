package io.github.reqpet.protocol

import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.channel.ProtocolBreakers
import io.github.reqpet.protocol.client.PetBathProtocolClient
import io.github.reqpet.protocol.client.PetCareProtocolClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.Base64

class PurchaseProtocolSafetyTest {
    private var previousUin = ""
    private val uin = "12345"
    private val petId = Base64.getEncoder().encodeToString("12345-4-2-123456789".toByteArray())
    private val store = PurchaseGuardTest.MemoryStore()
    private val guard = PurchaseGuard(store)
    private val purchases = mutableListOf<String>()
    private var inventoryCode = 0
    private var inventoryPresent = true
    private var balance = 0L
    private var purchaseCode = 0
    private var orderResult = 1L
    private var switchAccount = false
    private val channel = object : OidbChannel(PurchaseProtocolSafetyTest::class.java.classLoader!!) {
        override fun sendOidb(commandName: String, command: Int, subCommand: Int, request: ByteArray,
            callback: (Int, ByteArray?, String?) -> Unit): Int {
            when (command) {
                39922 -> {
                    if (switchAccount) PetAdventureEngine.currentActiveUin = "67890"
                    val entry = ProtoWire.message().writeString(1, "2010104").writeVarint(2, balance).toByteArray()
                    val root = ProtoWire.message().writeBytes(1, entry).toByteArray()
                    callback(inventoryCode, if (inventoryPresent) ProtoWire.message().writeBytes(1, root).toByteArray() else null, null)
                }
                39241 -> {
                    val entry = ProtoWire.message().writeString(4, "9990032").writeVarint(2, balance).toByteArray()
                    callback(inventoryCode, if (inventoryPresent) ProtoWire.message().writeBytes(4, entry).toByteArray() else null, null)
                }
                39888 -> {
                    purchases += "bath"
                    val item = ProtoWire.firstBytes(request, 3)!!
                    assertEquals(355L, ProtoWire.firstVarint(item, 1))
                    assertEquals(2010104L, ProtoWire.firstVarint(item, 2))
                    callback(purchaseCode, ProtoWire.message().writeVarint(1, orderResult).toByteArray(), null)
                }
                39391 -> {
                    purchases += "food"
                    assertEquals("9990032", ProtoWire.firstString(request, 3))
                    val count = ProtoWire.firstVarint(request, 1)!!
                    callback(purchaseCode, ProtoWire.message().writeVarint(1, count).writeVarint(3, count).toByteArray(), null)
                }
                else -> error("unexpected command $command")
            }
            return 1
        }
    }
    private val bath = PetBathProtocolClient(channel, guard)
    private val food = PetCareProtocolClient(channel, guard)

    @Before
    fun setUp() {
        previousUin = PetAdventureEngine.currentActiveUin
        PetAdventureEngine.currentActiveUin = uin
    }

    @After
    fun tearDown() { PetAdventureEngine.currentActiveUin = previousUin }

    @Test
    fun bothEntrancesEnforceSharedBudget() {
        store.states[uin] = PurchaseGuard.State(day = java.time.LocalDate.now().toString(), count = 44)
        bath.buyBathItem(petId, "2010104", 2) { code, order, _ -> assertEquals(0, code); assertEquals(1, order) }
        store.states[uin] = store.read(uin).copy(lastAttemptMillis = 0L)
        food.buyFood(petId, 5) { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        food.buyFood(petId, 4) { code, _, _ -> assertEquals(0, code) }
        assertEquals(listOf("bath", "food"), purchases)
        assertEquals(50, store.read(uin).count)
    }

    @Test
    fun overQuantityExistingStockAndFailedInventoryNeverBuy() {
        bath.buyBathItem(petId, "2010104", 500) { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        food.buyFood(petId, Long.MAX_VALUE) { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        balance = 500
        bath.buyBathItem(petId, "2010104") { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        food.buyFood(petId) { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        balance = 0
        inventoryCode = -99
        bath.buyBathItem(petId, "2010104") { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        food.buyFood(petId) { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        inventoryCode = 0
        inventoryPresent = false
        bath.buyBathItem(petId, "2010104") { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        food.buyFood(petId) { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        assertTrue(purchases.isEmpty())
    }

    @Test
    fun unknownOrderBlocksOtherItemEvenAfterRestart() {
        orderResult = 0
        bath.buyBathItem(petId, "2010104") { code, order, _ -> assertEquals(0, code); assertEquals(0, order) }
        store.states[uin] = store.read(uin).copy(lastAttemptMillis = 0L)
        val restarted = PetCareProtocolClient(channel, PurchaseGuard(store))
        restarted.buyFood(petId) { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        assertEquals(listOf("bath"), purchases)
        assertEquals(5, store.read(uin).pendingCount)
    }

    @Test
    fun accountSwitchDuringInventoryQueryDoesNotBuy() {
        switchAccount = true
        bath.buyBathItem(petId, "2010104") { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        assertTrue(purchases.isEmpty())
        assertTrue(store.states.isEmpty())
    }

    @Test
    fun noPersistentStoreMeansNoPurchase() {
        PetBathProtocolClient(channel).buyBathItem(petId, "2010104") { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        PetCareProtocolClient(channel).buyFood(petId) { code, _, _ -> assertEquals(PurchaseGuard.BLOCKED_CODE, code) }
        assertTrue(purchases.isEmpty())
    }

    @Test
    fun localCircuitRejectionDoesNotLeaveUnknownOrder() {
        purchaseCode = ProtocolBreakers.FAST_FAIL_CODE
        bath.buyBathItem(petId, "2010104") { code, _, _ -> assertEquals(ProtocolBreakers.FAST_FAIL_CODE, code) }
        assertEquals(0, store.read(uin).pendingCount)
        assertEquals(0, store.read(uin).count)
        store.states[uin] = store.read(uin).copy(lastAttemptMillis = 0L)
        food.buyFood(petId) { code, _, _ -> assertEquals(ProtocolBreakers.FAST_FAIL_CODE, code) }
        assertEquals(0, store.read(uin).pendingCount)
        assertEquals(0, store.read(uin).count)
    }

    @Test
    fun transportFailureKeepsUnknownOrder() {
        purchaseCode = -99
        food.buyFood(petId) { code, _, _ -> assertEquals(-99, code) }
        assertEquals(5, store.read(uin).pendingCount)
        assertEquals(5, store.read(uin).count)
    }
}

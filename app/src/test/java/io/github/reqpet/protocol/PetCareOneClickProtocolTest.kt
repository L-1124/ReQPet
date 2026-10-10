package io.github.reqpet.protocol

import io.github.reqpet.engine.task.PetCareTask
import kotlinx.coroutines.runBlocking
import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.client.PetCareProtocolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetCareOneClickProtocolTest {

    private fun createClient(
        onSend: (commandName: String, command: Int, subCommand: Int, request: ByteArray, callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit) -> Unit = { _, _, _, _, _ -> }
    ): PetCareProtocolClient {
        val channel = object : OidbChannel(ClassLoader.getSystemClassLoader(), null) {
            override fun sendOidb(
                commandName: String,
                command: Int,
                subCommand: Int,
                request: ByteArray,
                callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit
            ): Int {
                onSend(commandName, command, subCommand, request, callback)
                return 1
            }
        }
        return PetCareProtocolClient(channel)
    }

    @Test
    fun parseOneClickCareConfigExtractsThresholdAndGainConfig() {
        val client = createClient()

        val thresholdBytes = ProtoWire.message()
            .writeVarint(1, 15L) // mood
            .writeVarint(2, 20L) // hunger
            .writeVarint(3, 25L) // clean
            .toByteArray()

        val capBytes = ProtoWire.message()
            .writeVarint(1, 100L)
            .writeVarint(2, 85L)  // hungerCap
            .writeVarint(3, 90L)  // cleanCap
            .toByteArray()

        val gainBytes = ProtoWire.message()
            .writeVarint(1, 20L) // hungerPerBiscuit
            .writeFloat(4, 1.5f) // expPerHunger
            .writeVarint(5, 500L)// expCanGainHost
            .writeVarint(7, 25L) // cleanPerSoap
            .writeFloat(8, 2.0f) // expPerClean
            .writeBytes(9, capBytes)
            .toByteArray()

        val data = ProtoWire.message()
            .writeBytes(1, thresholdBytes)
            .writeBytes(2, gainBytes)
            .toByteArray()

        val config = client.parseOneClickCareConfig(data)
        assertEquals(20, config.hungerThreshold)
        assertEquals(25, config.cleanThreshold)
        assertEquals(15, config.moodThreshold)
        assertEquals(20, config.hungerPerBiscuit)
        assertEquals(25, config.cleanPerSoap)
        assertEquals(1.5f, config.expPerHunger, 0.01f)
        assertEquals(2.0f, config.expPerClean, 0.01f)
        assertEquals(85, config.hungerCap)
        assertEquals(90, config.cleanCap)
        assertEquals(500, config.dailyExpLimitHost)
    }

    @Test
    fun doOneClickCareParsesSuccessAndShortfallCodes() {
        val client = createClient()

        val successResultNode = ProtoWire.message()
            .writeVarint(1, 3L) // actualBiscuitCost
            .writeVarint(2, 2L) // actualSoapCost
            .toByteArray()

        val successData = ProtoWire.message()
            .writeVarint(1, 1L) // resultCode = 1
            .writeBytes(2, successResultNode)
            .toByteArray()

        val successRes = client.parseOneClickCareResult(successData)
        assertEquals(1, successRes.code)
        assertEquals(3, successRes.biscuitCostOrShortfall)
        assertEquals(2, successRes.soapCostOrShortfall)

        val shortfallResultNode = ProtoWire.message()
            .writeVarint(1, 5L) // biscuitShortfall
            .writeVarint(2, 4L) // soapShortfall
            .toByteArray()

        val shortfallData = ProtoWire.message()
            .writeVarint(1, 3L) // resultCode = 3
            .writeBytes(2, shortfallResultNode)
            .toByteArray()

        val shortfallRes = client.parseOneClickCareResult(shortfallData)
        assertEquals(3, shortfallRes.code)
        assertEquals(5, shortfallRes.biscuitCostOrShortfall)
        assertEquals(4, shortfallRes.soapCostOrShortfall)
    }

    @Test
    fun executeOneClickCareSkipsWhenAlreadyFull() = runBlocking {
        var networkCalled = false
        val channel = object : OidbChannel(ClassLoader.getSystemClassLoader(), null) {
            override fun sendOidb(
                commandName: String,
                command: Int,
                subCommand: Int,
                request: ByteArray,
                callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit
            ): Int {
                networkCalled = true
                return 1
            }
        }
        val bridge = QQPetDirectBridge(ClassLoader.getSystemClassLoader())
        val attrs = QQPetDirectBridge.PetAttributes(energy = 100f, maxEnergy = 100f, clean = 100f, maxClean = 100f)

        val res = PetCareTask.executeOneClickCareWithAutoBuyAwait(bridge, "pet_full", attrs) { _, _ -> }
        assertEquals(1, res.code)
        assertFalse(networkCalled)
    }
}

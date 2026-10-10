package io.github.reqpet.protocol

import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.client.PetPkProtocolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetPkProtocolClientTest {

    private fun createClient(
        onSend: (commandName: String, command: Int, subCommand: Int, request: ByteArray, callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit) -> Unit = { _, _, _, _, _ -> }
    ): PetPkProtocolClient {
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
        return PetPkProtocolClient(channel)
    }

    @Test
    fun queryFriendPkStatusBodyMatchesW54GSchema() {
        val client = createClient()
        val body = client.buildQueryFriendPkStatusBody(
            friendUin = 12345678L,
            friendPetId = "friend_pet_001",
            ownPetId = "my_pet_999"
        )

        assertEquals("friend_pet_001", ProtoWire.firstString(body, 1))
        assertEquals("12345678", ProtoWire.firstString(body, 2))
        assertEquals("my_pet_999", ProtoWire.firstString(body, 3))
        assertEquals(0L, ProtoWire.firstVarint(body, 4))
        assertNull(ProtoWire.firstVarint(body, 100))
    }

    @Test
    fun queryFriendPkStatusDecodesW54HResponse() {
        var callbackInvoked = false
        val mockHireStatus = ProtoWire.message()
            .writeVarint(1, 10L)
            .toByteArray()

        val mockPkStatus = ProtoWire.message()
            .writeVarint(1, 100L)
            .writeVarint(3, 30L)
            .writeVarint(4, 30L)
            .writeString(5, "story_test_123")
            .toByteArray()

        val responseData = ProtoWire.message()
            .writeBytes(1, mockHireStatus)
            .writeBytes(2, mockPkStatus)
            .writeVarint(3, 10L)
            .toByteArray()
        val client = createClient { commandName, command, subCommand, _, callback ->
            assertEquals("OidbSvcTrpcTcp.0x9875_1", commandName)
            assertEquals(39029, command)
            assertEquals(1, subCommand)
            callback(0, responseData, null)
        }

        client.queryFriendPkStatus(12345L, "friend_pet", "own_pet") { code, info, err ->
            callbackInvoked = true
            assertEquals(0, code)
            assertTrue(info != null && info.canPk)
            assertEquals(100, info?.rawStatus)
            assertEquals(30L, info?.remainingSec)
            assertEquals("story_test_123", info?.ongoingStoryId)
            assertTrue(info?.canHire == true)
            assertEquals(10, info?.hireStatus)
            assertNull(err)
        }

        assertTrue(callbackInvoked)
    }

    @Test
    fun parsePkBattleResultSeparatesRemainingSecondsFromStartTimestamp() {
        val client = createClient()

        val statusInfo = ProtoWire.message()
            .writeVarint(1, 2L)
            .writeVarint(2, 60L)
            .writeVarint(3, 90L)
            .writeVarint(4, 1700000000L)
            .toByteArray()

        val mySide = ProtoWire.message()
            .writeVarint(3, 1500L)
            .writeString(5, "MyPet")
            .toByteArray()

        val oppSide = ProtoWire.message()
            .writeVarint(3, 1200L)
            .writeString(5, "OppPet")
            .toByteArray()

        val battleInfo = ProtoWire.message()
            .writeBytes(1, mySide)
            .writeBytes(2, oppSide)
            .toByteArray()

        val data = ProtoWire.message()
            .writeString(1, "battle_story_99")
            .writeBytes(4, statusInfo)
            .writeBytes(5, battleInfo)
            .toByteArray()

        val res = client.parsePkBattleResult(data)
        assertEquals("battle_story_99", res.storyId)
        assertEquals(1500, res.myPower)
        assertEquals(1200, res.oppPower)
        assertEquals("MyPet", res.myNick)
        assertEquals("OppPet", res.oppNick)
        assertTrue(res.isWin)
        assertEquals(60L, res.leftDurationSec)
    }

    @Test
    fun parsePkSettleResultExtractsGoldFromStoryItemInfoTag9() {
        val client = createClient()

        val goldItem = ProtoWire.message()
            .writeVarint(1, 888L)
            .writeVarint(2, 1L)
            .writeString(3, "金币")
            .toByteArray()

        val expItem = ProtoWire.message()
            .writeVarint(1, 50L)
            .writeVarint(2, 2L)
            .writeString(3, "经验")
            .toByteArray()

        val pkEnd = ProtoWire.message()
            .writeString(1, "对决胜利")
            .writeString(2, "取得了切磋胜利")
            .toByteArray()

        val endInfo = ProtoWire.message()
            .writeBytes(9, goldItem)
            .writeBytes(9, expItem)
            .writeBytes(18, pkEnd)
            .toByteArray()

        val data = ProtoWire.message()
            .writeBytes(1, endInfo)
            .toByteArray()

        val res = client.parsePkSettleResult("story_888", data)
        assertEquals(888L, res.goldEarned)
        assertEquals("对决胜利", res.title)
        assertEquals("取得了切磋胜利", res.desc)
    }

    @Test
    fun parsePkSettleResultZeroGoldWhenNoItems() {
        val client = createClient()

        val pkEnd = ProtoWire.message()
            .writeString(1, "平局")
            .writeString(2, "未分胜负")
            .toByteArray()

        val endInfo = ProtoWire.message()
            .writeBytes(18, pkEnd)
            .toByteArray()

        val data = ProtoWire.message()
            .writeBytes(1, endInfo)
            .toByteArray()

        val res = client.parsePkSettleResult("story_tie", data)
        assertEquals(0L, res.goldEarned)
        assertEquals("平局", res.title)
        assertEquals("未分胜负", res.desc)
    }
}

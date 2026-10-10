package io.github.reqpet.protocol

import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.client.PetCareProtocolClient
import io.github.reqpet.protocol.client.PetSocialProtocolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetSocialFriendStatusProtocolTest {

    private fun createSocialClient(
        onSend: (commandName: String, command: Int, subCommand: Int, request: ByteArray, callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit) -> Unit = { _, _, _, _, _ -> }
    ): PetSocialProtocolClient {
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
        return PetSocialProtocolClient(channel)
    }

    private fun createCareClient(
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
    fun workCandidatesUseCareerSourceAndPreservePagination() {
        var callbackCalled = false
        val client = createSocialClient { name, command, subCommand, request, callback ->
            assertEquals("OidbSvcTrpcTcp.0x985d_0", name)
            assertEquals(39005, command)
            assertEquals(0, subCommand)
            assertEquals("current_page", ProtoWire.firstString(request, 1))
            assertEquals(6L, ProtoWire.firstVarint(request, 2))
            callback(0, ProtoWire.message().writeString(2, "next_page").writeVarint(3, 1L).toByteArray(), null)
        }
        client.fetchPetFriendsPage("current_page") { code, friends, hasMore, cookie, error ->
            callbackCalled = true
            assertEquals(0, code)
            assertTrue(friends.isEmpty())
            assertTrue(hasMore)
            assertEquals("next_page", cookie)
            assertEquals(null, error)
        }
        assertTrue(callbackCalled)
    }

    @Test
    fun parseFriendPetSnapshotsFrom0x985dResponse() {
        val client = createSocialClient()

        val profileBytes = ProtoWire.message()
            .writeString(1, "小波")
            .writeString(8, "pet_1001")
            .writeBytes(13, ProtoWire.message().writeVarint(1, 15L).toByteArray())
            .toByteArray()

        val userBytes = ProtoWire.message()
            .writeVarint(1, 12345678L)
            .writeString(2, "好友小明")
            .toByteArray()

        val feelDetail = ProtoWire.message().writeFloat(3, 85.5f).toByteArray()
        val hungerDetail = ProtoWire.message().writeFloat(3, 60.0f).toByteArray()
        val cleanDetail = ProtoWire.message().writeFloat(3, 90.0f).toByteArray()
        val displayValues = ProtoWire.message()
            .writeBytes(1, feelDetail)
            .writeBytes(2, hungerDetail)
            .writeBytes(3, cleanDetail)
            .toByteArray()

        val coinBagBytes = ProtoWire.message()
            .writeString(1, "bag_999")
            .toByteArray()

        val friendNode = ProtoWire.message()
            .writeBytes(1, profileBytes)
            .writeBytes(2, userBytes)
            .writeVarint(3, 2L)
            .writeBytes(4, displayValues)
            .writeBytes(21, coinBagBytes)
            .toByteArray()

        val responseData = ProtoWire.message()
            .writeBytes(1, friendNode)
            .writeString(2, "cookie_page_2")
            .writeVarint(3, 1L)
            .toByteArray()

        val snapshots = client.parseFriendPetSnapshots(responseData)
        assertEquals(1, snapshots.size)

        val first = snapshots[0]
        assertEquals(12345678L, first.uin)
        assertEquals("好友小明", first.userNick)
        assertEquals("pet_1001", first.petId)
        assertEquals("小波", first.petNick)
        assertEquals(15, first.level)
        assertEquals(60.0f, first.energy, 0.01f)
        assertEquals(90.0f, first.clean, 0.01f)
        assertEquals(85.5f, first.mood, 0.01f)
        assertEquals(2, first.hireStatus)
        assertEquals("bag_999", first.coinbagId)
    }

    @Test
    fun parseGuestPetStatusFrom0x9acbResponse() {
        val client = createSocialClient()

        val settingPayload = ProtoWire.message()
            .writeVarint(52, 1L)
            .toByteArray()

        val sicknessInfo = ProtoWire.message()
            .writeVarint(1, 1800L)
            .writeVarint(3, 2L)
            .writeString(4, "med_cold_01")
            .toByteArray()
        val statusNewPayload = ProtoWire.message()
            .writeBytes(1, sicknessInfo)
            .toByteArray()

        val coinBagPayload = ProtoWire.message()
            .writeString(1, "bag_guest_01")
            .writeVarint(5, 1L)
            .writeVarint(31, 0L)
            .toByteArray()

        val msg8 = ProtoWire.message()
            .writeVarint(1, 8L)
            .writeBytes(2, settingPayload)
            .toByteArray()
        val msg9 = ProtoWire.message()
            .writeVarint(1, 9L)
            .writeBytes(2, statusNewPayload)
            .toByteArray()
        val msg14 = ProtoWire.message()
            .writeVarint(1, 14L)
            .writeBytes(2, coinBagPayload)
            .toByteArray()

        val responseData = ProtoWire.message()
            .writeBytes(1, msg8)
            .writeBytes(1, msg9)
            .writeBytes(1, msg14)
            .toByteArray()

        val guestStatus = client.parseGuestPetStatus("pet_target_777", responseData)
        assertEquals("pet_target_777", guestStatus.petId)
        assertTrue(guestStatus.isSick)
        assertTrue(guestStatus.isTreating)
        assertEquals(2, guestStatus.sicknessType)
        assertEquals("med_cold_01", guestStatus.medicineId)
        assertEquals(1800L, guestStatus.recoverCountdownSec)
        assertTrue(guestStatus.acceptStrangerPK)
        assertEquals("bag_guest_01", guestStatus.coinbagId)
    }

    @Test
    fun parsePetProfileDetailFrom0x95e1Response() {
        val client = createCareClient()

        val levelNode = ProtoWire.message().writeVarint(1, 25L).toByteArray()
        val nameNode = ProtoWire.message().writeString(1, "可可").toByteArray()

        val petBytes = ProtoWire.message()
            .writeString(1, "企鹅")
            .writeBytes(4, levelNode)
            .writeBytes(7, nameNode)
            .writeString(101, "pet_keke_001")
            .toByteArray()

        val responseData = ProtoWire.message()
            .writeBytes(1, petBytes)
            .toByteArray()

        val detail = client.parsePetProfileDetail("pet_keke_001", responseData)
        assertEquals("pet_keke_001", detail.petId)
        assertEquals("企鹅", detail.species)
        assertEquals("可可", detail.petName)
        assertEquals(25, detail.level)
    }
}

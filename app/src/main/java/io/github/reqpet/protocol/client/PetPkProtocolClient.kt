package io.github.reqpet.protocol.client

import io.github.reqpet.engine.EngineLog
import io.github.reqpet.protocol.ProtoWire
import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.model.PkBattleResult
import io.github.reqpet.protocol.model.PkSettleResult
import io.github.reqpet.protocol.model.PkStatusInfo

/**
 * 宠物切磋与PK竞技协议客户端
 */
class PetPkProtocolClient(
    private val channel: OidbChannel
) {
    companion object {
        private const val TAG = "PetPkProtocolClient"
    }

    internal fun buildQueryFriendPkStatusBody(
        friendUin: Long,
        friendPetId: String,
        ownPetId: String
    ): ByteArray {
        return ProtoWire.message()
            .writeString(1, friendPetId)
            .writeString(2, friendUin.toString())
            .writeString(3, ownPetId)
            .writeVarint(4, 0L)
            .toByteArray()
    }

    fun queryFriendPkStatus(
        friendUin: Long,
        friendPetId: String,
        ownPetId: String,
        callback: (code: Int, info: PkStatusInfo?, errorMsg: String?) -> Unit
    ) {
        val body = buildQueryFriendPkStatusBody(friendUin, friendPetId, ownPetId)
        channel.sendOidb("OidbSvcTrpcTcp.0x9875_1", 39029, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val hireStatusBytes = ProtoWire.firstBytes(data, 1)
                val hireRawStatus = (ProtoWire.firstVarint(hireStatusBytes, 1) ?: 0L).toInt()
                val canHire = (hireRawStatus == 10 || hireRawStatus == 30)

                val pkStatusBytes = ProtoWire.firstBytes(data, 2)
                val rawStatus = (ProtoWire.firstVarint(pkStatusBytes, 1) ?: 0L).toInt()
                val countDown = ProtoWire.firstVarint(pkStatusBytes, 4)
                    ?: ProtoWire.firstVarint(pkStatusBytes, 3)
                    ?: 0L
                val storyId = ProtoWire.firstString(pkStatusBytes, 5)
                val canPk = (rawStatus == 100 || rawStatus == 300)
                EngineLog.i(
                    "PetPkClient",
                    "queryFriendPkStatus: uin=$friendUin, rawStatus=$rawStatus, canPk=$canPk, canHire=$canHire, storyId=$storyId"
                )
                callback(0, PkStatusInfo(canPk, rawStatus, storyId, countDown, canHire, hireRawStatus), null)
            } else {
                EngineLog.w("PetPkClient", "queryFriendPkStatus 回包: code=$code, err=$errorMsg")
                callback(code, null, errorMsg)
            }
        }
    }

    fun startPkBattle(
        targetUin: Long,
        targetPetId: String,
        ownPetId: String,
        bodyguardId: String = "",
        callback: (PkBattleResult) -> Unit
    ) {
        val targetUserInfo = ProtoWire.message()
            .writeString(1, targetPetId)
            .writeString(2, targetUin.toString())
            .toByteArray()
        val msg = ProtoWire.message()
            .writeVarint(1, 6900L)
            .writeString(2, ownPetId)
            .writeString(3, "")
            .writeBytes(4, targetUserInfo)
        if (bodyguardId.isNotBlank()) msg.writeString(5, bodyguardId)
        val body = msg.writeString(6, "PK").writeVarint(7, 6901L).writeVarint(100, 2L).toByteArray()

        channel.sendOidb("OidbSvcTrpcTcp.0x975e_1", 38750, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val res = parsePkBattleResult(data)
                EngineLog.i(
                    "PetPkClient",
                    "startPkBattle: storyId=${res.storyId}, win=${res.isWin}, left=${res.leftDurationSec}s"
                )
                callback(res)
            } else {
                EngineLog.w("PetPkClient", "startPkBattle 回包失败: code=$code, err=$errorMsg")
                callback(PkBattleResult(code, null, errorMsg = errorMsg))
            }
        }
    }

    internal fun parsePkBattleResult(data: ByteArray): PkBattleResult {
        val storyId = ProtoWire.firstString(data, 1)
        val statusInfoBytes = ProtoWire.firstBytes(data, 4)
        val leftDuration = ProtoWire.firstVarint(statusInfoBytes, 2) ?: 0L
        val battleInfoBytes = ProtoWire.firstBytes(data, 5)
        val mySideBytes = ProtoWire.firstBytes(battleInfoBytes, 1)
        val oppSideBytes = ProtoWire.firstBytes(battleInfoBytes, 2)
        val myPower = (ProtoWire.firstVarint(mySideBytes, 3) ?: 0L).toInt()
        val myNick = ProtoWire.firstString(mySideBytes, 5) ?: ""
        val oppPower = (ProtoWire.firstVarint(oppSideBytes, 3) ?: 0L).toInt()
        val oppNick = ProtoWire.firstString(oppSideBytes, 5) ?: ""
        val isWin = myPower > oppPower
        return PkBattleResult(0, storyId, myPower, oppPower, myNick, oppNick, isWin, leftDuration, null)
    }

    fun settlePkBattle(
        storyId: String,
        ownPetId: String,
        callback: (PkSettleResult) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, storyId)
            .writeVarint(2, 6000L)
            .writeString(3, ownPetId)
            .writeVarint(4, 0L)
            .writeVarint(100, 2L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9760_1", 38752, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val res = parsePkSettleResult(storyId, data)
                EngineLog.i("PetPkClient", "settlePkBattle 成功: storyId=$storyId, gold=${res.goldEarned}")
                callback(res)
            } else {
                EngineLog.w("PetPkClient", "settlePkBattle 回包: storyId=$storyId, code=$code, err=$errorMsg")
                callback(PkSettleResult(code, 0L, null, null, errorMsg))
            }
        }
    }

    internal fun parsePkSettleResult(storyId: String, data: ByteArray): PkSettleResult {
        val endInfoBytes = ProtoWire.firstBytes(data, 1)
        val pkEndBytes = ProtoWire.firstBytes(endInfoBytes, 18)
        val title = ProtoWire.firstString(pkEndBytes, 1) ?: ProtoWire.firstString(endInfoBytes, 6)
        val desc = ProtoWire.firstString(pkEndBytes, 2) ?: ProtoWire.firstString(endInfoBytes, 7)

        var goldEarned = 0L
        val itemNodes = if (endInfoBytes != null) ProtoWire.allBytes(endInfoBytes, 9) else emptyList()
        for (item in itemNodes) {
            val count = ProtoWire.firstVarint(item, 1) ?: 0L
            val name = ProtoWire.firstString(item, 3).orEmpty()
            if (name.contains("金币") || name.contains("coin", ignoreCase = true)) {
                goldEarned += count
            } else if (goldEarned == 0L && count in 1..1_000_000L) {
                goldEarned = count
            }
        }
        return PkSettleResult(0, goldEarned, title, desc, null)
    }
}

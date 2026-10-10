package io.github.reqpet.protocol.client

import io.github.reqpet.engine.EngineLog
import io.github.reqpet.engine.AccountSessionGuard
import io.github.reqpet.protocol.ProtoWire
import io.github.reqpet.protocol.ProtoWireText
import io.github.reqpet.protocol.QQPetDirectBridge.HireableFriend
import io.github.reqpet.protocol.QQPetDirectBridge.LikeMember
import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.model.FriendCoinBagInfo
import io.github.reqpet.protocol.model.FriendPetSnapshot
import io.github.reqpet.protocol.model.GuestPetStatus
import io.github.reqpet.protocol.model.SnatchCoinBagResult

/**
 * 宠物社交互动协议客户端 (访客踩踩、好友福袋掠夺与雇佣好友拉取)
 */
class PetSocialProtocolClient(
    private val channel: OidbChannel,
    private val onOwnBagFound: (bagId: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "PetSocialProtocolClient"
        private const val CMD_HOME_MSG = 39627
        private const val SUBCMD_HOME_MSG = 0
        private const val MSG_TYPE_COINBAG = 14
        private const val BAG_STATUS_OPENED = 3
        private const val BAG_STATUS_EXPIRED = 4
    }

    fun fetchLikeList(
        extra: String = "",
        callback: (code: Int, members: List<LikeMember>, hasMore: Boolean, nextExtra: String, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, extra)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x985e_0", 39006, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val memberList = parseLikeMembers(data)
                val hasMore = (ProtoWire.firstVarint(data, 2) ?: 0L) != 0L
                val nextExtra = ProtoWire.firstString(data, 5) ?: ""
                EngineLog.i("PetSocialClient", "fetchLikeList 回包: 解析到 ${memberList.size} 位访客, hasMore=$hasMore")
                callback(0, memberList, hasMore, nextExtra, data, null)
            } else {
                EngineLog.w("PetSocialClient", "fetchLikeList 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), false, "", data, errorMsg)
            }
        }
    }

    private fun parseLikeMembers(data: ByteArray): List<LikeMember> {
        val memberList = mutableListOf<LikeMember>()
        val itemBytesList = ProtoWire.allBytes(data, 1)
        for (itemBytes in itemBytesList) {
            val userProfileBytes = ProtoWire.firstBytes(itemBytes, 1)
            val uin = ProtoWire.firstVarint(userProfileBytes, 1) ?: 0L
            val nick = ProtoWire.firstString(userProfileBytes, 2) ?: ""
            val headerUrl = ProtoWire.firstString(userProfileBytes, 3) ?: ""
            val ts = ProtoWire.firstVarint(itemBytes, 2) ?: 0L
            val descBytes = ProtoWire.firstBytes(itemBytes, 3)
            val contentBytesList = ProtoWire.allBytes(descBytes, 1)
            val descSb = StringBuilder()
            for (cBytes in contentBytesList) {
                descSb.append(ProtoWire.firstString(cBytes, 1) ?: "")
            }
            val friendPetBytes = ProtoWire.firstBytes(itemBytes, 7)
            var petId = ""
            var canLikeBack = true
            if (friendPetBytes != null) {
                petId = ProtoWire.firstString(ProtoWire.firstBytes(friendPetBytes, 1), 101) ?: ""
            }
            val sparkBriefBytes = ProtoWire.firstBytes(itemBytes, 6)
                ?: if (friendPetBytes != null) ProtoWire.firstBytes(friendPetBytes, 10) else null
            if (sparkBriefBytes != null) {
                val selfLikedToday = (ProtoWire.firstVarint(sparkBriefBytes, 10) ?: 0L) != 0L
                canLikeBack = !selfLikedToday
            } else if (friendPetBytes != null) {
                canLikeBack = (ProtoWire.firstVarint(friendPetBytes, 8) ?: 0L) != 1L
            }
            if (uin > 0L) {
                memberList.add(LikeMember(uin, nick, headerUrl, ts, descSb.toString(), canLikeBack, petId))
            }
        }
        return memberList
    }

    fun sendLike(
        targetUin: Long,
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message().writeVarint(1, targetUin).toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x985b_0", 39003, 0, body) { code, data, errorMsg ->
            EngineLog.i("PetSocialClient", "sendLike 结果: uin=$targetUin, code=$code, err=$errorMsg")
            callback(code, data, errorMsg)
        }
    }

    fun queryLikeCount(
        targetUin: Long,
        callback: (code: Int, alreadyLiked: Boolean, likeCount: String, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message().writeVarint(1, targetUin).toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x985c_0", 39004, 0, body) { code, data, errorMsg ->
            var alreadyLiked = false
            var likeCount = "0"
            if (code == 0 && data != null) {
                likeCount = ProtoWire.firstString(data, 1) ?: "0"
                alreadyLiked = (ProtoWire.firstVarint(data, 2) ?: 0L) != 0L
            }
            callback(code, alreadyLiked, likeCount, data, errorMsg)
        }
    }

    fun fetchFriendCoinBags(
        cookie: String = "",
        callback: (code: Int, bags: List<FriendCoinBagInfo>, totalFriends: Int, hasMore: Boolean, nextCookie: String, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message().writeString(1, cookie).writeVarint(2, 1L).writeVarint(3, 0L).toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x985d_0", 39005, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val (bagList, totalFriends) = parseFriendCoinBags(data)
                val nextCookie = ProtoWire.firstString(data, 2) ?: ""
                val hasMore = (ProtoWire.firstVarint(data, 3) ?: 0L) != 0L
                EngineLog.i(
                    "PetSocialClient",
                    "fetchFriendCoinBags: 本页好友=$totalFriends, 发现福袋=${bagList.size}, hasMore=$hasMore"
                )
                callback(0, bagList, totalFriends, hasMore, nextCookie, null)
            } else {
                EngineLog.w("PetSocialClient", "fetchFriendCoinBags 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), 0, false, "", errorMsg)
            }
        }
    }

    private fun parseFriendCoinBags(data: ByteArray): Pair<List<FriendCoinBagInfo>, Int> {
        val bagList = mutableListOf<FriendCoinBagInfo>()
        val allFriendNodes = mutableListOf<ByteArray>()
        allFriendNodes.addAll(ProtoWire.allBytes(data, 1))
        val selfNodeBytes = ProtoWire.firstBytes(data, 6)
        if (selfNodeBytes != null) allFriendNodes.add(selfNodeBytes)
        val currentOwnUinStr = channel.getCurrentRuntimeUin()
        val rootCoinBagId = ProtoWire.firstString(ProtoWire.firstBytes(data, 21), 1)?.trim().orEmpty()
        if (rootCoinBagId.isNotEmpty()) {
            onOwnBagFound(rootCoinBagId)
            bagList.add(
                FriendCoinBagInfo(
                    currentOwnUinStr.toLongOrNull() ?: 0L,
                    "自己小窝",
                    "",
                    "我的小窝",
                    rootCoinBagId,
                    true
                )
            )
        }
        for (nodeBytes in allFriendNodes) {
            val profileBytes = ProtoWire.firstBytes(nodeBytes, 1)
            val petNick = ProtoWire.firstString(profileBytes, 1) ?: ""
            val friendPetId = ProtoWire.firstString(profileBytes, 8) ?: ProtoWire.firstString(profileBytes, 101) ?: ""
            val userBytes = ProtoWire.firstBytes(nodeBytes, 2)
            val friendUin = ProtoWire.firstVarint(userBytes, 1) ?: 0L
            val friendNick = ProtoWire.firstString(userBytes, 2) ?: ""
            val coinbagId = ProtoWire.firstString(ProtoWire.firstBytes(nodeBytes, 21), 1)?.trim() ?: ""
            if (coinbagId.isNotEmpty()) {
                val isSelf = (currentOwnUinStr.isNotEmpty() && friendUin.toString() == currentOwnUinStr) ||
                        (selfNodeBytes != null && nodeBytes.contentEquals(selfNodeBytes)) || (friendUin == 0L && friendNick.isEmpty())
                EngineLog.i(
                    "PetSocialClient",
                    "发现地面福袋: uin=$friendUin, nick=$friendNick, bagId=$coinbagId, isSelf=$isSelf, currentUin=$currentOwnUinStr"
                )
                if (isSelf) onOwnBagFound(coinbagId)
                bagList.add(
                    FriendCoinBagInfo(
                        friendUin,
                        if (isSelf) "自己小窝" else friendNick,
                        friendPetId,
                        petNick,
                        coinbagId,
                        isSelf
                    )
                )
            }
        }
        return Pair(bagList, allFriendNodes.size)
    }

    fun fetchPetFriendsPage(
        cookie: String = "",
        callback: (code: Int, friends: List<HireableFriend>, hasMore: Boolean, nextCookie: String, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message().writeString(1, cookie).writeVarint(2, 6L).writeVarint(3, 0L).toByteArray()
        val currentOwnUin = channel.getCurrentRuntimeUin()
        channel.sendOidb("OidbSvcTrpcTcp.0x985d_0", 39005, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val list = parseHireableFriends(data, currentOwnUin)
                val nextCookie = ProtoWire.firstString(data, 2) ?: ""
                val hasMore = (ProtoWire.firstVarint(data, 3) ?: 0L) != 0L
                EngineLog.i("PetSocialClient", "fetchPetFriendsPage: 解析到 ${list.size} 位好友, hasMore=$hasMore")
                callback(0, list, hasMore, nextCookie, null)
            } else {
                EngineLog.w("PetSocialClient", "fetchPetFriendsPage 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), false, "", errorMsg)
            }
        }
    }

    fun fetchFriendPetList(
        cookie: String = "",
        callback: (code: Int, friends: List<FriendPetSnapshot>, hasMore: Boolean, nextCookie: String, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, cookie)
            .writeVarint(2, 3L)
            .writeVarint(3, 0L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x985d_0", 39005, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val list = parseFriendPetSnapshots(data)
                val nextCookie = ProtoWire.firstString(data, 2) ?: ""
                val hasMore = (ProtoWire.firstVarint(data, 3) ?: 0L) != 0L
                EngineLog.i("PetSocialClient", "fetchFriendPetList: 解析到 ${list.size} 位好友宠物, hasMore=$hasMore")
                callback(0, list, hasMore, nextCookie, null)
            } else {
                EngineLog.w("PetSocialClient", "fetchFriendPetList 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), false, "", errorMsg)
            }
        }
    }

    internal fun parseFriendPetSnapshots(data: ByteArray): List<FriendPetSnapshot> {
        val list = mutableListOf<FriendPetSnapshot>()
        val friendNodes = ProtoWire.allBytes(data, 1)
        for (nodeBytes in friendNodes) {
            val userBytes = ProtoWire.firstBytes(nodeBytes, 2)
            val friendUin = ProtoWire.firstVarint(userBytes, 1) ?: 0L
            val friendNick = ProtoWire.firstString(userBytes, 2)?.trim().orEmpty()
            val profileBytes = ProtoWire.firstBytes(nodeBytes, 1)
            val petNick = ProtoWire.firstString(profileBytes, 1)?.trim().orEmpty()
            val friendPetId = ProtoWire.firstString(profileBytes, 8)?.trim()
                ?: ProtoWire.firstString(profileBytes, 101)?.trim().orEmpty()
            val levelNode = ProtoWire.firstBytes(profileBytes, 13)
            val level = (ProtoWire.firstVarint(levelNode, 1) ?: 0L).toInt()
            val hireStatus = (ProtoWire.firstVarint(nodeBytes, 3) ?: 0L).toInt()
            val displayValueBytes = ProtoWire.firstBytes(nodeBytes, 4)
            val feelBytes = ProtoWire.firstBytes(displayValueBytes, 1)
            val hungerBytes = ProtoWire.firstBytes(displayValueBytes, 2)
            val cleanBytes = ProtoWire.firstBytes(displayValueBytes, 3)
            val mood = if (feelBytes != null) (ProtoWire.firstFloat(feelBytes, 3) ?: 100f) else 100f
            val energy = if (hungerBytes != null) (ProtoWire.firstFloat(hungerBytes, 3) ?: 100f) else 100f
            val clean = if (cleanBytes != null) (ProtoWire.firstFloat(cleanBytes, 3) ?: 100f) else 100f
            val coinbagId = ProtoWire.firstString(ProtoWire.firstBytes(nodeBytes, 21), 1)?.trim().orEmpty()
            if (coinbagId.isNotEmpty()) {
                onOwnBagFound(coinbagId)
            }
            list.add(
                FriendPetSnapshot(
                    uin = friendUin,
                    userNick = friendNick,
                    petId = friendPetId,
                    petNick = petNick,
                    level = level,
                    energy = energy,
                    clean = clean,
                    mood = mood,
                    hireStatus = hireStatus,
                    coinbagId = coinbagId
                )
            )
        }
        return list
    }

    fun fetchGuestPetDenStatus(
        petId: String,
        callback: (code: Int, status: GuestPetStatus?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, petId)
            .writeBytes(2, byteArrayOf(8, 9, 14))
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9acb_0", CMD_HOME_MSG, SUBCMD_HOME_MSG, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val status = parseGuestPetStatus(petId, data)
                EngineLog.i(
                    TAG,
                    "fetchGuestPetDenStatus 成功: petId=$petId, isSick=${status.isSick}, coinbagId=${status.coinbagId}"
                )
                callback(0, status, null)
            } else {
                EngineLog.w(TAG, "fetchGuestPetDenStatus 失败 (petId=$petId): code=$code, err=$errorMsg")
                callback(code, null, errorMsg)
            }
        }
    }

    internal fun parseGuestPetStatus(targetPetId: String, data: ByteArray): GuestPetStatus {
        var isSick = false
        var isTreating = false
        var sicknessType = 0
        var medicineId = ""
        var countdownSec = 0L
        var acceptStrangerPK = false
        var coinbagId = ""

        val msgList = ProtoWire.allBytes(data, 1)
        for (mBytes in msgList) {
            val type = (ProtoWire.firstVarint(mBytes, 1) ?: 0L).toInt()
            val payload = ProtoWire.firstBytes(mBytes, 2) ?: continue
            when (type) {
                8 -> {
                    acceptStrangerPK = (ProtoWire.firstVarint(payload, 52) ?: 0L) == 1L
                }

                9 -> {
                    val sicknessInfoBytes = ProtoWire.firstBytes(payload, 1)
                    if (sicknessInfoBytes != null) {
                        countdownSec = ProtoWire.firstVarint(sicknessInfoBytes, 1) ?: 0L
                        sicknessType = (ProtoWire.firstVarint(sicknessInfoBytes, 3) ?: 0L).toInt()
                        medicineId = ProtoWire.firstString(sicknessInfoBytes, 4).orEmpty()
                        isSick = sicknessType != 0
                        isTreating = medicineId.isNotEmpty()
                    }
                }

                14 -> {
                    val bagId = ProtoWire.firstString(payload, 1)?.trim().orEmpty()
                    val status = (ProtoWire.firstVarint(payload, 5) ?: 0L).toInt()
                    val alreadyOpened = (ProtoWire.firstVarint(payload, 31) ?: 0L) != 0L
                    if (bagId.isNotEmpty() && !alreadyOpened && status != BAG_STATUS_OPENED && status != BAG_STATUS_EXPIRED) {
                        coinbagId = bagId
                        onOwnBagFound(bagId)
                    }
                }
            }
        }
        return GuestPetStatus(
            petId = targetPetId,
            isSick = isSick,
            isTreating = isTreating,
            sicknessType = sicknessType,
            medicineId = medicineId,
            recoverCountdownSec = countdownSec,
            acceptAllPK = true,
            acceptStrangerPK = acceptStrangerPK,
            acceptAllEmploy = true,
            coinbagId = coinbagId
        )
    }

    private fun parseHireableFriends(data: ByteArray, currentOwnUin: String): List<HireableFriend> {
        val list = mutableListOf<HireableFriend>()
        val friendNodes = ProtoWire.allBytes(data, 1)
        for (nodeBytes in friendNodes) {
            val userBytes = ProtoWire.firstBytes(nodeBytes, 2)
            val friendUin = ProtoWire.firstVarint(userBytes, 1) ?: 0L
            if (friendUin <= 0L || (currentOwnUin.isNotEmpty() && friendUin.toString() == currentOwnUin)) continue
            val friendNick = ProtoWire.firstString(userBytes, 2)?.trim().orEmpty()
            val profileBytes = ProtoWire.firstBytes(nodeBytes, 1)
            val petNick = ProtoWire.firstString(profileBytes, 1)?.trim().orEmpty()
            var friendPetId = ProtoWire.firstString(profileBytes, 8)?.trim()
                ?: ProtoWire.firstString(profileBytes, 101)?.trim() ?: ""
            if (friendPetId.isEmpty() && profileBytes != null) {
                val candidates = ProtoWireText.extractAllStrings(profileBytes)
                friendPetId = candidates.firstOrNull { str: String ->
                    AccountSessionGuard.extractOwnerUinFromPetId(str) == friendUin.toString()
                }.orEmpty()
            }
            if (friendPetId.isNotEmpty()) {
                list.add(HireableFriend(friendUin, friendNick, petNick, friendPetId))
            }
        }
        return list
    }

    fun fetchOwnGroundCoinBag(
        petId: String,
        callback: (code: Int, coinbagId: String?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, petId)
            .writeBytes(2, byteArrayOf(MSG_TYPE_COINBAG.toByte()))
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9acb_0", CMD_HOME_MSG, SUBCMD_HOME_MSG, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val bagId = parseGroundCoinBagId(data)
                if (!bagId.isNullOrEmpty()) {
                    onOwnBagFound(bagId)
                    EngineLog.i("PetSocialClient", "[0x9acb_0] 捕获到自家地面钱袋: $bagId")
                }
                callback(0, bagId, null)
            } else {
                EngineLog.w("PetSocialClient", "fetchOwnGroundCoinBag 失败: code=$code, err=$errorMsg")
                callback(code, null, errorMsg)
            }
        }
    }

    private fun parseGroundCoinBagId(data: ByteArray): String? {
        val msgList = ProtoWire.allBytes(data, 1)
        for (mBytes in msgList) {
            val type = (ProtoWire.firstVarint(mBytes, 1) ?: 0L).toInt()
            if (type == MSG_TYPE_COINBAG) {
                val payload = ProtoWire.firstBytes(mBytes, 2) ?: continue
                val bagId = ProtoWire.firstString(payload, 1)?.trim().orEmpty()
                val status = (ProtoWire.firstVarint(payload, 5) ?: 0L).toInt()
                val alreadyOpened = (ProtoWire.firstVarint(payload, 31) ?: 0L) != 0L
                if (bagId.isNotEmpty() && !alreadyOpened && status != BAG_STATUS_OPENED && status != BAG_STATUS_EXPIRED) {
                    return bagId
                }
            }
        }
        return null
    }

    fun snatchCoinBag(
        ownPetId: String,
        coinbagId: String,
        callback: (SnatchCoinBagResult) -> Unit
    ) {
        val body =
            ProtoWire.message().writeString(1, ownPetId).writeString(2, "").writeString(3, coinbagId).toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9d71_0", 40305, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val detailBytes = ProtoWire.firstBytes(data, 1)
                val bagBytes = ProtoWire.firstBytes(detailBytes, 1)
                val status = (ProtoWire.firstVarint(bagBytes, 5) ?: 0L).toInt()
                val alreadyOpened = (ProtoWire.firstVarint(bagBytes, 31) ?: 0L) != 0L
                val snatchInfoBytes = ProtoWire.firstBytes(data, 2)
                var gotGold = ProtoWire.firstVarint(snatchInfoBytes, 4) ?: 0L
                if (gotGold <= 0L && detailBytes != null) {
                    val snatchList = ProtoWire.allBytes(detailBytes, 2)
                    var sum = 0L
                    for (sBytes in snatchList) {
                        val pid = ProtoWire.firstString(sBytes, 2) ?: ""
                        if (pid == ownPetId) sum += (ProtoWire.firstVarint(sBytes, 4) ?: 0L)
                    }
                    gotGold = sum
                }
                EngineLog.i("PetSocialClient", "snatchCoinBag: bagId=$coinbagId, gotGold=$gotGold, status=$status")
                callback(SnatchCoinBagResult(0, coinbagId, gotGold, status, alreadyOpened, null))
            } else {
                EngineLog.w("PetSocialClient", "snatchCoinBag 失败: bagId=$coinbagId, code=$code, err=$errorMsg")
                callback(SnatchCoinBagResult(code, coinbagId, 0L, 0, false, errorMsg))
            }
        }
    }
}

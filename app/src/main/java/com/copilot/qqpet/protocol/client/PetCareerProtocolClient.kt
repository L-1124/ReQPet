package com.copilot.qqpet.protocol.client

import com.copilot.qqpet.hook.HookLog as Log
import com.copilot.qqpet.protocol.ProtoWire
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.protocol.QQPetDirectBridge.SecondMapDetails
import com.copilot.qqpet.protocol.QQPetDirectBridge.SelectEvent
import com.copilot.qqpet.protocol.channel.OidbChannel
import com.copilot.qqpet.protocol.model.ProcessStoryFatigueResult
import com.copilot.qqpet.protocol.model.SchoolStageInfo

/**
 * 宠物生涯成长、探险、学业与打工协议客户端
 */
class PetCareerProtocolClient(
    private val channel: OidbChannel,
    private val onFatigueDetected: (fatigued: Boolean, tip: String?) -> Unit = { _, _ -> },
    private val onSelectEventsFatigue: (fatigued: Boolean, tip: String?) -> Unit = { _, _ -> },
    private val onCostParsed: (costText: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "PetCareerProtocolClient"
    }

    fun queryStoryStatus(
        petId: String,
        callback: (code: Int, remainingSec: Long?, totalSec: Long?, activeStoryId: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, petId)
            .writeVarint(2, 0L)
            .writeVarint(100, 2L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x975a_1", 38746, 1, body) { code, data, _ ->
            var remaining: Long? = null
            var total: Long? = null
            var storyId: String? = null
            if (code == 0 && data != null) {
                val subInfo = ProtoWire.firstBytes(data, 1)
                if (subInfo != null) {
                    val status = ProtoWire.firstVarint(subInfo, 1) ?: 0L
                    if (status != 0L) {
                        remaining = ProtoWire.firstVarint(subInfo, 2) ?: 0L
                        total = ProtoWire.firstVarint(subInfo, 3) ?: 0L
                    }
                }
                storyId = ProtoWire.firstString(data, 2)
            }
            callback(code, remaining, total, storyId)
        }
    }

    fun queryProcessStoryInfo(
        storyId: String,
        petId: String,
        callback: (ProcessStoryFatigueResult) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, storyId)
            .writeString(2, petId)
            .writeVarint(100, 2L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x975f_1", 38751, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val eventType = (ProtoWire.firstVarint(data, 5) ?: 0L).toInt()
                val (fatigued, displayTip) = extractFatigueFromStoryData(data)
                val effectiveTip: String? = if (fatigued) displayTip else null
                onFatigueDetected(fatigued, effectiveTip)
                val allStrings = ProtoWire.extractAllStrings(data)
                val isHired = allStrings.any { s ->
                    s.contains("被雇佣") || s.contains("雇佣者") || s.contains("被雇佣者") ||
                    s.contains("加成奖金") || s.contains("额外加成") || s.contains("可获得基础工资") ||
                    s.contains("icon/1776409721409")
                }
                Log.i(TAG, "queryProcessStoryInfo: storyId=$storyId, fatigued=$fatigued, tip='$displayTip'")
                callback(ProcessStoryFatigueResult(0, fatigued, displayTip, eventType, null, isHired))
            } else {
                Log.w(TAG, "queryProcessStoryInfo 失败: code=$code, err=$errorMsg")
                callback(ProcessStoryFatigueResult(code, false, null, 0, errorMsg, false))
            }
        }
    }

    private fun extractFatigueFromStoryData(data: ByteArray): Pair<Boolean, String?> {
        val tipBytes = ProtoWire.firstBytes(data, 17)
        val tipContent = ProtoWire.firstString(tipBytes, 2)?.trim() ?: ""
        val tipExtra = ProtoWire.firstString(tipBytes, 3)?.trim() ?: ""
        val tipMarkdown = ProtoWire.firstString(tipBytes, 4)?.trim() ?: ""
        val allStrings = ProtoWire.extractAllStrings(data)
        val matchedStr = listOf(tipContent, tipMarkdown, tipExtra).firstOrNull { s: String ->
            QQPetDirectBridge.containsFatigueKeyword(s)
        } ?: allStrings.firstOrNull { s: String ->
            QQPetDirectBridge.containsFatigueKeyword(s)
        }
        val fatigued = !matchedStr.isNullOrEmpty()
        val displayTip = matchedStr
            ?.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
            ?.replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.ifEmpty { tipContent.ifEmpty { "疲惫，收益减少" } }
        return Pair(fatigued, displayTip)
    }

    fun settleStory(storyId: String, petId: String, callback: (code: Int, rawData: ByteArray?) -> Unit) {
        val body = ProtoWire.message()
            .writeString(1, storyId)
            .writeVarint(2, 1000L)
            .writeString(3, petId)
            .writeVarint(100, 2L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9760_1", 38752, 1, body) { code, data, _ -> callback(code, data) }
    }

    fun recallStory(storyId: String, petId: String, callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit) {
        val body = ProtoWire.message()
            .writeString(1, storyId)
            .writeVarint(2, 6000L)
            .writeString(3, petId)
            .writeVarint(4, 3L)
            .writeVarint(100, 2L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9760_1", 38752, 1, body) { code, data, errorMsg ->
            callback(code, data, errorMsg)
        }
    }

    fun startAdventure(
        petId: String,
        adventureName: String = "森林探险",
        subEventType: Long = 6701L,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        startSceneTask(6700L, petId, adventureName, subEventType, "", 0L, callback)
    }

    fun startWork(
        petId: String,
        jobName: String = "小镇兼职",
        page: Long = 6400L,
        subEventType: Long = 6401L,
        hiredPetId: String = "",
        hiredUin: Long = 0L,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        startSceneTask(page, petId, jobName, subEventType, hiredPetId, hiredUin, callback)
    }

    fun startSchool(
        petId: String,
        courseName: String = "基础学园课程",
        page: Long = 6100L,
        subEventType: Long = 6101L,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        startSceneTask(page, petId, courseName, subEventType, "", 0L, callback)
    }

    private fun startSceneTask(
        page: Long,
        petId: String,
        taskName: String,
        subEventType: Long,
        hiredPetId: String = "",
        hiredUin: Long = 0L,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val msg = ProtoWire.message()
            .writeVarint(1, page)
            .writeString(2, petId)
            .writeString(3, "")
        if (hiredPetId.isNotBlank() && hiredUin > 0L) {
            val reqUserInfo = ProtoWire.message()
                .writeString(1, hiredPetId)
                .writeString(2, hiredUin.toString())
                .toByteArray()
            msg.writeBytes(4, reqUserInfo)
        }
        val body = msg
            .writeString(6, taskName)
            .writeVarint(7, subEventType)
            .writeVarint(100, 2L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x975e_1", 38750, 1, body) { code, data, errorMsg ->
            val storyId = ProtoWire.firstString(data, 1)
            callback(code, storyId, data, errorMsg)
        }
    }

    fun querySecondMapInfo(
        eventType: Long = 6100L,
        petId: String,
        callback: (code: Int, schoolStage: Int, lastSubEvent: Long, rawData: ByteArray?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeVarint(1, eventType)
            .writeString(2, petId)
            .writeVarint(100, 2L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9b60_1", 39776, 1, body) { code, data, _ ->
            var stage = 0
            var lastSub = 0L
            if (code == 0 && data != null) {
                stage = (ProtoWire.firstVarint(data, 4) ?: 0L).toInt()
                lastSub = ProtoWire.firstVarint(data, 5) ?: 0L
            }
            callback(code, stage, lastSub, data)
        }
    }

    fun querySecondMapInfoDetails(
        eventType: Long = 6100L,
        petId: String,
        callback: (SecondMapDetails) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeVarint(1, eventType)
            .writeString(2, petId)
            .writeVarint(100, 2L)
            .toByteArray()
        channel.sendOidb("OidbSvcTrpcTcp.0x9b60_1", 39776, 1, body) { code, data, _ ->
            val details = parseSecondMapDetails(code, data)
            callback(details)
        }
    }

    private fun parseSecondMapDetails(code: Int, data: ByteArray?): SecondMapDetails {
        var stage = 0
        var lastSub = 0L
        val stageList = mutableListOf<SchoolStageInfo>()
        var power = 0L
        var intel = 0L
        var charm = 0L
        if (code == 0 && data != null) {
            stage = (ProtoWire.firstVarint(data, 4) ?: 0L).toInt()
            lastSub = ProtoWire.firstVarint(data, 5) ?: 0L
            val itemBytesList = ProtoWire.allBytes(data, 1)
            for (itemBytes in itemBytesList) {
                val title = ProtoWire.firstString(itemBytes, 1) ?: ""
                val limitStatus = (ProtoWire.firstVarint(itemBytes, 4) ?: 0L).toInt()
                val lockReason = ProtoWire.firstString(itemBytes, 5) ?: ""
                val id = (ProtoWire.firstVarint(itemBytes, 20) ?: ProtoWire.firstVarint(itemBytes, 21) ?: 0L).toInt()
                val graduated = (ProtoWire.firstVarint(itemBytes, 23) ?: 0L) != 0L
                if (id > 0) {
                    stageList.add(SchoolStageInfo(id, title, limitStatus, graduated, lockReason))
                }
            }
            val attrBytes = ProtoWire.firstBytes(data, 2)
            if (attrBytes != null) {
                power = ProtoWire.firstBytes(attrBytes, 1)?.let { b -> ProtoWire.firstVarint(b, 3) } ?: 0L
                intel = ProtoWire.firstBytes(attrBytes, 2)?.let { b -> ProtoWire.firstVarint(b, 3) } ?: 0L
                charm = ProtoWire.firstBytes(attrBytes, 3)?.let { b -> ProtoWire.firstVarint(b, 3) } ?: 0L
            }
        }
        return SecondMapDetails(code, stage, lastSub, stageList, power, intel, charm)
    }

    fun querySelectEvents(
        eventType: Long,
        petId: String,
        schoolStage: Int = 0,
        careerType: Int = 0,
        callback: (code: Int, events: List<SelectEvent>, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val msg = ProtoWire.message()
            .writeVarint(1, eventType)
            .writeString(2, petId)
            .writeString(3, "")
            .writeString(4, "")
        if (careerType > 0) msg.writeVarint(10, careerType.toLong())
        if (schoolStage > 0) msg.writeVarint(11, schoolStage.toLong())
        msg.writeVarint(100, 2L)
        channel.sendOidb("OidbSvcTrpcTcp.0x9ab2_1", 39602, 1, msg.toByteArray()) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val (list, fatigued, tip) = parseSelectEventsData(eventType, data)
                onSelectEventsFatigue(fatigued, tip)
                callback(0, list, data, null)
            } else {
                Log.w(TAG, "querySelectEvents 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), data, errorMsg)
            }
        }
    }

    private fun parseSelectEventsData(eventType: Long, data: ByteArray): Triple<List<SelectEvent>, Boolean, String?> {
        val list = mutableListOf<SelectEvent>()
        var foundFatigueTip: String? = null
        val itemBytesList = ProtoWire.allBytes(data, 1)
        for (itemBytes in itemBytesList) {
            val name = ProtoWire.firstString(itemBytes, 1) ?: ""
            val sub = ProtoWire.firstVarint(itemBytes, 52) ?: 0L
            val can = (ProtoWire.firstVarint(itemBytes, 50) ?: 0L) != 0L
            val level = (ProtoWire.firstVarint(itemBytes, 3) ?: 0L).toInt()
            val cost = ProtoWire.firstString(itemBytes, 6) ?: ""
            val costTime = ProtoWire.firstString(itemBytes, 7) ?: ""
            val reward = ProtoWire.firstString(itemBytes, 8) ?: ""
            val rewardExtra = ProtoWire.firstString(itemBytes, 9) ?: ""
            val eventTips = ProtoWire.firstString(itemBytes, 17) ?: ""
            val isOwnerNeedCare = (ProtoWire.firstVarint(itemBytes, 18) ?: 0L) != 0L
            val itemFatigueHit = listOf(eventTips, rewardExtra, reward).firstOrNull {
                QQPetDirectBridge.containsFatigueKeyword(it)
            }
            val itemIsFatigued = itemFatigueHit != null
            if (foundFatigueTip == null && itemFatigueHit != null) {
                foundFatigueTip = itemFatigueHit
            }
            if (cost.isNotEmpty()) onCostParsed(cost)
            if (name.isNotEmpty() && sub > 0L) {
                list.add(SelectEvent(name, sub, can, level, cost, costTime, reward, rewardExtra, eventTips, isOwnerNeedCare, itemIsFatigued))
            }
        }
        if (foundFatigueTip == null) {
            val rawTip = ProtoWire.extractAllStrings(data).firstOrNull { s: String -> QQPetDirectBridge.containsFatigueKeyword(s) }
            if (rawTip != null) {
                foundFatigueTip = rawTip.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
                    .replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
            }
        }
        val fatigued = if (list.isNotEmpty()) list.all { it.isFatigued } else !foundFatigueTip.isNullOrEmpty()
        return Triple(list, fatigued, foundFatigueTip)
    }
}

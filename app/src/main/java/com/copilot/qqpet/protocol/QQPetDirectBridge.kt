package com.copilot.qqpet.protocol

import android.content.Context
import com.copilot.qqpet.protocol.channel.OidbChannel
import com.copilot.qqpet.protocol.client.PetBathProtocolClient
import com.copilot.qqpet.protocol.client.PetCareProtocolClient
import com.copilot.qqpet.protocol.client.PetCareerProtocolClient
import com.copilot.qqpet.protocol.client.PetPkProtocolClient
import com.copilot.qqpet.protocol.client.PetSocialProtocolClient
import com.copilot.qqpet.protocol.utils.PetCostAttributeParser
import java.lang.reflect.Method


/**
 * QQ 宠物宿主反射发包桥接门面 (Facade)
 * 封装并委托底层 OIDB 发包通道与领域协议客户端
 */
class QQPetDirectBridge(private val classLoader: ClassLoader, private val context: Context? = null) {

    constructor(classLoader: ClassLoader) : this(classLoader, null)

    data class SelectEvent(val eventName: String, val subEventType: Long, val canDo: Boolean, val level: Int = 0, val cost: String = "", val costTime: String = "", val reward: String = "", val rewardExtra: String = "", val eventTips: String = "", val isOwnerNeedCare: Boolean = false, val isFatigued: Boolean = false)
    data class SchoolStageInfo(val stage: Int, val title: String, val limitStatus: Int, val isGraduated: Boolean = false, val lockReason: String = "")
    data class SecondMapDetails(val code: Int, val currentStage: Int, val lastSubEvent: Long, val stages: List<SchoolStageInfo>, val power: Long = 0L, val intel: Long = 0L, val charm: Long = 0L)
    data class LikeMember(val uin: Long, val nick: String, val headerUrl: String, val timestamp: Long, val desc: String, val canLikeBack: Boolean, val petId: String = "")
    data class PetAttributes(val energy: Float, val maxEnergy: Float = 100f, val clean: Float, val maxClean: Float = 100f, val mood: Float = 0f)
    data class BathItemConfig(val itemId: String, val name: String, val gold: Int, val cleanValue: Int, val defaultPurchaseCount: Int)
    data class BathResult(val code: Int, val newClean: Int, val addedClean: Int, val remainBalance: Int, val isFullClean: Boolean, val errorMsg: String? = null)
    data class FriendCoinBagInfo(val friendUin: Long, val friendNick: String, val friendPetId: String, val petNick: String, val coinbagId: String, val isSelf: Boolean = false)
    data class SnatchCoinBagResult(val code: Int, val coinbagId: String, val gotGold: Long, val status: Int, val alreadyOpened: Boolean, val errorMsg: String? = null)
    data class ProcessStoryFatigueResult(
        val code: Int,
        val isFatigued: Boolean,
        val tipText: String? = null,
        val eventType: Int = 0,
        val errorMsg: String? = null,
        val isHired: Boolean = false,
        val employedUin: Long = 0L,
        val bodyNote: String? = null,
        val storyText: String? = null
    )
    data class HireableFriend(val uin: Long, val friendNick: String, val petNick: String, val petId: String, val power: Long = 0L, val intel: Long = 0L, val charm: Long = 0L, val isIdle: Boolean = true, val remainingSec: Long = 0L) { val totalAttr: Long get() = power + intel + charm }
    data class FoodInventoryItem(val itemId: String, val name: String, val balance: Int, val energyValue: Int = 20)
    data class FeedDetailResult(val code: Int, val feedState: Int = 0, val tipText: String? = null, val errorMsg: String? = null)
    data class PkStatusInfo(val canPk: Boolean, val rawStatus: Int, val ongoingStoryId: String? = null, val remainingSec: Long = 0L)
    data class PkBattleResult(val code: Int, val storyId: String?, val myPower: Int = 0, val oppPower: Int = 0, val myNick: String = "", val oppNick: String = "", val isWin: Boolean = false, val leftDurationSec: Long = 0L, val errorMsg: String? = null)
    data class PkSettleResult(val code: Int, val goldEarned: Long = 0L, val title: String? = null, val desc: String? = null, val errorMsg: String? = null)

    companion object {
        private const val TAG = "QQPetDirectBridge"

        val resolvedDelegateClass: Class<*>?
            get() = OidbChannel.resolvedDelegateClass

        val resolvedSendMethodName: String
            get() = OidbChannel.resolvedSendMethodName

        @Volatile
        var cachedPetAttributes: PetAttributes? = null
            internal set

        @Volatile
        var lastFatigueDetected: Boolean = false
            internal set

        @Volatile
        var lastFatigueTip: String? = null
            internal set

        @Volatile
        var lastSelectEventsFatigued: Boolean = false
            internal set

        @Volatile
        var lastSelectEventsFatigueTip: String? = null
            internal set

        @Volatile
        var cachedOwnCoinBagId: String? = null
            internal set

        fun clearStaticRuntimeCache() {
            cachedPetAttributes = null
            lastFatigueDetected = false
            lastFatigueTip = null
            lastSelectEventsFatigued = false
            lastSelectEventsFatigueTip = null
            cachedOwnCoinBagId = null
        }

        fun parseCurrentAttrsFromCost(costText: String?): Pair<Float?, Float?> =
            PetCostAttributeParser.parseCurrentAttrsFromCost(costText)

        fun updateCachedAttributesFromCost(costText: String?) {
            PetCostAttributeParser.updateCachedAttributesFromCost(costText)
        }

        fun containsFatigueKeyword(text: String?): Boolean =
            PetCostAttributeParser.containsFatigueKeyword(text)

        fun getCandidateClassLoaders(primaryLoader: ClassLoader, context: Context?): List<ClassLoader> =
            OidbChannel.getCandidateClassLoaders(primaryLoader, context)

        fun tryLoadClass(name: String, loader: ClassLoader): Class<*>? =
            OidbChannel.tryLoadClass(name, loader)

        fun buildCandidateClassNames(): List<String> =
            OidbChannel.buildCandidateClassNames()

        fun findDelegateClass(classLoader: ClassLoader): Pair<Class<*>?, Method?> =
            OidbChannel.findDelegateClass(classLoader)

        fun findDelegateClass(loaders: List<ClassLoader>): Triple<Class<*>?, Method?, Class<*>?> =
            OidbChannel.findDelegateClass(loaders)
    }

    val channel = OidbChannel(classLoader, context)

    val isReady: Boolean
        get() = channel.isReady

    var isInternalSending: Boolean
        get() = channel.isInternalSending
        set(value) { channel.isInternalSending = value }

    private val bathClient = PetBathProtocolClient(channel) { clean ->
        cachedPetAttributes?.let { cachedPetAttributes = it.copy(clean = clean.toFloat()) }
    }

    private val careClient = PetCareProtocolClient(
        channel,
        onAttributesUpdated = { cachedPetAttributes = it },
        onOwnBagFound = { cachedOwnCoinBagId = it }
    )

    private val careerClient = PetCareerProtocolClient(
        channel,
        onFatigueDetected = { fat: Boolean, tip: String? ->
            lastFatigueDetected = fat
            lastFatigueTip = tip
        },
        onSelectEventsFatigue = { fat: Boolean, tip: String? ->
            lastSelectEventsFatigued = fat
            lastSelectEventsFatigueTip = tip
        },
        onCostParsed = { updateCachedAttributesFromCost(it) }
    )

    private val socialClient = PetSocialProtocolClient(channel) { bagId ->
        cachedOwnCoinBagId = bagId
    }

    private val pkClient = PetPkProtocolClient(channel)

    fun getCurrentRuntimeUin(): String = channel.getCurrentRuntimeUin()

    fun resolveUin(petId: String): String = channel.resolveUin(petId)

    fun sendOidb(
        commandName: String,
        command: Int,
        subCommand: Int,
        request: ByteArray,
        callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit
    ) = channel.sendOidb(commandName, command, subCommand, request, callback)

    fun queryOwnPet(callback: (code: Int, petId: String?, rawData: ByteArray?) -> Unit) =
        careClient.queryOwnPet(callback)

    fun resolveFoodId(): Long = careClient.resolveFoodId()

    fun feed(
        petId: String,
        foodId: Long = 0L,
        petUin: String = "",
        foodItemId: String = "",
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = careClient.feed(petId, foodId, petUin, foodItemId, callback)

    fun feedDetailed(
        petId: String,
        foodId: Long = 0L,
        petUin: String = "",
        foodItemId: String = "",
        callback: (FeedDetailResult) -> Unit
    ) = careClient.feedDetailed(petId, foodId, petUin, foodItemId, callback)

    fun fetchFoodInventory(callback: (code: Int, remain: Int, total: Int, items: List<FoodInventoryItem>) -> Unit) =
        careClient.fetchFoodInventory(callback)

    fun queryFeedTimes(callback: (code: Int, remain: Int, total: Int) -> Unit) =
        careClient.queryFeedTimes(callback)

    fun bath(
        petId: String,
        cleanValue: Int = 100,
        stage: Int = 2,
        petUin: String = "",
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = bathClient.bath(petId, cleanValue, stage, petUin, callback)

    fun fetchBathItemConfig(callback: (code: Int, items: List<BathItemConfig>) -> Unit) =
        bathClient.fetchBathItemConfig(callback)

    fun fetchBathInventory(callback: (code: Int, balances: Map<String, Int>) -> Unit) =
        bathClient.fetchBathInventory(callback)

    fun buyBathItem(
        petId: String,
        itemId: String,
        count: Int = 5,
        scene: Long = 21L,
        callback: (code: Int, orderResult: Int, errorMsg: String?) -> Unit
    ) = bathClient.buyBathItem(petId, itemId, count, scene, callback)

    fun doBathOnce(
        petId: String,
        itemId: String,
        useNum: Int = 1,
        petUin: String = "",
        callback: (BathResult) -> Unit
    ) = bathClient.doBathOnce(petId, itemId, useNum, petUin, callback)

    fun queryPetAttributes(
        petId: String,
        isSelf: Boolean = true,
        callback: (code: Int, attrs: PetAttributes?) -> Unit
    ) = careClient.queryPetAttributes(petId, isSelf, callback)

    fun buyFood(
        petId: String,
        count: Long = 5L,
        itemType: String = "1",
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = careClient.buyFood(petId, count, itemType, callback)

    fun refreshProfile(callback: ((code: Int) -> Unit)? = null) =
        careClient.refreshProfile(callback)

    fun getPetAttributes(petId: String): PetAttributes? =
        careClient.getPetAttributes(petId)

    fun queryStoryStatus(
        petId: String,
        callback: (code: Int, remainingSec: Long?, totalSec: Long?, activeStoryId: String?, status: Long?, bodyNote: String?) -> Unit
    ) = careerClient.queryStoryStatus(petId, callback)

    fun queryProcessStoryInfo(
        storyId: String,
        petId: String,
        callback: (ProcessStoryFatigueResult) -> Unit
    ) = careerClient.queryProcessStoryInfo(storyId, petId, callback)

    fun settleStory(storyId: String, petId: String, callback: (code: Int, rawData: ByteArray?) -> Unit) =
        careerClient.settleStory(storyId, petId, callback)

    fun recallStory(storyId: String, petId: String, callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit) =
        careerClient.recallStory(storyId, petId, callback)

    fun startAdventure(
        petId: String,
        adventureName: String = "森林探险",
        subEventType: Long = 6701L,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = careerClient.startAdventure(petId, adventureName, subEventType, callback)

    fun startWork(
        petId: String,
        jobName: String = "小镇兼职",
        page: Long = 6400L,
        subEventType: Long = 6401L,
        hiredPetId: String = "",
        hiredUin: Long = 0L,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = careerClient.startWork(petId, jobName, page, subEventType, hiredPetId, hiredUin, callback)

    fun startSchool(
        petId: String,
        courseName: String = "基础学园课程",
        page: Long = 6100L,
        subEventType: Long = 6101L,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = careerClient.startSchool(petId, courseName, page, subEventType, callback)

    fun querySecondMapInfo(
        eventType: Long = 6100L,
        petId: String,
        callback: (code: Int, schoolStage: Int, lastSubEvent: Long, rawData: ByteArray?) -> Unit
    ) = careerClient.querySecondMapInfo(eventType, petId, callback)

    fun querySecondMapInfoDetails(
        eventType: Long = 6100L,
        petId: String,
        callback: (SecondMapDetails) -> Unit
    ) = careerClient.querySecondMapInfoDetails(eventType, petId, callback)

    fun querySelectEvents(
        eventType: Long,
        petId: String,
        schoolStage: Int = 0,
        careerType: Int = 0,
        callback: (code: Int, events: List<SelectEvent>, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = careerClient.querySelectEvents(eventType, petId, schoolStage, careerType, callback)

    fun fetchLikeList(
        extra: String = "",
        callback: (code: Int, members: List<LikeMember>, hasMore: Boolean, nextExtra: String, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = socialClient.fetchLikeList(extra, callback)

    fun sendLike(
        targetUin: Long,
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = socialClient.sendLike(targetUin, callback)

    fun queryLikeCount(
        targetUin: Long,
        callback: (code: Int, alreadyLiked: Boolean, likeCount: String, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) = socialClient.queryLikeCount(targetUin, callback)

    fun fetchOwnGroundCoinBag(
        petId: String,
        callback: (code: Int, coinbagId: String?, errorMsg: String?) -> Unit
    ) = socialClient.fetchOwnGroundCoinBag(petId, callback)

    fun fetchFriendCoinBags(
        cookie: String = "",
        callback: (code: Int, bags: List<FriendCoinBagInfo>, totalFriendsInPage: Int, hasMore: Boolean, nextCookie: String, errorMsg: String?) -> Unit
    ) = socialClient.fetchFriendCoinBags(cookie, callback)

    fun fetchPetFriendsPage(
        cookie: String = "",
        callback: (code: Int, friends: List<HireableFriend>, hasMore: Boolean, nextCookie: String, errorMsg: String?) -> Unit
    ) = socialClient.fetchPetFriendsPage(cookie, callback)

    fun snatchCoinBag(
        ownPetId: String,
        coinbagId: String,
        callback: (SnatchCoinBagResult) -> Unit
    ) = socialClient.snatchCoinBag(ownPetId, coinbagId, callback)

    fun queryFriendPkStatus(
        friendUin: Long,
        friendPetId: String,
        ownPetId: String,
        callback: (code: Int, info: PkStatusInfo?, errorMsg: String?) -> Unit
    ) = pkClient.queryFriendPkStatus(friendUin, friendPetId, ownPetId, callback)

    fun startPkBattle(
        targetUin: Long,
        targetPetId: String,
        ownPetId: String,
        bodyguardId: String = "",
        callback: (PkBattleResult) -> Unit
    ) = pkClient.startPkBattle(targetUin, targetPetId, ownPetId, bodyguardId, callback)

    fun settlePkBattle(
        storyId: String,
        ownPetId: String,
        callback: (PkSettleResult) -> Unit
    ) = pkClient.settlePkBattle(storyId, ownPetId, callback)
}

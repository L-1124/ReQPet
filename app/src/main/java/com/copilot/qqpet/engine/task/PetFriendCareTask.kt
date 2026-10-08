package com.copilot.qqpet.engine.task

import com.copilot.qqpet.engine.TaskLogger
import com.copilot.qqpet.engine.utils.randomJitter
import com.copilot.qqpet.engine.EngineLog
import android.content.Context
import com.copilot.qqpet.engine.PetAccountGateway
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责养宠好友宠物的代喂与代搓澡日常照料任务
 */
object PetFriendCareTask {
    private const val NETWORK_TIMEOUT_MS = 8000L

    data class FriendCareParams(
        val context: Context,
        val bridge: QQPetDirectBridge,
        val ownPetId: String,
        val energyThreshold: Int,
        val cleanThreshold: Int,
        val isManual: Boolean
    )

    data class CareResultSummary(
        val checkedCount: Int = 0,
        val fedCount: Int = 0,
        val bathedCount: Int = 0
    )

    data class FriendFeedRequest(
        val bridge: QQPetDirectBridge,
        val ownPetId: String,
        val friend: QQPetDirectBridge.HireableFriend,
        val targetThreshold: Int,
        val startEnergy: Int,
        val maxEnergy: Int
    )

    data class FriendBathRequest(
        val bridge: QQPetDirectBridge,
        val ownPetId: String,
        val friend: QQPetDirectBridge.HireableFriend,
        val targetThreshold: Int,
        val startClean: Int,
        val maxClean: Int
    )

    suspend fun executeAutoFriendCare(
        params: FriendCareParams,
        onLog: TaskLogger
    ): CareResultSummary {
        return try {
            onLog("[好友照料] 开始扫描养宠好友状态 (触发阈值: 体力<${params.energyThreshold} 喂食, 清洁<${params.cleanThreshold} 洗澡)...")
            val friends = resolveTargetFriends(params)
            if (friends.isEmpty()) {
                onLog("[好友照料] 暂未发现可照料的养宠好友")
                return CareResultSummary()
            }
            val summary = processFriendsBatch(params, friends, onLog)
            onLog("[好友照料汇总] 本轮共检测 ${summary.checkedCount} 位好友，成功喂食 ${summary.fedCount} 位、洗澡 ${summary.bathedCount} 位！")
            summary
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            EngineLog.w("PetFriendCareTask", "自动照料好友宠物异常: ${t.message}")
            if (params.isManual) onLog.warn("[好友照料] 执行异常: ${t.message}")
            CareResultSummary()
        }
    }

    /**
     * 只在刚刚雇佣成功时调用一次。低于阈值才补，补到大于等于阈值后结束。
     */
    suspend fun careJustHiredFriend(
        params: FriendCareParams,
        friend: QQPetDirectBridge.HireableFriend,
        onLog: TaskLogger
    ) {
        if (friend.uin <= 0L || friend.petId.isBlank() || friend.petId == params.ownPetId) return
        val friendName = friend.friendNick.ifEmpty { friend.uin.toString() }
        val petName = friend.petNick.ifEmpty { "小宠" }
        onLog("[雇佣照料] 刚雇佣「$friendName」，检查这一次是否低于阈值")
        val attrs = PetCareTask.queryPetAttributesAwait(params.bridge, friend.petId, isSelf = false)
        if (attrs == null) {
            onLog("[雇佣照料] 没查到「$friendName」的体力与清洁，本次不补")
            return
        }
        val curEnergy = attrs.energy.toInt()
        val maxEnergy = attrs.maxEnergy.toInt().coerceAtLeast(100)
        val curClean = attrs.clean.toInt()
        val maxClean = attrs.maxClean.toInt().coerceAtLeast(100)
        onLog("[雇佣照料] 「$friendName」· $petName：体力 $curEnergy/$maxEnergy，清洁 $curClean/$maxClean")
        if (curEnergy < params.energyThreshold && curEnergy < maxEnergy) {
            onLog("[雇佣照料] 体力低于 ${params.energyThreshold}，开始补到不低于该值")
            val req =
                FriendFeedRequest(params.bridge, params.ownPetId, friend, params.energyThreshold, curEnergy, maxEnergy)
            val (ok, newEnergy) = feedFriendWithAutoBuyAwait(req, onLog)
            if (ok) onLog("[雇佣照料] 已帮「$friendName」把体力补到 $newEnergy/$maxEnergy")
        }
        if (curClean < params.cleanThreshold && curClean < maxClean) {
            onLog("[雇佣照料] 清洁低于 ${params.cleanThreshold}，开始补到不低于该值")
            val req =
                FriendBathRequest(params.bridge, params.ownPetId, friend, params.cleanThreshold, curClean, maxClean)
            val bathRes = bathFriendWithAutoBuyAwait(req, onLog)
            if (bathRes.code == 0 && bathRes.addedClean > 0) {
                onLog("[雇佣照料] 已帮「$friendName」把清洁补到 ${bathRes.newClean}/$maxClean")
            }
        }
    }

    private suspend fun resolveTargetFriends(params: FriendCareParams): List<QQPetDirectBridge.HireableFriend> {
        val cached = PetAccountGateway.loadCachedHireableFriends(params.context)
        val sourceList = if (cached.isNotEmpty()) cached else {
            PetWorkTask.fetchAllHireableFriendsAwait(
                params.context,
                params.bridge,
                PetAdventureEngine.currentActiveUin,
                false
            )
        }
        val validFriends = sourceList.filter { it.uin > 0L && it.petId.isNotBlank() && it.petId != params.ownPetId }
        // 单轮平摊最多巡检 3 位好友，手动立即照料巡检 12 位，避免时序聚类
        return validFriends.take(if (params.isManual) 12 else 3)
    }

    private suspend fun processFriendsBatch(
        params: FriendCareParams,
        friends: List<QQPetDirectBridge.HireableFriend>,
        onLog: TaskLogger
    ): CareResultSummary {
        var checked = 0;
        var fed = 0;
        var bathed = 0
        for (friend in friends) {
            val attrs = PetCareTask.queryPetAttributesAwait(params.bridge, friend.petId, isSelf = false)
            if (attrs == null) {
                delay(randomJitter(720L, 1680L))
                continue
            }
            checked++
            val (didFeed, didBath) = inspectAndCareFriend(params, friend, attrs, onLog)
            if (didFeed) fed++
            if (didBath) bathed++
            delay(randomJitter(1500L, 2500L))
        }
        return CareResultSummary(checked, fed, bathed)
    }

    private suspend fun inspectAndCareFriend(
        params: FriendCareParams,
        friend: QQPetDirectBridge.HireableFriend,
        attrs: QQPetDirectBridge.PetAttributes,
        onLog: TaskLogger
    ): Pair<Boolean, Boolean> {
        val curEnergy = attrs.energy.toInt();
        val maxEnergy = attrs.maxEnergy.toInt().coerceAtLeast(100)
        val curClean = attrs.clean.toInt();
        val maxClean = attrs.maxClean.toInt().coerceAtLeast(100)
        val friendName = friend.friendNick.ifEmpty { friend.uin.toString() }
        val petName = friend.petNick.ifEmpty { "小宠" }
        val needFeed = curEnergy in 0 until params.energyThreshold && curEnergy < maxEnergy
        val needBath = curClean in 0 until params.cleanThreshold && curClean < maxClean

        if (params.isManual || needFeed || needBath) {
            val suffix = if (!needFeed && !needBath) " (状态健康，无需照料)" else ""
            onLog("[好友检测] 「$friendName」· $petName：体力 $curEnergy/$maxEnergy，清洁 $curClean/$maxClean$suffix")
        }
        var fedOk = false;
        var bathOk = false
        if (needFeed) {
            onLog("[好友喂食] 「$friendName」的「$petName」体力偏低，开始自动投喂...")
            val req =
                FriendFeedRequest(params.bridge, params.ownPetId, friend, params.energyThreshold, curEnergy, maxEnergy)
            val (ok, newEnergy) = feedFriendWithAutoBuyAwait(req, onLog)
            if (ok) {
                fedOk = true
                onLog("[好友喂食] 已帮好友「$friendName」补充体力至 $newEnergy/$maxEnergy")
            }
            delay(randomJitter(2000L, 3500L))
        }
        if (needBath) {
            onLog("[好友洗澡] 「$friendName」的「$petName」清洁偏低，开始自动搓澡...")
            val req =
                FriendBathRequest(params.bridge, params.ownPetId, friend, params.cleanThreshold, curClean, maxClean)
            val bathRes = bathFriendWithAutoBuyAwait(req, onLog)
            if (bathRes.code == 0 && bathRes.addedClean > 0) {
                bathOk = true
                onLog("[好友洗澡] 已帮好友「$friendName」搓澡洗香香，清洁度升至 ${bathRes.newClean}/$maxClean")
            }
        }
        return Pair(fedOk, bathOk)
    }

    suspend fun feedFriendWithAutoBuyAwait(
        req: FriendFeedRequest,
        onLog: TaskLogger
    ): Pair<Boolean, Int> {
        val friendName = req.friend.friendNick.ifEmpty { req.friend.uin.toString() }
        val petLabel =
            if (req.friend.petNick.isNotEmpty()) "${friendName}的「${req.friend.petNick}」" else "好友「$friendName」的宠物"
        var foodItemId = ensureFoodInventory(req.bridge, req.ownPetId, petLabel, onLog)
        var curEnergy = req.startEnergy;
        var feedCount = 0

        while (curEnergy < req.targetThreshold && curEnergy < req.maxEnergy && feedCount < 8) {
            var res = feedDetailedAwait(req.bridge, req.friend.petId, req.friend.uin.toString(), foodItemId)
            if (res.code == 1000210) {
                onLog("[好友投喂采购] 背包食物耗尽，自动补购 5 份爱心饼干...")
                val (buyCode, buyErr) = PetCareTask.buyFoodAwait(req.bridge, req.ownPetId, 5L, "1")
                if (buyCode == 0) {
                    delay(randomJitter(720L, 1680L))
                    val (_, _, items) = fetchFoodInventoryAwait(req.bridge)
                    foodItemId = items.firstOrNull { it.balance > 0 }?.itemId ?: foodItemId
                    res = feedDetailedAwait(req.bridge, req.friend.petId, req.friend.uin.toString(), foodItemId)
                } else {
                    onLog.error("[好友投喂] 自动补购食物失败: $buyErr")
                    break
                }
            }
            if (res.code == 0) {
                if (res.feedState == 1) break
                feedCount++
                curEnergy = (curEnergy + 10).coerceAtMost(req.maxEnergy)
                onLog("[好友投喂] 成功投喂 1 份爱心饼干 -> 估计体力 $curEnergy（阈值 ${req.targetThreshold}）")
                if (curEnergy >= req.targetThreshold || curEnergy >= req.maxEnergy) break
                delay(randomJitter(1200L, 2000L))
            } else {
                onLog("[好友投喂] 投喂回包: code=${res.code} ${res.tipText ?: res.errorMsg ?: ""}")
                break
            }
        }
        if (feedCount >= 8) {
            onLog.warn("[好友投喂] 投喂轮数已达 8 次上限，停止继续投喂")
        }
        return Pair(feedCount > 0, curEnergy)
    }

    private suspend fun ensureFoodInventory(
        bridge: QQPetDirectBridge,
        ownPetId: String,
        petLabel: String,
        onLog: TaskLogger
    ): String {
        val (invCode, _, foodItems) = fetchFoodInventoryAwait(bridge)
        val chosen = foodItems.firstOrNull { it.balance > 0 } ?: foodItems.firstOrNull()
        var itemId = chosen?.itemId ?: ""
        val balance = chosen?.balance ?: -1
        if (invCode == 0 && balance == 0) {
            onLog("[好友投喂采购] 背包食物库存为 0，正在为$petLabel 自动采购 5 份爱心饼干...")
            val (buyCode, _) = PetCareTask.buyFoodAwait(bridge, ownPetId, 5L, "1")
            if (buyCode == 0) {
                onLog("[好友投喂采购] 成功采购 5 份爱心饼干！")
                delay(randomJitter(720L, 1680L))
            }
        }
        return itemId
    }

    suspend fun bathFriendWithAutoBuyAwait(
        req: FriendBathRequest,
        onLog: TaskLogger
    ): QQPetDirectBridge.BathResult {
        val friendName = req.friend.friendNick.ifEmpty { req.friend.uin.toString() }
        val petLabel =
            if (req.friend.petNick.isNotEmpty()) "${friendName}的「${req.friend.petNick}」" else "好友「$friendName」的宠物"
        val (_, configs) = PetCareTask.fetchBathItemConfigAwait(req.bridge)
        val (_, inventory) = PetCareTask.fetchBathInventoryAwait(req.bridge)
        val chosenConfig = configs.firstOrNull { it.cleanValue > 0 } ?: configs.firstOrNull()
        val itemId = chosenConfig?.itemId ?: inventory.keys.firstOrNull() ?: "2010104"
        val itemName = chosenConfig?.name ?: "香皂片"
        var balance = inventory[itemId] ?: 0
        var curClean = req.startClean.coerceAtLeast(0)
        var totalAdded = 0;
        var steps = 0

        while (curClean < req.targetThreshold && curClean < req.maxClean && steps < 10) {
            steps++
            if (balance <= 0) {
                val buyRes = purchaseFriendSoap(req.bridge, req.ownPetId, itemId, itemName, petLabel, onLog)
                if (!buyRes.first) {
                    return QQPetDirectBridge.BathResult(-2, curClean, totalAdded, balance, false, buyRes.second)
                }
                balance += 5
            }
            val res = PetCareTask.doBathOnceAwait(req.bridge, req.friend.petId, itemId, 1, req.friend.uin.toString())
            if (res.code != 0) {
                if (balance > 0 && steps == 1) {
                    balance = 0; continue
                }
                onLog("[好友洗澡] 帮$petLabel 搓澡回包: code=${res.code} ${res.errorMsg ?: ""}")
                return QQPetDirectBridge.BathResult(res.code, curClean, totalAdded, balance, false, res.errorMsg)
            }
            curClean = res.newClean; totalAdded += res.addedClean; balance = res.remainBalance
            onLog("[好友搓澡] 帮$petLabel 消耗 1 份$itemName (+${res.addedClean}) -> 清洁度 $curClean/${req.maxClean}")
            if (curClean >= req.targetThreshold || res.isFullClean || curClean >= req.maxClean) break
            delay(randomJitter(1200L, 2000L))
        }
        if (steps >= 10 && curClean < req.targetThreshold) {
            onLog.warn("[好友洗澡] 洗澡步数已达 10 步上限，停止继续搓澡")
        }
        if (totalAdded > 0) {
            try {
                PetCareTask.bathAwait(req.bridge, req.friend.petId, req.friend.uin.toString())
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        return QQPetDirectBridge.BathResult(0, curClean, totalAdded, balance, curClean >= req.maxClean, null)
    }

    private suspend fun purchaseFriendSoap(
        bridge: QQPetDirectBridge,
        ownPetId: String,
        itemId: String,
        itemName: String,
        petLabel: String,
        onLog: TaskLogger
    ): Pair<Boolean, String?> {
        onLog("[好友洗护采购] 背包${itemName}不足，正在自动采购 5 份${itemName}用于帮$petLabel 洗澡...")
        val (buyCode, orderResult, buyErr) = PetCareTask.buyBathItemAwait(bridge, ownPetId, itemId, 5, scene = 21L)
        return if (buyCode == 0 && (orderResult == 1 || orderResult == 0)) {
            onLog("[好友洗护采购] 成功购入 5 份${itemName}！")
            delay(randomJitter(720L, 1680L))
            Pair(true, null)
        } else {
            val reason = if (orderResult == 2) "金币不足" else (buyErr ?: "code=$buyCode, orderResult=$orderResult")
            onLog.error("[好友洗护采购] 购买${itemName}失败: $reason")
            Pair(false, reason)
        }
    }

    suspend fun feedDetailedAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        petUin: String,
        foodItemId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.FeedDetailResult =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bridge.feedDetailed(petId, 0L, petUin, foodItemId) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }
        } ?: QQPetDirectBridge.FeedDetailResult(-1, 0, null, "超时")

    suspend fun fetchFoodInventoryAwait(
        bridge: QQPetDirectBridge,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, Int, List<QQPetDirectBridge.FoodInventoryItem>> =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bridge.fetchFoodInventory { code, remain, _, items ->
                    if (cont.isActive) cont.resume(Triple(code, remain, items))
                }
            }
        } ?: Triple(-1, 0, emptyList())
}

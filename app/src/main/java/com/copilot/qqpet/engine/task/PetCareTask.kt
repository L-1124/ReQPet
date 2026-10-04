package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责小宠自身日常自理巡检、体力进食与清洁沐浴（含饼干与香皂自动补购）
 */
object PetCareTask {

    private const val NETWORK_TIMEOUT_MS = 8000L
    private const val ENERGY_PER_FEED = com.copilot.qqpet.engine.utils.PetPureCalculations.ENERGY_PER_FEED
    private const val MAX_FEED_ROUNDS = com.copilot.qqpet.engine.utils.PetPureCalculations.MAX_FEED_ROUNDS_PER_SESSION

    suspend fun queryFeedTimesAwait(bridge: QQPetDirectBridge, timeoutMs: Long = NETWORK_TIMEOUT_MS): Triple<Int, Int, Int> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryFeedTimes { code, remain, total ->
                        if (cont.isActive) cont.resume(Triple(code, remain, total))
                    }
                }
            } ?: Triple(-99, 0, 0)
        } catch (_: Throwable) {
            Triple(-99, 0, 0)
        }

    suspend fun feedAwait(bridge: QQPetDirectBridge, petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, ByteArray?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.feed(petId) { code, data, _ ->
                        if (cont.isActive) cont.resume(Pair(code, data))
                    }
                }
            } ?: Pair(-99, null)
        } catch (_: Throwable) {
            Pair(-99, null)
        }

    suspend fun queryPetAttributesAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        isSelf: Boolean = true,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.PetAttributes? =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryPetAttributes(petId, isSelf) { _, attrs ->
                        if (cont.isActive) cont.resume(attrs)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }

    suspend fun fetchBathItemConfigAwait(
        bridge: QQPetDirectBridge,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, List<QQPetDirectBridge.BathItemConfig>> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchBathItemConfig { code, items ->
                        if (cont.isActive) cont.resume(Pair(code, items))
                    }
                }
            } ?: Pair(-99, emptyList())
        } catch (_: Throwable) {
            Pair(-99, emptyList())
        }

    suspend fun fetchBathInventoryAwait(
        bridge: QQPetDirectBridge,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, Map<String, Int>> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchBathInventory { code, map ->
                        if (cont.isActive) cont.resume(Pair(code, map))
                    }
                }
            } ?: Pair(-99, emptyMap())
        } catch (_: Throwable) {
            Pair(-99, emptyMap())
        }

    suspend fun buyBathItemAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        itemId: String,
        count: Int = 5,
        scene: Long = 21L,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, Int, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.buyBathItem(petId, itemId, count, scene) { code, orderResult, err ->
                        if (cont.isActive) cont.resume(Triple(code, orderResult, err))
                    }
                }
            } ?: Triple(-99, 0, "超时")
        } catch (t: Throwable) {
            Triple(-99, 0, t.message)
        }

    suspend fun doBathOnceAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        itemId: String,
        useNum: Int = 1,
        petUin: String = "",
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.BathResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.doBathOnce(petId, itemId, useNum, petUin) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.BathResult(-99, -1, 0, -1, false, "超时")
        } catch (t: Throwable) {
            QQPetDirectBridge.BathResult(-99, -1, 0, -1, false, t.message)
        }

    suspend fun bathAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        petUin: String = "",
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, ByteArray?> =
        try {
            try {
                withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        bridge.bath(petId, cleanValue = 50, stage = 1, petUin = petUin) { _, _, _ ->
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
            } catch (_: Throwable) {}
            delay(600L)
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.bath(petId, cleanValue = 100, stage = 2, petUin = petUin) { code, data, _ ->
                        if (cont.isActive) cont.resume(Pair(code, data))
                    }
                }
            } ?: Pair(-99, null)
        } catch (_: Throwable) {
            Pair(-99, null)
        }

    suspend fun buyFoodAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        count: Long = 5L,
        itemType: String = "1",
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.buyFood(petId, count, itemType) { code, _, errorMsg ->
                        if (cont.isActive) cont.resume(Pair(code, errorMsg))
                    }
                }
            } ?: Pair(-99, "超时")
        } catch (t: Throwable) {
            Pair(-99, t.message)
        }

    private data class FeedLoopParam(
        val startEnergy: Int,
        val targetThreshold: Int,
        val maxRounds: Int
    )

    suspend fun feedWithAutoBuyAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        targetThreshold: Int = 80,
        onLog: (String) -> Unit
    ): Pair<Int, String?> {
        val attrs = queryPetAttributesAwait(bridge, petId) ?: bridge.getPetAttributes(petId)
        val curEnergy = attrs?.energy?.toInt() ?: -1
        if (curEnergy >= targetThreshold && targetThreshold > 0) {
            onLog("✨ [进食检查] 当前体力充足 ($curEnergy/$targetThreshold)，无需补充爱心饼干")
            return Pair(0, null)
        }
        val maxRounds = if (targetThreshold > 0 && curEnergy >= 0) {
            com.copilot.qqpet.engine.utils.PetPureCalculations.calculateFeedingRounds(curEnergy, targetThreshold)
        } else {
            1
        }
        val loopParam = FeedLoopParam(curEnergy, targetThreshold, maxRounds)
        return executeFeedLoop(bridge, petId, loopParam, onLog)
    }

    suspend fun feedWithAutoBuyAwait(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        targetThreshold: Int = 80,
        onLog: (String) -> Unit
    ): Pair<Int, String?> = feedWithAutoBuyAwait(bridge, petId, targetThreshold, onLog)

    private suspend fun executeFeedLoop(
        bridge: QQPetDirectBridge,
        petId: String,
        param: FeedLoopParam,
        onLog: (String) -> Unit
    ): Pair<Int, String?> {
        var curEnergy = param.startEnergy
        var fedCount = 0
        var lastCode = 0
        var lastErr: String? = null
        val rounds = param.maxRounds.coerceIn(1, MAX_FEED_ROUNDS)

        while (fedCount < rounds) {
            val (fCode, fErr) = tryFeedOnceWithAutoBuy(bridge, petId, onLog)
            lastCode = fCode
            lastErr = fErr
            if (fCode != 0) break

            fedCount++
            curEnergy = if (curEnergy >= 0) minOf(100, curEnergy + ENERGY_PER_FEED) else curEnergy
            val curStr = if (curEnergy >= 0) " -> 预估体力: $curEnergy/100" else ""
            onLog("🍲 [日常进食] 成功喂食第 $fedCount 次爱心饼干 (+${ENERGY_PER_FEED} 体力)$curStr")
            if (param.targetThreshold > 0 && curEnergy >= param.targetThreshold) break
            delay(500L)
        }

        queryPetAttributesAwait(bridge, petId)
        bridge.refreshProfile()
        if (fedCount > 0) {
            val finalAttrs = bridge.getPetAttributes(petId)
            val finalEnergy = finalAttrs?.energy?.toInt() ?: curEnergy
            onLog("🎉 [日常进食] 进食补充完成！共投喂 $fedCount 次，最新体力: $finalEnergy/100")
        }
        return Pair(lastCode, lastErr)
    }

    private suspend fun tryFeedOnceWithAutoBuy(
        bridge: QQPetDirectBridge,
        petId: String,
        onLog: (String) -> Unit
    ): Pair<Int, String?> {
        val (fCode, _) = feedAwait(bridge, petId)
        if (fCode == 1000210) {
            onLog("🛒 [自动采购] 背包饼干不足 (code=1000210)，立即自动采购 5 份爱心饼干...")
            val (buyCode, buyErr) = buyFoodAwait(bridge, petId, 5L, "1")
            if (buyCode == 0) {
                onLog("✅ [自动采购] 5 份爱心饼干采购入库成功！继续为小宠喂食...")
                delay(500L)
                val (retryCode, _) = feedAwait(bridge, petId)
                return Pair(retryCode, if (retryCode == 0) null else "重试喂食回包 code=$retryCode")
            } else {
                onLog("❌ [自动采购] 采购爱心饼干失败: code=$buyCode, 说明: ${buyErr ?: "金币不足或网络异常"}")
                return Pair(buyCode, buyErr)
            }
        }
        return Pair(fCode, if (fCode == 0) null else "喂食回包 code=$fCode")
    }

    suspend fun bathWithAutoBuyAwait(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        onLog: (String) -> Unit
    ): QQPetDirectBridge.BathResult {
        val target = resolveBathTarget(bridge, petId)
        if (target.startClean >= target.maxClean) {
            onLog("✨ [沐浴检查] 当前清洁度已满 (${target.startClean}/${target.maxClean})，无需消耗${target.itemName} (库存: ${target.balance})")
            return QQPetDirectBridge.BathResult(0, target.startClean, 0, target.balance, true, null)
        }
        val loopRes = executeBathLoop(bridge, petId, target, onLog)
        if (!loopRes.success) {
            return QQPetDirectBridge.BathResult(loopRes.code, loopRes.curClean, loopRes.totalAdded, loopRes.balance, false, loopRes.errorMsg)
        }
        try { bathAwait(bridge, petId) } catch (_: Throwable) {}
        queryPetAttributesAwait(bridge, petId)
        return QQPetDirectBridge.BathResult(0, loopRes.curClean, loopRes.totalAdded, loopRes.balance, loopRes.curClean >= target.maxClean, null)
    }

    data class BathLoopResult(val success: Boolean, val code: Int, val curClean: Int, val totalAdded: Int, val balance: Int, val errorMsg: String?)

    private suspend fun executeBathLoop(
        bridge: QQPetDirectBridge, petId: String, target: BathTargetInfo, onLog: (String) -> Unit
    ): BathLoopResult {
        var curClean = if (target.startClean >= 0) target.startClean else 0
        var totalAdded = 0
        var balance = target.balance
        var steps = 0
        while (curClean < target.maxClean && steps < 12) {
            steps++
            if (balance <= 0) {
                val (_, newBal, err) = purchaseSoapIfNeeded(bridge, petId, target.itemId, target.itemName, target.cleanPerSoap, target.defaultBuyCount, curClean, target.maxClean, onLog)
                if (err != null) return BathLoopResult(false, -2, curClean, totalAdded, balance, err)
                balance = newBal
            }
            val res = doBathOnceAwait(bridge, petId, target.itemId, 1)
            if (res.code != 0) {
                if (balance > 0 && steps == 1) { balance = 0; continue }
                return BathLoopResult(false, res.code, curClean, totalAdded, balance, res.errorMsg)
            }
            curClean = res.newClean
            totalAdded += res.addedClean
            balance = res.remainBalance
            onLog("🧼 [搓澡进度] 消耗 1 份${target.itemName} (+${res.addedClean}) -> 清洁度 $curClean/${target.maxClean} (剩余库存: $balance)")
            if (res.isFullClean || curClean >= target.maxClean) break
            delay(450L)
        }
        return BathLoopResult(true, 0, curClean, totalAdded, balance, null)
    }

    data class BathTargetInfo(
        val startClean: Int, val maxClean: Int, val itemId: String,
        val itemName: String, val cleanPerSoap: Int, val defaultBuyCount: Int, val balance: Int
    )

    private suspend fun resolveBathTarget(bridge: QQPetDirectBridge, petId: String): BathTargetInfo {
        val attrs = queryPetAttributesAwait(bridge, petId) ?: bridge.getPetAttributes(petId)
        val startClean = attrs?.clean?.toInt() ?: -1
        val maxClean = attrs?.maxClean?.toInt()?.takeIf { it > 0 } ?: 100
        val (_, configs) = fetchBathItemConfigAwait(bridge)
        val (_, inventory) = fetchBathInventoryAwait(bridge)
        val chosenConfig = configs.firstOrNull { it.cleanValue > 0 } ?: configs.firstOrNull()
        val itemId = chosenConfig?.itemId ?: inventory.keys.firstOrNull() ?: "2010104"
        val itemName = chosenConfig?.name ?: "香皂片"
        val cleanPerSoap = chosenConfig?.cleanValue?.takeIf { it > 0 } ?: 10
        val defaultBuyCount = chosenConfig?.defaultPurchaseCount?.takeIf { it > 0 } ?: 5
        val balance = inventory[itemId] ?: 0
        return BathTargetInfo(startClean, maxClean, itemId, itemName, cleanPerSoap, defaultBuyCount, balance)
    }

    private suspend fun purchaseSoapIfNeeded(
        bridge: QQPetDirectBridge, petId: String, itemId: String, itemName: String,
        cleanPerSoap: Int, defaultBuyCount: Int, curClean: Int, maxClean: Int, onLog: (String) -> Unit
    ): Triple<Int, Int, String?> {
        val gapClean = (maxClean - curClean).coerceAtLeast(cleanPerSoap)
        val buyCount = maxOf(((gapClean + cleanPerSoap - 1) / cleanPerSoap).coerceIn(1, 10), defaultBuyCount)
        onLog("🛒 [自动采购] 背包${itemName}不足 (库存 0)，正在自动采购 $buyCount 份${itemName}...")
        val (buyCode, orderResult, buyErr) = buyBathItemAwait(bridge, petId, itemId, buyCount)
        if (buyCode == 0 && (orderResult == 1 || orderResult == 0)) {
            onLog("✅ [自动采购] 成功购入 $buyCount 份${itemName}！继续为小宠搓澡...")
            delay(400L)
            return Triple(buyCount, buyCount, null)
        }
        val reason = if (orderResult == 2) "金币不足" else (buyErr ?: "code=$buyCode, orderResult=$orderResult")
        onLog("❌ [自动采购] 购买${itemName}失败: $reason")
        return Triple(0, 0, "购买${itemName}失败($reason)")
    }
}

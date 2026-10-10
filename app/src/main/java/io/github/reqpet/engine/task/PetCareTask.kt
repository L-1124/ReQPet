package io.github.reqpet.engine.task

import io.github.reqpet.engine.TaskLogger
import io.github.reqpet.engine.utils.randomJitter
import android.content.Context
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责小宠自身日常自理巡检、体力进食与清洁沐浴（含饼干与香皂自动补购）
 */
object PetCareTask {

    private const val NETWORK_TIMEOUT_MS = 8000L
    private const val ENERGY_PER_FEED = io.github.reqpet.engine.utils.PetPureCalculations.ENERGY_PER_FEED
    private const val MAX_FEED_ROUNDS = io.github.reqpet.engine.utils.PetPureCalculations.MAX_FEED_ROUNDS_PER_SESSION

    suspend fun queryFeedTimesAwait(
        bridge: QQPetDirectBridge,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, Int, Int> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryFeedTimes { code, remain, total ->
                        if (cont.isActive) cont.resume(Triple(code, remain, total))
                    }
                }
            } ?: Triple(-99, 0, 0)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Triple(-99, 0, 0)
        }

    suspend fun feedAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, ByteArray?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.feed(petId) { code, data, _ ->
                        if (cont.isActive) cont.resume(Pair(code, data))
                    }
                }
            } ?: Pair(-99, null)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
                    bridge.queryPetAttributes(petId, isSelf) { code, attrs ->
                        if (cont.isActive) cont.resume(if (code == 0) attrs else null)
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            if (t is kotlinx.coroutines.CancellationException) throw t
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
            if (t is kotlinx.coroutines.CancellationException) throw t
            QQPetDirectBridge.BathResult(-99, -1, 0, -1, false, t.message)
        }

    suspend fun buyFoodAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        count: Long = 5L,
        itemType: String = "9990032",
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
            if (t is kotlinx.coroutines.CancellationException) throw t
            Pair(-99, t.message)
        }

    suspend fun fetchOneClickCareConfigAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, QQPetDirectBridge.OneClickCareConfig?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchOneClickCareConfig(petId) { code, config, _ ->
                        if (cont.isActive) cont.resume(Pair(code, config))
                    }
                }
            } ?: Pair(-99, null)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Pair(-99, null)
        }

    suspend fun doOneClickCareAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        biscuitCost: Int,
        soapCost: Int,
        expGain: Int,
        isGuestCare: Boolean = false,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.OneClickCareResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.doOneClickCare(petId, biscuitCost, soapCost, expGain, isGuestCare) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.OneClickCareResult(-99, 0, 0, "超时")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            QQPetDirectBridge.OneClickCareResult(-99, 0, 0, e.message)
        }

    suspend fun executeOneClickCareWithAutoBuyAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        attrs: QQPetDirectBridge.PetAttributes,
        onLog: TaskLogger
    ): QQPetDirectBridge.OneClickCareResult {
        val (_, fetchedConfig) = fetchOneClickCareConfigAwait(bridge, petId)
        val config = fetchedConfig ?: QQPetDirectBridge.OneClickCareConfig()

        val curEnergy = attrs.energy.toInt().coerceAtLeast(0)
        val maxEnergy = attrs.maxEnergy.toInt().takeIf { it > 0 } ?: 100
        val curClean = attrs.clean.toInt().coerceAtLeast(0)
        val maxClean = attrs.maxClean.toInt().takeIf { it > 0 } ?: 100

        val hungerDiff = (maxEnergy - curEnergy).coerceAtLeast(0)
        val cleanDiff = (maxClean - curClean).coerceAtLeast(0)

        if (hungerDiff == 0 && cleanDiff == 0) {
            onLog("[一键呵护] 宠物体力与清洁均已全满 ($curEnergy/$maxEnergy, $curClean/$maxClean)，无需照料")
            return QQPetDirectBridge.OneClickCareResult(1, 0, 0, null)
        }

        val cappedHunger =
            if (config.hungerCap > 0) hungerDiff.coerceAtMost((config.hungerCap - curEnergy).coerceAtLeast(0)) else hungerDiff
        val cappedClean =
            if (config.cleanCap > 0) cleanDiff.coerceAtMost((config.cleanCap - curClean).coerceAtLeast(0)) else cleanDiff

        val needBiscuits =
            if (config.hungerPerBiscuit > 0 && cappedHunger > 0) (cappedHunger + config.hungerPerBiscuit - 1) / config.hungerPerBiscuit else 0
        val needSoaps =
            if (config.cleanPerSoap > 0 && cappedClean > 0) (cappedClean + config.cleanPerSoap - 1) / config.cleanPerSoap else 0

        val staminaExp = (cappedHunger * config.expPerHunger).toInt()
        val cleanExp = (cappedClean * config.expPerClean).toInt()
        val expectExpGain = (staminaExp + cleanExp).coerceAtMost(config.dailyExpLimitHost)

        onLog("[一键呵护] 开始发起恢复: 预估需饼干 $needBiscuits 块, 香皂 $needSoaps 块, 预期经验 +$expectExpGain")
        var result = doOneClickCareAwait(bridge, petId, needBiscuits, needSoaps, expectExpGain)

        if (result.code == 3) {
            val biscuitShortfall = result.biscuitCostOrShortfall
            val soapShortfall = result.soapCostOrShortfall
            onLog("[一键呵护] 道具不足 (缺饼干 $biscuitShortfall 块, 缺香皂 $soapShortfall 块)，正在自动补购...")

            var buySuccess = true
            if (biscuitShortfall > 0) {
                val (foodCode, foodErr) = buyFoodAwait(bridge, petId, count = biscuitShortfall.toLong())
                if (foodCode != 0) {
                    onLog("[一键呵护] 补购饼干失败 ($foodErr)，终止自动恢复")
                    buySuccess = false
                }
            }
            if (buySuccess && soapShortfall > 0) {
                val (_, configs) = fetchBathItemConfigAwait(bridge)
                val soapItem = configs.firstOrNull { it.cleanValue > 0 }
                val soapItemId = soapItem?.itemId ?: "2000001"
                val (soapCode, orderRes, soapErr) = buyBathItemAwait(
                    bridge,
                    petId,
                    itemId = soapItemId,
                    count = soapShortfall
                )
                if (soapCode != 0 || orderRes != 1) {
                    onLog("[一键呵护] 补购香皂失败 ($soapErr)，终止自动恢复")
                    buySuccess = false
                }
            }

            if (buySuccess) {
                onLog("[一键呵护] 缺额补购完成，正在重试一键拉满...")
                delay(randomJitter(500L, 1000L))
                result = doOneClickCareAwait(bridge, petId, needBiscuits, needSoaps, expectExpGain)
            }
        }

        when (result.code) {
            1 -> onLog("[一键呵护] 恢复成功！实际消耗饼干 ${result.biscuitCostOrShortfall} 块, 香皂 ${result.soapCostOrShortfall} 块")
            2 -> onLog("[一键呵护] 宠物状态目前良好，无需照料")
            3 -> onLog("[一键呵护] 道具仍不足，暂停照料")
            5 -> onLog("[一键呵护] 金币不足，无法完成恢复")
            else -> onLog("[一键呵护] 恢复未成功 (code=${result.code}, err=${result.errorMsg ?: "无"})")
        }

        return result
    }

    private data class FeedLoopParam(
        val startEnergy: Int,
        val targetThreshold: Int,
        val maxEnergy: Int,
        val maxRounds: Int
    )

    suspend fun feedWithAutoBuyAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        targetThreshold: Int = 80,
        onLog: TaskLogger
    ): Pair<Int, String?> {
        val attrs = queryPetAttributesAwait(bridge, petId) ?: bridge.getPetAttributes(petId)
        val curEnergy = attrs?.energy?.toInt() ?: -1
        val maxEnergy = attrs?.maxEnergy?.toInt()?.takeIf { it > 0 } ?: 100
        if (targetThreshold > 0 && curEnergy >= 0 && curEnergy >= targetThreshold) {
            onLog("[进食检查] 当前体力已不低于阈值 ($curEnergy>=$targetThreshold)，无需补充爱心饼干")
            return Pair(0, null)
        }
        val maxRounds = if (curEnergy >= 0) {
            io.github.reqpet.engine.utils.PetPureCalculations.calculateFeedingRounds(
                curEnergy, targetThreshold, maxValue = maxEnergy
            )
        } else {
            MAX_FEED_ROUNDS
        }
        val loopParam = FeedLoopParam(curEnergy, targetThreshold, maxEnergy, maxRounds)
        return executeFeedLoop(bridge, petId, loopParam, onLog)
    }

    suspend fun feedWithAutoBuyAwait(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        targetThreshold: Int = 80,
        onLog: TaskLogger
    ): Pair<Int, String?> = feedWithAutoBuyAwait(bridge, petId, targetThreshold, onLog)

    private suspend fun executeFeedLoop(
        bridge: QQPetDirectBridge,
        petId: String,
        param: FeedLoopParam,
        onLog: TaskLogger
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
            if (curEnergy >= 0) curEnergy = minOf(param.maxEnergy, curEnergy + ENERGY_PER_FEED)
            val curStr =
                if (curEnergy >= 0) " -> 估计+${ENERGY_PER_FEED}: $curEnergy（阈值 ${param.targetThreshold}）" else ""
            onLog("[日常进食] 成功喂食第 $fedCount 次爱心饼干$curStr")
            if (param.targetThreshold > 0 && curEnergy >= param.targetThreshold) break
            if (curEnergy >= param.maxEnergy) break
            delay(randomJitter(300L, 700L))
        }

        if (fedCount > 0) {
            onLog("[日常进食] 进食补充完成！共投喂 $fedCount 次，体力: $curEnergy（阈值 ${param.targetThreshold}）")
        }
        return Pair(lastCode, lastErr)
    }

    private suspend fun tryFeedOnceWithAutoBuy(
        bridge: QQPetDirectBridge,
        petId: String,
        onLog: TaskLogger
    ): Pair<Int, String?> {
        val (fCode, _) = feedAwait(bridge, petId)
        if (fCode == 1000210) {
            onLog("[自动采购] 背包饼干不足 (code=1000210)，立即自动采购 5 份爱心饼干...")
            val (buyCode, buyErr) = buyFoodAwait(bridge, petId, 5L)
            if (buyCode == 0) {
                onLog("[自动采购] 5 份爱心饼干采购入库成功！继续为小宠喂食...")
                delay(randomJitter(300L, 700L))
                val (retryCode, _) = feedAwait(bridge, petId)
                return Pair(retryCode, if (retryCode == 0) null else "重试喂食回包 code=$retryCode")
            } else {
                onLog.error("[自动采购] 采购爱心饼干失败: code=$buyCode, 说明: ${buyErr ?: "金币不足或网络异常"}")
                return Pair(buyCode, buyErr)
            }
        }
        return Pair(fCode, if (fCode == 0) null else "喂食回包 code=$fCode")
    }

    suspend fun bathWithAutoBuyAwait(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        targetThreshold: Int = 80,
        onLog: TaskLogger
    ): QQPetDirectBridge.BathResult {
        val attrs = queryPetAttributesAwait(bridge, petId)
        if (attrs == null || !attrs.clean.isFinite() || !attrs.maxClean.isFinite()) {
            return QQPetDirectBridge.BathResult(-104, -1, 0, -1, false, "实时清洁度未确认，暂停照料")
        }
        return bathTargetWithAutoBuyAwait(
            bridge, petId, petId, "", attrs.clean.toInt(),
            attrs.maxClean.toInt(), targetThreshold, onLog
        )
    }

    internal suspend fun bathTargetWithAutoBuyAwait(
        bridge: QQPetDirectBridge, ownPetId: String, targetPetId: String, friendUin: String,
        startClean: Int, maxClean: Int, targetThreshold: Int, onLog: TaskLogger
    ): QQPetDirectBridge.BathResult = BathCareRunner(object : BathOperations {
        override suspend fun configs() = fetchBathItemConfigAwait(bridge)
        override suspend fun inventory() = fetchBathInventoryAwait(bridge)
        override suspend fun buy(itemId: String, count: Int) = buyBathItemAwait(bridge, ownPetId, itemId, count)
        override suspend fun bath(itemId: String) = doBathOnceAwait(bridge, targetPetId, itemId, 1, friendUin)
    }, onLog).run(startClean, maxClean, targetThreshold)
}

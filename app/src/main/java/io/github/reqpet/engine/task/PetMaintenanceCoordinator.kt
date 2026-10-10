package io.github.reqpet.engine.task

import io.github.reqpet.engine.utils.randomJitter
import android.content.Context
import io.github.reqpet.engine.PetAccountGateway
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.engine.RuntimeDiagnostics
import io.github.reqpet.engine.model.StoryStatusResult
import io.github.reqpet.engine.state.AccountSessionStore
import io.github.reqpet.engine.resilience.RateLimitExceededException
import io.github.reqpet.protocol.QQPetDirectBridge

/**
 * 负责后台周期性日常维护任务协调（自理、福袋、回踩、主动串门与自动PK）
 */
object PetMaintenanceCoordinator {

    private const val CARE_CHECK_INTERVAL_MS = 3 * 60 * 1000L
    private const val COIN_BAG_INTERVAL_MS = 5 * 60 * 1000L
    private const val LIKE_BACK_INTERVAL_MS = 6 * 60 * 1000L
    private const val ACTIVE_VISIT_INTERVAL_MS = 8 * 60 * 1000L
    private val careRetry = CareRetryGate()

    fun resetCareBackoff() = careRetry.reset()

    /** 距离下一次喂食、洗澡、福袋、回踩、串门或 PK 到点还有多久。外出不会拉长这个等待。 */
    fun millisUntilNextCheck(
        context: Context,
        now: Long = System.currentTimeMillis(),
        isOuting: Boolean = false
    ): Long {
        val due = ArrayList<Long>(5)
        if (PetAdventureEngine.enableOneClickCare || PetAdventureEngine.enableCare) due += maxOf(
            careRetry.waitMillis(now), waitAfter(
                PetAdventureEngine.lastCareTimeMillis,
                CARE_CHECK_INTERVAL_MS,
                now
            )
        )
        if (PetAdventureEngine.enableClaimCoinBag) due += waitAfter(
            PetAdventureEngine.lastCoinBagTimeMillis,
            COIN_BAG_INTERVAL_MS,
            now
        )
        if (PetAdventureEngine.enableLikeBack) due += waitAfter(
            PetAdventureEngine.lastLikeBackTimeMillis,
            LIKE_BACK_INTERVAL_MS,
            now
        )
        if (PetAdventureEngine.enableActiveVisit) due += waitAfter(
            PetAdventureEngine.lastActiveVisitTimeMillis,
            ACTIVE_VISIT_INTERVAL_MS,
            now
        )
        if (!isOuting && PetAdventureEngine.enableAutoPk && AccountSessionStore.getDailyPkCount(
                context,
                PetAdventureEngine.currentActiveUin
            ) < 10
        ) {
            due += waitAfter(PetAdventureEngine.lastPkTimeMillis, PetAdventureEngine.pkCooldownMillis, now)
        }
        return due.minOrNull() ?: Long.MAX_VALUE
    }

    private fun waitAfter(last: Long, interval: Long, now: Long): Long {
        if (last <= 0L) return 0L
        return (last + interval + 1L - now).coerceAtLeast(0L)
    }

    suspend fun performMaintenance(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        story: StoryStatusResult,
        forceCheck: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        try {
            checkCareMaintenance(context, bridge, petId, now, forceCheck)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[自理] 照料维护异常: ${e.message}")
            RuntimeDiagnostics.error(
                "maintenance_error", e, "cycle" to PetAdventureEngine.currentCycleId,
                "transaction" to PetAdventureEngine.currentTransactionId,
                "item" to "care"
            )
        }
        try {
            checkCoinBagMaintenance(context, bridge, petId, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[福袋] 维护巡检异常: ${e.message}")
            RuntimeDiagnostics.error(
                "maintenance_error", e, "cycle" to PetAdventureEngine.currentCycleId,
                "transaction" to PetAdventureEngine.currentTransactionId,
                "item" to "coin_bag"
            )
        }
        try {
            checkLikeBackMaintenance(context, bridge, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[回踩] 维护巡检异常: ${e.message}")
            RuntimeDiagnostics.error(
                "maintenance_error", e, "cycle" to PetAdventureEngine.currentCycleId,
                "transaction" to PetAdventureEngine.currentTransactionId,
                "item" to "like_back"
            )
        }
        try {
            checkActiveVisitMaintenance(context, bridge, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[串门] 维护巡检异常: ${e.message}")
            RuntimeDiagnostics.error(
                "maintenance_error", e, "cycle" to PetAdventureEngine.currentCycleId,
                "transaction" to PetAdventureEngine.currentTransactionId,
                "item" to "active_visit"
            )
        }
        try {
            checkAutoPkMaintenance(context, bridge, petId, now, story)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[自动PK] 维护巡检异常: ${e.message}")
            RuntimeDiagnostics.error(
                "maintenance_error", e, "cycle" to PetAdventureEngine.currentCycleId,
                "transaction" to PetAdventureEngine.currentTransactionId,
                "item" to "pk"
            )
        }
    }

    private suspend fun checkCareMaintenance(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        now: Long,
        forceCheck: Boolean = false
    ) {
        if (!PetAdventureEngine.enableOneClickCare && !PetAdventureEngine.enableCare) return
        if (careRetry.waitMillis(now) > 0L) return
        if (!forceCheck && (now - PetAdventureEngine.lastCareTimeMillis <= CARE_CHECK_INTERVAL_MS)) return
        PetAdventureEngine.lastCareTimeMillis = now
        val generation = PetAdventureEngine.sessionGeneration
        var success = false
        try {
            bridge.refreshProfile()
            val attrs = PetCareTask.queryPetAttributesAwait(bridge, petId)
            if (attrs == null || !attrs.energy.isFinite() || !attrs.clean.isFinite() ||
                attrs.energy < 0f || attrs.clean < 0f
            ) return
            if (PetAdventureEngine.enableOneClickCare) {
                val oneClickRes = PetCareTask.executeOneClickCareWithAutoBuyAwait(bridge, petId, attrs) { level, msg ->
                    PetAdventureEngine.sendLog(level, msg)
                }
                if (oneClickRes.code == 1 || oneClickRes.code == 2) {
                    success = true
                    return
                } else {
                    PetAdventureEngine.sendLog("[自理] 一键呵护暂停: ${oneClickRes.errorMsg ?: "code=${oneClickRes.code}"}，进入退避")
                    return
                }
            }
            if (attrs.energy < PetAdventureEngine.prefCareEnergyThreshold) {
                val result = PetCareTask.feedWithAutoBuyAwait(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.prefCareEnergyThreshold
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                if (result.first != 0) {
                    PetAdventureEngine.sendLog("[自理] 进食暂停: ${result.second ?: result.first}，进入退避")
                    return
                }
            }
            if (attrs.clean < PetAdventureEngine.prefCareCleanThreshold) {
                val result = PetCareTask.bathWithAutoBuyAwait(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.prefCareCleanThreshold
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                if (result.code != 0) {
                    PetAdventureEngine.sendLog("[自理] 洗护暂停: ${result.errorMsg ?: result.code}，进入退避")
                    return
                }
            }
            success = true
        } finally {
            if (generation == PetAdventureEngine.sessionGeneration) {
                PetAdventureEngine.lastCareTimeMillis = System.currentTimeMillis()
                if (success) careRetry.reset() else careRetry.failed(System.currentTimeMillis())
            }
        }
    }


    private suspend fun checkCoinBagMaintenance(context: Context, bridge: QQPetDirectBridge, petId: String, now: Long) {
        if (!PetAdventureEngine.enableClaimCoinBag || (now - PetAdventureEngine.lastCoinBagTimeMillis <= COIN_BAG_INTERVAL_MS)) return
        try {
            PetSocialTask.executeAutoClaimCoinBags(
                context,
                bridge,
                petId,
                PetAdventureEngine.currentActiveUin,
                false
            ) { level, msg ->
                PetAdventureEngine.sendLog(level, msg)
            }
        } catch (e: RateLimitExceededException) {
            PetAdventureEngine.sendLog("[福袋巡检] 今日好友金币福袋领取已达官方上限，暂停后续巡检")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[福袋巡检] 领福袋异常：${e.message}")
        } finally {
            PetAdventureEngine.lastCoinBagTimeMillis = System.currentTimeMillis()
        }
    }

    private suspend fun checkLikeBackMaintenance(context: Context, bridge: QQPetDirectBridge, now: Long) {
        if (!PetAdventureEngine.enableLikeBack || (now - PetAdventureEngine.lastLikeBackTimeMillis <= LIKE_BACK_INTERVAL_MS)) return
        val friends = PetAccountGateway.loadCachedHireableFriends(context)
        val params = PetSocialTask.LikeBackParams(
            context = context,
            bridge = bridge,
            currentUin = PetAdventureEngine.currentActiveUin,
            cachedFriends = friends,
            isManual = false
        )
        PetSocialTask.executeAutoLikeBack(params) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
        PetAdventureEngine.lastLikeBackTimeMillis = System.currentTimeMillis()
    }

    private suspend fun checkActiveVisitMaintenance(context: Context, bridge: QQPetDirectBridge, now: Long) {
        if (!PetAdventureEngine.enableActiveVisit || (now - PetAdventureEngine.lastActiveVisitTimeMillis <= ACTIVE_VISIT_INTERVAL_MS)) return
        val friends = PetAccountGateway.loadCachedHireableFriends(context)
        PetActiveVisitTask.executeActiveVisitSession(
            context = context,
            bridge = bridge,
            currentUin = PetAdventureEngine.currentActiveUin,
            cachedFriends = friends,
            enableFriends = PetAdventureEngine.prefActiveVisitFriends,
            enableStrangers = PetAdventureEngine.prefActiveVisitStrangers,
            dailyLimit = PetAdventureEngine.prefActiveVisitDailyLimit,
            isManual = false
        ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
        PetAdventureEngine.lastActiveVisitTimeMillis = System.currentTimeMillis()
    }

    private suspend fun checkAutoPkMaintenance(
        context: Context, bridge: QQPetDirectBridge, petId: String, now: Long, story: StoryStatusResult
    ) {
        if (!PetAdventureEngine.enableAutoPk || !story.isIdle ||
            !PetAdventureEngine.lastActiveStoryId.isNullOrEmpty()
        ) return
        val dailyCount = AccountSessionStore.getDailyPkCount(context, PetAdventureEngine.currentActiveUin)
        if (dailyCount >= 10 || (now - PetAdventureEngine.lastPkTimeMillis < PetAdventureEngine.pkCooldownMillis)) return
        PetAdventureEngine.lastPkTimeMillis = now
        val school = PetAdventureEngine.cachedSchoolDetails
        val myTotal = if (school != null && (school.power + school.intel + school.charm > 0L)) {
            school.power + school.intel + school.charm
        } else {
            999999L
        }
        val friends = PetAccountGateway.loadCachedHireableFriends(context)
        val candidates =
            PetPkTask.collectPkCandidates(context, bridge, petId, PetAdventureEngine.currentActiveUin, friends)
        val newCount = PetPkTask.executeSinglePk(
            context,
            bridge,
            petId,
            PetAdventureEngine.currentActiveUin,
            candidates,
            myTotal
        ) { level, msg ->
            PetAdventureEngine.sendLog(level, msg)
        }
        if (newCount in 1..9) {
            val nextCdSec = randomJitter(60L, 179L)
            PetAdventureEngine.pkCooldownMillis = nextCdSec * 1000L
            PetAdventureEngine.sendLog("[自动PK] 本场对决结算完毕，随机冷却休眠 $nextCdSec 秒 (1~3分钟) 后进入下一场...")
        } else if (newCount >= 10) {
            PetAdventureEngine.sendLog("[自动PK] 今日 10 场对决挑战已全部打满，明日将自动重置！")
        }
    }
}

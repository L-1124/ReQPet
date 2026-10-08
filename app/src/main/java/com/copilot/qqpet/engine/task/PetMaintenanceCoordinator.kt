package com.copilot.qqpet.engine.task

import com.copilot.qqpet.engine.utils.randomJitter
import android.content.Context
import com.copilot.qqpet.engine.PetAccountGateway
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.engine.model.StoryStatusResult
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.engine.resilience.RateLimitExceededException
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * 负责后台周期性日常维护任务协调（自理、福袋、回踩、主动串门与自动PK）
 */
object PetMaintenanceCoordinator {

    private const val CARE_CHECK_INTERVAL_MS = 3 * 60 * 1000L
    private const val COIN_BAG_INTERVAL_MS = 5 * 60 * 1000L
    private const val LIKE_BACK_INTERVAL_MS = 6 * 60 * 1000L
    private const val ACTIVE_VISIT_INTERVAL_MS = 8 * 60 * 1000L

    /** 距离下一次喂食、洗澡、福袋、回踩、串门或 PK 到点还有多久。外出不会拉长这个等待。 */
    fun millisUntilNextCheck(
        context: Context,
        now: Long = System.currentTimeMillis(),
        isOuting: Boolean = false
    ): Long {
        val due = ArrayList<Long>(5)
        if (PetAdventureEngine.enableCare) due += waitAfter(
            PetAdventureEngine.lastCareTimeMillis,
            CARE_CHECK_INTERVAL_MS,
            now
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
        }
        try {
            checkCoinBagMaintenance(context, bridge, petId, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[福袋] 维护巡检异常: ${e.message}")
        }
        try {
            checkLikeBackMaintenance(context, bridge, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[回踩] 维护巡检异常: ${e.message}")
        }
        try {
            checkActiveVisitMaintenance(context, bridge, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[串门] 维护巡检异常: ${e.message}")
        }
        try {
            checkAutoPkMaintenance(context, bridge, petId, now, story)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PetAdventureEngine.sendLog("[自动PK] 维护巡检异常: ${e.message}")
        }
    }

    private suspend fun checkCareMaintenance(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        now: Long,
        forceCheck: Boolean = false
    ) {
        if (!PetAdventureEngine.enableCare) return
        if (!forceCheck && (now - PetAdventureEngine.lastCareTimeMillis <= CARE_CHECK_INTERVAL_MS)) return
        bridge.refreshProfile()
        val attrs = PetCareTask.queryPetAttributesAwait(bridge, petId) ?: bridge.getPetAttributes(petId)
        if (attrs != null && (attrs.energy < PetAdventureEngine.prefCareEnergyThreshold || attrs.clean < PetAdventureEngine.prefCareCleanThreshold)) {
            if (attrs.energy < PetAdventureEngine.prefCareEnergyThreshold) {
                PetCareTask.feedWithAutoBuyAwait(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.prefCareEnergyThreshold
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
            }
            if (attrs.clean < PetAdventureEngine.prefCareCleanThreshold) {
                PetCareTask.bathWithAutoBuyAwait(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.prefCareCleanThreshold
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
            }
            delay(randomJitter(720L, 1680L).milliseconds)
            PetAdventureEngine.lastCareTimeMillis = System.currentTimeMillis()
        } else if (attrs != null) {
            PetAdventureEngine.lastCareTimeMillis = System.currentTimeMillis()
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

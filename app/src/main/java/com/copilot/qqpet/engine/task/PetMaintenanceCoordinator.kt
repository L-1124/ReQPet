package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import java.util.concurrent.ThreadLocalRandom

/**
 * 负责后台周期性日常维护任务协调（自理、福袋、回踩、主动串门与自动PK）
 */
object PetMaintenanceCoordinator {

    private const val CARE_CHECK_INTERVAL_MS = 3 * 60 * 1000L
    private const val COIN_BAG_INTERVAL_MS = 5 * 60 * 1000L
    private const val LIKE_BACK_INTERVAL_MS = 6 * 60 * 1000L
    private const val ACTIVE_VISIT_INTERVAL_MS = 8 * 60 * 1000L

    /** 距离下一次喂食、洗澡、福袋、回踩、串门或 PK 到点还有多久。外出不会拉长这个等待。 */
    fun millisUntilNextCheck(context: Context, now: Long = System.currentTimeMillis()): Long {
        val due = ArrayList<Long>(5)
        if (PetAdventureEngine.enableCare) due += waitAfter(PetAdventureEngine.lastCareTimeMillis, CARE_CHECK_INTERVAL_MS, now)
        if (PetAdventureEngine.enableClaimCoinBag) due += waitAfter(PetAdventureEngine.lastCoinBagTimeMillis, COIN_BAG_INTERVAL_MS, now)
        if (PetAdventureEngine.enableLikeBack) due += waitAfter(PetAdventureEngine.lastLikeBackTimeMillis, LIKE_BACK_INTERVAL_MS, now)
        if (PetAdventureEngine.enableActiveVisit) due += waitAfter(PetAdventureEngine.lastActiveVisitTimeMillis, ACTIVE_VISIT_INTERVAL_MS, now)
        if (PetAdventureEngine.enableAutoPk && AccountSessionStore.getDailyPkCount(context, PetAdventureEngine.currentActiveUin) < 10) {
            due += waitAfter(PetAdventureEngine.lastPkTimeMillis, PetAdventureEngine.pkCooldownMillis, now)
        }
        return due.minOrNull() ?: Long.MAX_VALUE
    }

    private fun waitAfter(last: Long, interval: Long, now: Long): Long {
        if (last <= 0L) return 0L
        return (last + interval + 1L - now).coerceAtLeast(0L)
    }

    suspend fun performMaintenance(context: Context, bridge: QQPetDirectBridge, petId: String) {
        val now = System.currentTimeMillis()
        checkCareMaintenance(context, bridge, petId, now)
        checkCoinBagMaintenance(context, bridge, petId, now)
        checkLikeBackMaintenance(context, bridge, now)
        checkActiveVisitMaintenance(context, bridge, now)
        checkAutoPkMaintenance(context, bridge, petId, now)
    }

    private suspend fun checkCareMaintenance(context: Context, bridge: QQPetDirectBridge, petId: String, now: Long) {
        if (!PetAdventureEngine.enableCare || (now - PetAdventureEngine.lastCareTimeMillis <= CARE_CHECK_INTERVAL_MS)) return
        PetAdventureEngine.lastCareTimeMillis = now
        bridge.refreshProfile()
        val attrs = PetCareTask.queryPetAttributesAwait(bridge, petId) ?: bridge.getPetAttributes(petId)
        if (attrs != null && (attrs.energy < PetAdventureEngine.prefCareEnergyThreshold || attrs.clean < PetAdventureEngine.prefCareCleanThreshold)) {
            if (attrs.energy < PetAdventureEngine.prefCareEnergyThreshold) {
                PetCareTask.feedWithAutoBuyAwait(context, bridge, petId, PetAdventureEngine.prefCareEnergyThreshold) { PetAdventureEngine.sendLog(context, it) }
            }
            if (attrs.clean < PetAdventureEngine.prefCareCleanThreshold) {
                PetCareTask.bathWithAutoBuyAwait(context, bridge, petId, PetAdventureEngine.prefCareCleanThreshold) { PetAdventureEngine.sendLog(context, it) }
            }
            delay(1200L)
        }
    }

    private suspend fun checkCoinBagMaintenance(context: Context, bridge: QQPetDirectBridge, petId: String, now: Long) {
        if (!PetAdventureEngine.enableClaimCoinBag || (now - PetAdventureEngine.lastCoinBagTimeMillis <= COIN_BAG_INTERVAL_MS)) return
        PetAdventureEngine.lastCoinBagTimeMillis = now
        PetSocialTask.executeAutoClaimCoinBags(context, bridge, petId, PetAdventureEngine.currentActiveUin, false) {
            PetAdventureEngine.sendLog(context, it)
        }
    }

    private suspend fun checkLikeBackMaintenance(context: Context, bridge: QQPetDirectBridge, now: Long) {
        if (!PetAdventureEngine.enableLikeBack || (now - PetAdventureEngine.lastLikeBackTimeMillis <= LIKE_BACK_INTERVAL_MS)) return
        PetAdventureEngine.lastLikeBackTimeMillis = now
        val friends = PetAdventureEngine.loadCachedHireableFriends(context)
        val params = PetSocialTask.LikeBackParams(
            context = context,
            bridge = bridge,
            currentUin = PetAdventureEngine.currentActiveUin,
            cachedFriends = friends,
            isManual = false
        )
        PetSocialTask.executeAutoLikeBack(params) { PetAdventureEngine.sendLog(context, it) }
    }

    private suspend fun checkActiveVisitMaintenance(context: Context, bridge: QQPetDirectBridge, now: Long) {
        if (!PetAdventureEngine.enableActiveVisit || (now - PetAdventureEngine.lastActiveVisitTimeMillis <= ACTIVE_VISIT_INTERVAL_MS)) return
        PetAdventureEngine.lastActiveVisitTimeMillis = now
        val friends = PetAdventureEngine.loadCachedHireableFriends(context)
        PetActiveVisitTask.executeActiveVisitSession(
            context = context,
            bridge = bridge,
            currentUin = PetAdventureEngine.currentActiveUin,
            cachedFriends = friends,
            enableFriends = PetAdventureEngine.prefActiveVisitFriends,
            enableStrangers = PetAdventureEngine.prefActiveVisitStrangers,
            dailyLimit = PetAdventureEngine.prefActiveVisitDailyLimit,
            isManual = false
        ) { PetAdventureEngine.sendLog(context, it) }
    }

    private suspend fun checkAutoPkMaintenance(context: Context, bridge: QQPetDirectBridge, petId: String, now: Long) {
        if (!PetAdventureEngine.enableAutoPk) return
        val dailyCount = AccountSessionStore.getDailyPkCount(context, PetAdventureEngine.currentActiveUin)
        if (dailyCount >= 10 || (now - PetAdventureEngine.lastPkTimeMillis < PetAdventureEngine.pkCooldownMillis)) return
        PetAdventureEngine.lastPkTimeMillis = now

        val school = PetAdventureEngine.cachedSchoolDetails
        val myTotal = if (school != null && (school.power + school.intel + school.charm > 0L)) {
            school.power + school.intel + school.charm
        } else {
            999999L
        }
        val friends = PetAdventureEngine.loadCachedHireableFriends(context)
        val candidates = PetPkTask.collectPkCandidates(context, bridge, petId, PetAdventureEngine.currentActiveUin, friends)
        val newCount = PetPkTask.executeSinglePk(context, bridge, petId, PetAdventureEngine.currentActiveUin, candidates, myTotal) {
            PetAdventureEngine.sendLog(context, it)
        }
        if (newCount in 1..9) {
            val nextCdSec = ThreadLocalRandom.current().nextLong(60L, 180L)
            PetAdventureEngine.pkCooldownMillis = nextCdSec * 1000L
            PetAdventureEngine.sendLog(context, "⏱️ [自动PK] 本场对决结算完毕，随机冷却休眠 ${nextCdSec} 秒 (1~3分钟) 后进入下一场...")
        } else if (newCount >= 10) {
            PetAdventureEngine.sendLog(context, "🎉 [自动PK] 今日 10 场对决挑战已全部打满，明日将自动重置！")
        }
    }
}

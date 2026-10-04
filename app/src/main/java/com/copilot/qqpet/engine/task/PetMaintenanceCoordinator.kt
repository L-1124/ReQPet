package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import java.util.concurrent.ThreadLocalRandom

/**
 * 负责后台周期性日常维护任务协调（自理、好友照料、福袋、回踩、主动串门与自动PK）
 */
object PetMaintenanceCoordinator {

    private const val CARE_CHECK_INTERVAL_MS = 3 * 60 * 1000L

    suspend fun performMaintenance(context: Context, bridge: QQPetDirectBridge, petId: String) {
        val now = System.currentTimeMillis()
        checkCareMaintenance(context, bridge, petId, now)
        checkFriendCareMaintenance(context, bridge, petId, now)
        checkCoinBagMaintenance(context, bridge, petId, now)
        checkLikeBackMaintenance(context, bridge, now)
        checkActiveVisitMaintenance(context, bridge, now)
        checkAutoPkMaintenance(context, bridge, petId, now)
    }

    private suspend fun checkFriendCareMaintenance(context: Context, bridge: QQPetDirectBridge, petId: String, now: Long) {
        if (!PetAdventureEngine.enableFriendCare || (now - PetAdventureEngine.lastFriendCareTimeMillis <= 10 * 60 * 1000L)) return
        PetAdventureEngine.lastFriendCareTimeMillis = now
        val params = PetFriendCareTask.FriendCareParams(
            context = context,
            bridge = bridge,
            ownPetId = petId,
            energyThreshold = PetAdventureEngine.prefFriendCareEnergyThreshold,
            cleanThreshold = PetAdventureEngine.prefFriendCareCleanThreshold,
            isManual = false
        )
        PetFriendCareTask.executeAutoFriendCare(params) { PetAdventureEngine.sendLog(context, it) }
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
                PetCareTask.bathWithAutoBuyAwait(context, bridge, petId) { PetAdventureEngine.sendLog(context, it) }
            }
            delay(1200L)
        }
    }

    private suspend fun checkCoinBagMaintenance(context: Context, bridge: QQPetDirectBridge, petId: String, now: Long) {
        if (!PetAdventureEngine.enableClaimCoinBag || (now - PetAdventureEngine.lastCoinBagTimeMillis <= 5 * 60 * 1000L)) return
        PetAdventureEngine.lastCoinBagTimeMillis = now
        PetSocialTask.executeAutoClaimCoinBags(context, bridge, petId, PetAdventureEngine.currentActiveUin, false) {
            PetAdventureEngine.sendLog(context, it)
        }
    }

    private suspend fun checkLikeBackMaintenance(context: Context, bridge: QQPetDirectBridge, now: Long) {
        if (!PetAdventureEngine.enableLikeBack || (now - PetAdventureEngine.lastLikeBackTimeMillis <= 6 * 60 * 1000L)) return
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
        if (!PetAdventureEngine.enableActiveVisit || (now - PetAdventureEngine.lastActiveVisitTimeMillis <= 8 * 60 * 1000L)) return
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

package io.github.reqpet.engine.task

import io.github.reqpet.engine.TaskLogger
import io.github.reqpet.engine.utils.randomJitter
import android.content.Context
import io.github.reqpet.engine.PetAccountGateway
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.engine.state.AccountSessionStore
import io.github.reqpet.engine.state.RosterStore
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 负责每日 10 场 PK 自动对决、战力数值裁决、黑名单免战与对手筛选
 */
object PetPkTask {

    data class CandidateItem(
        val uin: Long,
        val petId: String,
        val userNick: String,
        val petNick: String,
        var power: Long = 0L,
        var intel: Long = 0L,
        var charm: Long = 0L,
        val isFriend: Boolean = true
    ) {
        val totalAttr: Long get() = power + intel + charm
    }

    suspend fun collectPkCandidates(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        cachedFriends: List<QQPetDirectBridge.HireableFriend>
    ): List<CandidateItem> {
        val list = mutableListOf<CandidateItem>()
        val seenUins = mutableSetOf<Long>()
        val ownUin = currentUin.toLongOrNull() ?: 0L
        val friends =
            if (cachedFriends.isNotEmpty()) cachedFriends else PetAccountGateway.loadCachedHireableFriends(context)

        for (f in friends) {
            if (f.uin <= 0L || f.uin == ownUin || seenUins.contains(f.uin) || f.petId.isBlank() || f.petId == ownPetId) continue
            seenUins.add(f.uin)
            list.add(
                CandidateItem(
                    f.uin,
                    f.petId,
                    f.friendNick.ifEmpty { "好友_${f.uin}" },
                    f.petNick.ifEmpty { "小宠" },
                    f.power,
                    f.intel,
                    f.charm,
                    true
                )
            )
        }
        try {
            val likeRes = kotlinx.coroutines.withTimeoutOrNull(8000L) {
                suspendCancellableCoroutine<List<QQPetDirectBridge.LikeMember>> { cont ->
                    bridge.fetchLikeList("") { code, members, _, _, _, _ ->
                        if (cont.isActive) cont.resume(if (code == 0) members else emptyList())
                    }
                }
            } ?: emptyList()
            for (m in likeRes) {
                if (m.uin <= 0L || m.uin == ownUin || seenUins.contains(m.uin) || m.petId.isBlank()) continue
                seenUins.add(m.uin)
                list.add(CandidateItem(m.uin, m.petId, m.nick.ifEmpty { "访客_${m.uin}" }, "小宠", 0L, 0L, 0L, false))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
        }
        return list
    }

    suspend fun queryFriendPkStatusAwait(
        bridge: QQPetDirectBridge,
        targetUin: Long,
        targetPetId: String,
        ownPetId: String
    ): QQPetDirectBridge.PkStatusInfo? =
        try {
            kotlinx.coroutines.withTimeoutOrNull(6000L) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryFriendPkStatus(targetUin, targetPetId, ownPetId) { code, info, _ ->
                        if (cont.isActive) cont.resume(if (code == 0) info else null)
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }

    suspend fun settlePkBattleAwait(
        bridge: QQPetDirectBridge,
        storyId: String,
        ownPetId: String
    ): QQPetDirectBridge.PkSettleResult? =
        try {
            kotlinx.coroutines.withTimeoutOrNull(6000L) {
                suspendCancellableCoroutine { cont ->
                    bridge.settlePkBattle(storyId, ownPetId) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }

    private suspend fun checkAndPrepareOpponent(
        bridge: QQPetDirectBridge,
        ownPetId: String,
        cand: CandidateItem,
        onLog: TaskLogger
    ): Boolean {
        val pkStatus = queryFriendPkStatusAwait(bridge, cand.uin, cand.petId, ownPetId) ?: return true
        if (pkStatus.rawStatus == 300 && !pkStatus.ongoingStoryId.isNullOrEmpty()) {
            onLog("[自动PK] 发现历史未结算对决 (storyId=${pkStatus.ongoingStoryId})，正在领奖...")
            val sRes = settlePkBattleAwait(bridge, pkStatus.ongoingStoryId, ownPetId)
            if (sRes?.code == 0) {
                onLog("[自动PK] 历史对决结算完成！斩获金币: +${sRes.goldEarned}")
            }
            delay(randomJitter(720L, 1680L))
        }
        if (!pkStatus.canPk && pkStatus.rawStatus != 100 && pkStatus.rawStatus != 300) {
            onLog("[自动PK] 对手「${cand.userNick}」当前不可对决 (rawStatus=${pkStatus.rawStatus})，寻找下一位...")
            return false
        }
        return true
    }

    private suspend fun challengeOpponent(
        bridge: QQPetDirectBridge,
        ownPetId: String,
        cand: CandidateItem,
        onLog: TaskLogger
    ): QQPetDirectBridge.PkSettleResult? {
        val battleRes = kotlinx.coroutines.withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<QQPetDirectBridge.PkBattleResult> { cont ->
                bridge.startPkBattle(cand.uin, cand.petId, ownPetId) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }
        }
        if (battleRes == null) {
            onLog.warn("[自动PK] 对决发包超时 (8s)，跳过对手「${cand.userNick}」")
            return null
        }
        if (battleRes.code != 0 || battleRes.storyId.isNullOrEmpty()) {
            onLog.warn("[自动PK] 对决回包: code=${battleRes.code}, err=${battleRes.errorMsg ?: "暂不可战"}，跳过")
            return null
        }
        val outcomeStr = if (battleRes.isWin) "战斗大捷！" else "战斗惜败"
        onLog("[对决进行中] 我方「${battleRes.myNick}」战力 ${battleRes.myPower} VS 对方「${battleRes.oppNick}」战力 ${battleRes.oppPower} -> 判定: $outcomeStr")
        val waitSec = if (battleRes.leftDurationSec in 1..25) battleRes.leftDurationSec else 5L
        delay(waitSec * 1000L + 500L)
        val settleRes = kotlinx.coroutines.withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<QQPetDirectBridge.PkSettleResult> { cont ->
                bridge.settlePkBattle(battleRes.storyId, ownPetId) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }
        }
        if (settleRes == null) {
            onLog.warn("[自动PK] 对决结算回包超时 (8s)")
        }
        return settleRes
    }

    suspend fun executeSinglePk(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        candidates: List<CandidateItem>,
        myTotal: Long,
        specificTargetUin: Long = 0L,
        onLog: TaskLogger
    ): Int {
        val currentCount = AccountSessionStore.getDailyPkCount(context, currentUin)
        if (currentCount >= 10) return currentCount
        val pool = if (specificTargetUin > 0L) candidates.filter { it.uin == specificTargetUin } else candidates
        val blacklist = RosterStore.loadSavedPkBlacklistUins(context, currentUin)

        for (cand in pool) {
            if (!io.github.reqpet.engine.utils.PetPureCalculations.canChallengePkOpponent(
                    oppTotal = cand.totalAttr,
                    myTotal = myTotal,
                    oppUin = cand.uin,
                    blacklist = blacklist,
                    canPk = true
                )
            ) continue
            if (!checkAndPrepareOpponent(bridge, ownPetId, cand, onLog)) continue
            onLog("[对手锁定] 选中碾压对手: 「${cand.userNick}」的小宠「${cand.petNick}」(对手三维: ${cand.totalAttr} <= 我方: $myTotal)")
            val settle = challengeOpponent(bridge, ownPetId, cand, onLog)
            if (settle != null) {
                val newCount = AccountSessionStore.incrementDailyPkCount(context, currentUin)
                val goldStr = if (settle.goldEarned in 1..1_000_000L) "，斩获金币: +${settle.goldEarned}" else ""
                onLog("[PK结算] 第 $newCount/10 场对决完成: ${settle.title ?: "大捷"}$goldStr！")
                bridge.refreshProfile()
                return newCount
            }
        }
        return currentCount
    }
}

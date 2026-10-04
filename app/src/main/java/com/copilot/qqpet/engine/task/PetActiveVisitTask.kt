package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.ActiveVisitHelper
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import java.util.concurrent.ThreadLocalRandom

/**
 * 负责小宠主动串门送心、陌生人蓄水池巡检与每日点赞配额调度
 */
object PetActiveVisitTask {

    suspend fun executeActiveVisitSession(
        context: Context,
        bridge: QQPetDirectBridge,
        currentUin: String,
        cachedFriends: List<QQPetDirectBridge.HireableFriend>,
        enableFriends: Boolean,
        enableStrangers: Boolean,
        dailyLimit: Int,
        isManual: Boolean,
        onLog: (String) -> Unit
    ): Int {
        val ownUin = currentUin.toLongOrNull() ?: 0L
        val friends = if (cachedFriends.isNotEmpty()) cachedFriends else AccountSessionStore.loadCachedHireableFriends(context, currentUin)
        val friendUins = friends.map { it.uin }.filter { it > 0L }
        val friendUinSet = friendUins.toSet()

        enrichStrangerPoolFromVisitors(context, bridge, currentUin, ownUin, friendUinSet)
        val strangerPool = AccountSessionStore.loadStrangerUinPool(context, currentUin).toSet()

        val todayLiked = AccountSessionStore.getTodayLikedUins(context, currentUin)
        val maxLimit = if (dailyLimit > 0) dailyLimit else 20
        val targets = ActiveVisitHelper.resolveActiveVisitTargets(
            friendUins = friendUins,
            strangerPool = strangerPool,
            enableFriends = enableFriends,
            enableStrangers = enableStrangers,
            todayLikedUins = todayLiked,
            maxDailyLimit = maxLimit
        )

        if (targets.isEmpty()) {
            if (isManual) {
                val reason = if (!enableFriends && !enableStrangers) "未开启好友或陌生人串门" else "今日配额已满或已全部串门"
                onLog("ℹ️ [主动串门] 暂无待串门目标 ($reason)")
            }
            return 0
        }

        return visitTargets(context, bridge, currentUin, targets, maxLimit, onLog)
    }

    private suspend fun enrichStrangerPoolFromVisitors(
        context: Context,
        bridge: QQPetDirectBridge,
        currentUin: String,
        ownUin: Long,
        friendUinSet: Set<Long>
    ) {
        try {
            val (vCode, vMembers) = PetSocialTask.fetchLikeListAwait(bridge)
            if (vCode == 0 && vMembers.isNotEmpty()) {
                val newStrangers = ActiveVisitHelper.extractStrangersFromVisitors(vMembers.map { it.uin }, ownUin, friendUinSet)
                if (newStrangers.isNotEmpty()) {
                    AccountSessionStore.recordStrangersToPool(context, currentUin, newStrangers)
                }
            }
        } catch (_: Throwable) {}
    }

    private suspend fun visitTargets(
        context: Context,
        bridge: QQPetDirectBridge,
        currentUin: String,
        targets: List<ActiveVisitHelper.VisitTarget>,
        maxLimit: Int,
        onLog: (String) -> Unit
    ): Int {
        val fCount = targets.count { it.isFriend }
        val sCount = targets.count { !it.isFriend }
        onLog("🚶 [主动串门] 开始串门踩踩: 好友 $fCount 位，随机陌生人 $sCount 位 (上限: $maxLimit)")

        var successCount = 0
        for (t in targets) {
            val label = if (t.isFriend) "好友" else "随机陌生小宠"
            val (code, _) = PetSocialTask.sendLikeAwait(bridge, t.uin)
            if (code == 0 || code == 136202) {
                AccountSessionStore.markFriendLikedToday(context, currentUin, t.uin)
                if (code == 0) {
                    successCount++
                    onLog("✅ [主动串门] 成功串门踩踩$label (${t.uin})！")
                    delay(ThreadLocalRandom.current().nextLong(3000L, 5000L))
                } else {
                    delay(1500L)
                }
            } else {
                delay(1800L)
            }
        }
        if (successCount > 0) {
            onLog("🎉 [主动串门] 串门完成！共成功送心 $successCount 位小伙伴 (好友+陌生人)")
        }
        return successCount
    }
}

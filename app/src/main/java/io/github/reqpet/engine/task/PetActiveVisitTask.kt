package io.github.reqpet.engine.task

import io.github.reqpet.engine.EngineLog
import android.content.Context
import io.github.reqpet.engine.ActiveVisitHelper
import io.github.reqpet.engine.TaskLogger
import io.github.reqpet.engine.state.AccountSessionStore
import io.github.reqpet.engine.utils.randomJitter
import io.github.reqpet.engine.state.RosterStore
import io.github.reqpet.engine.resilience.RateLimitExceededException
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay

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
        onLog: TaskLogger
    ): Int {
        val ownUin = currentUin.toLongOrNull() ?: 0L
        val friends = if (cachedFriends.isNotEmpty()) cachedFriends else RosterStore.loadCachedHireableFriends(
            context,
            currentUin
        )
        val friendUins = friends.map { it.uin }.filter { it > 0L }
        val friendUinSet = friendUins.toSet()

        enrichStrangerPoolFromVisitors(context, bridge, currentUin, ownUin, friendUinSet)
        val strangerPool = RosterStore.loadStrangerUinPool(context, currentUin).toSet()

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
                val reason =
                    if (!enableFriends && !enableStrangers) "未开启好友或陌生人串门" else "今日配额已满或已全部串门"
                onLog("[主动串门] 暂无待串门目标 ($reason)")
            }
            return 0
        }

        try {
            return visitTargets(context, bridge, currentUin, targets, maxLimit, onLog)
        } catch (e: RateLimitExceededException) {
            // 速率限制已处理，返回成功计数
            onLog.warn("[主动串门] 因达到官方限额终止会话")
            return 0
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EngineLog.w("PetActiveVisitTask", "串门会话异常：${e.message}")
            return 0
        }
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
                val newStrangers =
                    ActiveVisitHelper.extractStrangersFromVisitors(vMembers.map { it.uin }, ownUin, friendUinSet)
                if (newStrangers.isNotEmpty()) {
                    RosterStore.recordStrangersToPool(context, currentUin, newStrangers)
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private suspend fun visitTargets(
        context: Context,
        bridge: QQPetDirectBridge,
        currentUin: String,
        targets: List<ActiveVisitHelper.VisitTarget>,
        maxLimit: Int,
        onLog: TaskLogger
    ): Int {
        val fCount = targets.count { it.isFriend }
        val sCount = targets.count { !it.isFriend }
        onLog("[主动串门] 开始串门踩踩：好友 $fCount 位，随机陌生人 $sCount 位 (上限：$maxLimit)")

        var successCount = 0
        for (t in targets) {
            try {
                val label = if (t.isFriend) "好友" else "随机陌生小宠"
                val (code, _) = PetSocialTask.sendLikeAwait(bridge, t.uin)

                when (code) {
                    0 -> {
                        AccountSessionStore.markFriendLikedToday(context, currentUin, t.uin)
                        successCount++
                        onLog("[主动串门] 成功串门踩踩$label (${t.uin})！")
                        delay(randomJitter(3000L, 5000L))
                    }

                    136202 -> {
                        // 今日已送过心，立即跳过不延迟
                        AccountSessionStore.markFriendLikedToday(context, currentUin, t.uin)
                        onLog("[主动串门] $label (${t.uin}) 今日已送过心，跳过")
                        continue
                    }

                    else -> {
                        onLog.warn("[主动串门] $label (${t.uin}) 发送失败 (code=$code)")
                        delay(randomJitter(1080L, 2520L))
                    }
                }
            } catch (e: RateLimitExceededException) {
                // 检测到速率限制，立即终止整个会话
                onLog.warn("[主动串门] 检测到每日点赞/串门次数已达官方上限，立即终止会话")
                throw e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EngineLog.w("PetActiveVisitTask", "串门异常：${e.message}")
                // 异常也继续下一个目标
            }
        }

        if (successCount > 0) {
            onLog("[主动串门] 串门完成！共成功送心 $successCount 位小伙伴 (好友 + 陌生人)")
        }
        return successCount
    }
}

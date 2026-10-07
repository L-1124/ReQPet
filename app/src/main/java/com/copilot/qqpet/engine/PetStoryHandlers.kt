package com.copilot.qqpet.engine

import android.content.Context
import com.copilot.qqpet.engine.model.StoryStatusResult
import com.copilot.qqpet.engine.task.PetHiredRecallTask
import com.copilot.qqpet.engine.task.PetSocialTask
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay

/**
 * 主循环故事处理器：外出中的雇佣召回监控与到期收益结算
 *
 * 从 PetAdventureEngine 拆出，专注"正在外出的那只小宠"的两种生命周期事件
 */
internal object PetStoryHandlers {

    /** 处理进行中的外出：更新状态文本并评估雇佣召回；返回应休眠毫秒或 null */
    suspend fun handleOngoingStory(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        story: StoryStatusResult
    ): Long? {
        val rem = story.remaining ?: return null
        val total = story.total ?: 0L
        val storyId = story.storyId ?: return null
        if (story.code != 0 || rem <= 0) return null

        PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + rem * 1000L
        PetAdventureEngine.currentTaskTypeName = when {
            storyId.startsWith("6100") -> "进阶修习中"; storyId.startsWith("6400") -> "小镇打工中"; else -> "森林探险中"
        }
        PetAdventureEngine.currentStatusText =
            "${PetAdventureEngine.currentTaskTypeName} · 剩余 ${PetPureCalculations.formatDuration(rem)}"

        if (PetAdventureEngine.lastReportedOngoingStoryId != storyId) {
            PetAdventureEngine.lastReportedOngoingStoryId = storyId
            PetAdventureEngine.sendLog(
                context,
                "⏳ [任务进行中] 小宠正在 ${PetAdventureEngine.currentTaskTypeName} (剩余 ${
                    PetPureCalculations.formatDuration(
                        rem
                    )
                })，到期后将自动结算"
            )
        }

        val selfUin = PetAdventureEngine.currentActiveUin.toLongOrNull() ?: 0L
        val decision = PetHiredRecallTask.evaluateHiredMonitor(
            bridge, petId,
            PetHiredRecallTask.RecallCheckParam(
                storyId,
                rem,
                total,
                selfUin,
                PetAdventureEngine.prefHiredRecallProgress
            )
        ) { PetAdventureEngine.sendLog(context, it) }
        if (decision.isHired) {
            if (decision.hasRecalled) {
                PetAdventureEngine.lastActiveStoryId = null
                PetAdventureEngine.lastReportedOngoingStoryId = null
                PetAdventureEngine.currentTaskEndTimeMillis = 0L
            }
            if (decision.settled) {
                PetSocialTask.claimOnceAfterSettle(
                    context, bridge, petId, PetAdventureEngine.currentActiveUin, PetAdventureEngine.enableClaimCoinBag
                ) { PetAdventureEngine.sendLog(context, it) }
            }
            return decision.nextSleepMillis
        }
        return null
    }

    /** 处理到期结算：任务结束后自动领取收益 */
    suspend fun handleStorySettlement(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        story: StoryStatusResult
    ) {
        val pendingId = PetAdventureEngine.lastActiveStoryId ?: story.storyId
        if ((story.remaining ?: 0L) <= 0L && PetAdventureEngine.enableSettle && !pendingId.isNullOrEmpty()) {
            PetAdventureEngine.sendLog(context, "🎁 [结算] 自动发起收益结算 (StoryID: $pendingId)...")
            val (code, _) = PetHiredRecallTask.settleStoryAwait(bridge, pendingId, petId)
            if (code == 0) {
                PetAdventureEngine.sendLog(context, "✅ [结算] 收益结算成功！金币与经验已入账")
                PetSocialTask.claimOnceAfterSettle(
                    context, bridge, petId, PetAdventureEngine.currentActiveUin, PetAdventureEngine.enableClaimCoinBag
                ) { PetAdventureEngine.sendLog(context, it) }
            }
            PetAdventureEngine.lastActiveStoryId = null
            PetAdventureEngine.lastReportedOngoingStoryId = null
            PetAdventureEngine.currentTaskEndTimeMillis = 0L
            delay(1500L)
        }
    }
}

package io.github.reqpet.engine

import android.content.Context
import io.github.reqpet.engine.model.StoryStatusResult
import io.github.reqpet.engine.task.PetHiredRecallTask
import io.github.reqpet.engine.task.PetSocialTask
import io.github.reqpet.engine.utils.PetPureCalculations
import io.github.reqpet.engine.utils.randomJitter
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * 主循环故事处理器：外出中的雇佣召回监控与到期收益结算
 *
 * 从 PetAdventureEngine 拆出，专注"正在外出的那只小宠"的两种生命周期事件
 */
internal object PetStoryHandlers {

    suspend fun handleOngoingStory(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        story: StoryStatusResult
    ): PetHiredRecallTask.HiredMonitorDecision? {
        if (!story.isOngoing) return null
        val rem = story.remaining ?: return null
        val total = story.total ?: 0L
        val storyId = story.storyId ?: return null

        PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + rem * 1000L
        PetAdventureEngine.currentTaskTypeName = when {
            storyId.startsWith("6100") -> "进阶修习中"; storyId.startsWith("6400") -> "小镇打工中"; else -> "森林探险中"
        }
        PetAdventureEngine.currentStatusText =
            "${PetAdventureEngine.currentTaskTypeName} · 剩余 ${PetPureCalculations.formatDuration(rem)}"

        if (PetAdventureEngine.lastActiveStoryId == null) {
            PetAdventureEngine.lastActiveStoryId = storyId
        }

        if (PetAdventureEngine.lastReportedOngoingStoryId != storyId) {
            PetAdventureEngine.lastReportedOngoingStoryId = storyId
            PetAdventureEngine.sendLog(
                "[任务进行中] 小宠正在 ${PetAdventureEngine.currentTaskTypeName} (剩余 ${
                    PetPureCalculations.formatDuration(
                        rem
                    )
                })，到期后将自动结算"
            )
        }

        val selfUin = PetAdventureEngine.currentActiveUin.toLongOrNull() ?: 0L
        var decision = PetHiredRecallTask.evaluateHiredMonitor(
            bridge, petId,
            PetHiredRecallTask.RecallCheckParam(
                storyId,
                rem,
                total,
                selfUin,
                PetAdventureEngine.prefHiredRecallProgress
            )
        ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
        RuntimeDiagnostics.event(
            "recall_decision", "transaction" to PetAdventureEngine.currentTransactionId,
            "cycle" to PetAdventureEngine.currentCycleId, "story_id" to RuntimeDiagnostics.id(storyId),
            "hired" to decision.isHired, "recalled" to decision.hasRecalled,
            "threshold_percent" to PetAdventureEngine.prefHiredRecallProgress,
            "remaining_s" to rem, "total_s" to total, "next_sleep_ms" to decision.nextSleepMillis
        )
        if (decision.isHired) {
            if (decision.hasRecalled) {
                PetAdventureEngine.markStoryRecalled(storyId)
                RuntimeDiagnostics.event(
                    "settlement_start", "transaction" to PetAdventureEngine.currentTransactionId,
                    "cycle" to PetAdventureEngine.currentCycleId, "source" to "recall",
                    "story_id" to RuntimeDiagnostics.id(storyId)
                )
                delay(randomJitter(480L, 1120L))
                val (code, _) = PetHiredRecallTask.settleStoryAwait(bridge, storyId, petId)
                RuntimeDiagnostics.event(
                    "settlement_result", "transaction" to PetAdventureEngine.currentTransactionId,
                    "cycle" to PetAdventureEngine.currentCycleId, "source" to "recall",
                    "story_id" to RuntimeDiagnostics.id(storyId), "code" to code
                )
                decision = decision.copy(settled = code == 0)
                if (decision.settled) PetAdventureEngine.clearSettledStory(storyId)
            }
            if (decision.settled) {
                PetSocialTask.claimOnceAfterSettle(
                    context, bridge, petId, PetAdventureEngine.currentActiveUin, PetAdventureEngine.enableClaimCoinBag
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
            }
            return decision
        }
        return null
    }

    internal fun resolveSettlementStoryId(
        story: StoryStatusResult, now: Long = System.currentTimeMillis()
    ): String? {
        if (!PetAdventureEngine.enableSettle || story.code != 0 ||
            PetAdventureEngine.currentTaskEndTimeMillis > now
        ) return null
        return when {
            story.isReadyToSettle -> story.storyId
            story.isIdle -> PetAdventureEngine.pendingSettlementStoryId
                ?: PetAdventureEngine.lastActiveStoryId?.takeIf {
                    // Expiry may clear running fields while the known story still needs settlement.
                    PetAdventureEngine.currentTaskEndTimeMillis > 0L &&
                            (story.storyId.isNullOrBlank() || story.storyId == it)
                }

            else -> null
        }
    }

    /** 处理到期结算：任务结束后自动领取收益 */
    suspend fun handleStorySettlement(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        story: StoryStatusResult,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!PetAdventureEngine.enableSettle) {
            if (story.isIdle && PetAdventureEngine.currentTaskEndTimeMillis in 1..now) {
                PetAdventureEngine.lastActiveStoryId?.let { PetAdventureEngine.clearSettledStory(it) }
            }
            return false
        }
        val pendingId = resolveSettlementStoryId(story, now) ?: return false
        val source = when {
            !story.isIdle -> "server_ready"
            PetAdventureEngine.pendingSettlementStoryId != null -> "pending_retry"
            else -> "tracked_expiry"
        }

        PetAdventureEngine.lastActiveStoryId = pendingId
        PetAdventureEngine.pendingSettlementStoryId = pendingId
        PetAdventureEngine.sendLog("[结算] 自动发起收益结算 (StoryID: $pendingId)...")
        RuntimeDiagnostics.event(
            "settlement_start", "transaction" to PetAdventureEngine.currentTransactionId,
            "cycle" to PetAdventureEngine.currentCycleId, "story_id" to RuntimeDiagnostics.id(pendingId),
            "source" to source
        )
        val (code, _) = PetHiredRecallTask.settleStoryAwait(bridge, pendingId, petId)
        RuntimeDiagnostics.event(
            "settlement_result", "transaction" to PetAdventureEngine.currentTransactionId,
            "cycle" to PetAdventureEngine.currentCycleId, "source" to "automatic",
            "story_id" to RuntimeDiagnostics.id(pendingId), "code" to code
        )
        if (code == 0) {
            PetAdventureEngine.clearSettledStory(pendingId)
            PetAdventureEngine.sendLog("[结算] 收益结算成功！金币与经验已入账")
            PetSocialTask.claimOnceAfterSettle(
                context, bridge, petId, PetAdventureEngine.currentActiveUin, PetAdventureEngine.enableClaimCoinBag
            ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
            delay(1500.milliseconds)
            return true
        } else if (story.isIdle && code != 135004) {
            PetAdventureEngine.clearSettledStory(pendingId)
            PetAdventureEngine.sendLog(
                EngineLog.Level.INFO,
                "[结算] 任务已在手Q结清或已失效 (code=$code)，解除待结算标识"
            )
            return false
        } else {
            // 未确认收益入账前保留待结算标识。
            PetAdventureEngine.lastActiveStoryId = pendingId
            PetAdventureEngine.sendLog(
                EngineLog.Level.WARN,
                "[结算] 收益结算未确认成功 (code=$code)，保留 StoryID ($pendingId) 等待下一轮对账重试"
            )
            return false
        }
    }
}

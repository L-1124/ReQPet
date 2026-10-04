package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.StealthScheduler
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责小宠被好友雇佣打工状态的监控与达到 12%/42%/72% 阈值时的提前抢跑召回
 */
object PetHiredRecallTask {

    private const val NETWORK_TIMEOUT_MS = 8000L

    suspend fun queryProcessStoryInfoAwait(
        bridge: QQPetDirectBridge,
        storyId: String,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.ProcessStoryFatigueResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryProcessStoryInfo(storyId, petId) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.ProcessStoryFatigueResult(-99, false, null, 0, "超时")
        } catch (t: Throwable) {
            QQPetDirectBridge.ProcessStoryFatigueResult(-99, false, null, 0, t.message)
        }

    suspend fun recallStoryAwait(
        bridge: QQPetDirectBridge,
        storyId: String,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.recallStory(storyId, petId) { code, _, errorMsg ->
                        if (cont.isActive) cont.resume(Pair(code, errorMsg))
                    }
                }
            } ?: Pair(-99, "超时")
        } catch (t: Throwable) {
            Pair(-99, t.message)
        }

    suspend fun settleStoryAwait(
        bridge: QQPetDirectBridge,
        storyId: String,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, ByteArray?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.settleStory(storyId, petId) { code, data ->
                        if (cont.isActive) cont.resume(Pair(code, data))
                    }
                }
            } ?: Pair(-99, null)
        } catch (_: Throwable) {
            Pair(-99, null)
        }

    data class RecallCheckParam(
        val currentStoryId: String,
        val remainingSec: Long,
        val totalSec: Long,
        val selfDispatchedStoryId: String?,
        val targetThresh: Int
    )

    data class HiredMonitorDecision(
        val isHired: Boolean,
        val hasRecalled: Boolean,
        val nextSleepMillis: Long
    )

    suspend fun evaluateHiredMonitor(
        bridge: QQPetDirectBridge,
        petId: String,
        param: RecallCheckParam,
        onLog: (String) -> Unit
    ): HiredMonitorDecision {
        if (param.targetThresh <= 0 || !param.currentStoryId.startsWith("6400")) {
            return HiredMonitorDecision(isHired = false, hasRecalled = false, nextSleepMillis = 0L)
        }
        val effectiveTotal = PetPureCalculations.resolveEffectiveTotalSec(param.totalSec, param.remainingSec)
        val curProgress = PetPureCalculations.calculateHiredProgress(effectiveTotal, param.remainingSec)
        val processInfo = queryProcessStoryInfoAwait(bridge, param.currentStoryId, petId)
        val isHired = PetPureCalculations.isTrueHiredWork(
            isHiredFlag = (processInfo.code == 0 && processInfo.isHired),
            currentStoryId = param.currentStoryId,
            selfDispatchedStoryId = param.selfDispatchedStoryId,
            rewardTip = processInfo.tipText,
            totalSec = effectiveTotal
        )
        if (!isHired) {
            return HiredMonitorDecision(isHired = false, hasRecalled = false, nextSleepMillis = 0L)
        }

        val progressInt = curProgress.toInt()
        val mins = param.remainingSec / 60
        val secs = param.remainingSec % 60
        onLog("💼 [被雇佣监控] 小宠正处于好友雇佣打工中，当前进度: ${progressInt}% (剩余 ${mins}分${secs}秒)，设定召回阈值: ${param.targetThresh}%")

        if (!PetPureCalculations.shouldTriggerHiredRecall(curProgress, param.targetThresh)) {
            val neededSec = PetPureCalculations.calculateHiredRemainingToTarget(effectiveTotal, param.remainingSec, param.targetThresh)
            val sleepMs = StealthScheduler.calculateHiredMonitorSleepMillis(neededSec, hasReachedTarget = false)
            onLog("⏳ [召回守候] 距离目标 ${param.targetThresh}% 约剩 ${neededSec}秒，调度休眠 ${sleepMs / 1000L}秒后巡检")
            return HiredMonitorDecision(isHired = true, hasRecalled = false, nextSleepMillis = sleepMs)
        }

        onLog("💰 [雇佣收益抢跑] 当前打工进度 ${progressInt}% 已达到设定目标 ${param.targetThresh}%！正在执行提前召回以抢得满额基础工资与最高增益分成...")
        val (rCode, rErr) = recallStoryAwait(bridge, param.currentStoryId, petId)
        if (rCode == 0) {
            onLog("🎉 [提前召回成功] 宠物已提前回家，正在领取雇佣收益...")
            delay(800L)
            val (sCode, _) = settleStoryAwait(bridge, param.currentStoryId, petId)
            if (sCode == 0) onLog("✅ [雇佣收益入账] 基础工资与最高加成奖金已全额入账！")
            return HiredMonitorDecision(isHired = true, hasRecalled = true, nextSleepMillis = 4000L)
        } else {
            onLog("⚠️ [提前召回重试] 召回指令返回 code=$rCode, 说明: ${rErr ?: "未知"}，将在 15 秒后重试")
            val retrySleepMs = StealthScheduler.calculateHiredMonitorSleepMillis(0L, hasReachedTarget = true)
            return HiredMonitorDecision(isHired = true, hasRecalled = false, nextSleepMillis = retrySleepMs)
        }
    }

    suspend fun checkAndExecuteRecall(
        bridge: QQPetDirectBridge,
        petId: String,
        param: RecallCheckParam,
        onLog: (String) -> Unit
    ): Boolean = evaluateHiredMonitor(bridge, petId, param, onLog).hasRecalled

    suspend fun checkAndExecuteRecall(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        param: RecallCheckParam,
        onLog: (String) -> Unit
    ): Boolean = checkAndExecuteRecall(bridge, petId, param, onLog)
}

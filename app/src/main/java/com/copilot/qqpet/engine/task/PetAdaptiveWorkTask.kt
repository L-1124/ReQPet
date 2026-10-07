package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.engine.TaskLogger
import com.copilot.qqpet.engine.model.StoryStatusResult
import com.copilot.qqpet.engine.model.WorkDispatchParam
import com.copilot.qqpet.engine.model.WorkDispatchResult
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge

/**
 * 负责小镇场所自适应识别、岗位偏好匹配、空闲好友雇佣与打工派遣
 */
object PetAdaptiveWorkTask {

    const val CODE_ALREADY_OUT = -6

    suspend fun executeAdaptiveWork(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        param: WorkDispatchParam,
        onLog: TaskLogger
    ): WorkDispatchResult {
        val (targetCareerType, placeName) = resolveCareerTypeAndPlace(param)
        onLog("[动态求职] 锁定场所: $placeName (Career=$targetCareerType，工时设置=${param.customWorkDuration})")
        onLog("[动态求职] 先确认小宠是否已在外出...")
        val (story, ongoing) = describeOngoingOuting(bridge, petId, onLog)
        if (story.code != 0) {
            onLog("[外出判定] 状态没查完，本次不拉取岗位，也不发起雇佣")
            return WorkDispatchResult(code = story.code, errorMsg = story.bodyNote ?: "状态查询未完成", placeName = placeName)
        }
        if (ongoing != null) {
            onLog("[当前外出] $ongoing。本次不拉取岗位，也不发起雇佣")
            return WorkDispatchResult(code = CODE_ALREADY_OUT, errorMsg = ongoing, placeName = placeName)
        }

        if (param.customWorkType <= 0 && param.cachedWorkPlaces == null) {
            onLog.warn("[打工调度] 还没有场所缓存，本次不猜测星尘魔法塔，请先打开一次设置页同步解锁状态")
            return WorkDispatchResult(code = -4, errorMsg = "场所缓存为空", placeName = placeName)
        }
        onLog("[动态求职] 当前未在外出，正在拉取「$placeName」岗位...")
        val (evtCode, dynamicJobs) = PetWorkTask.querySelectEventsAwait(
            bridge, 6400L, petId, schoolStage = 0, careerType = targetCareerType, onLog = onLog
        )
        if (evtCode != 0 || dynamicJobs.isEmpty()) {
            onLog.warn("[打工调度] 岗位查询失败 code=$evtCode，停在「$placeName」，不改去其它场所")
            return WorkDispatchResult(code = evtCode, errorMsg = "岗位查询失败", placeName = placeName)
        }
        onLog("[岗位拉取] 服务端返回 ${dynamicJobs.size} 个打工岗位")
        val targetJob = filterAndSelectJob(dynamicJobs, param)
        if (targetJob == null) {
            onLog.warn("[打工调度] 「$placeName」没有与所设工时匹配的可做岗位，本次不派遣")
            return WorkDispatchResult(code = -3, errorMsg = "没有与所设工时匹配的岗位", placeName = placeName)
        }
        if (param.enableFatigueToAdventure && targetJob.isFatigued) {
            return WorkDispatchResult(code = -2, isFatigued = true, fatigueTip = targetJob.eventTips)
        }
        onLog("[小镇上岗] 锁定岗位: ${targetJob.eventName} (工时:${targetJob.costTime})，发起启程...")
        val hireRes = PetWorkTask.startWorkWithOptionalHireAwait(
            context = context, bridge = bridge, petId = petId, jobName = targetJob.eventName,
            page = 6400L, subEventType = targetJob.subEventType, hireCandidates = param.hireCandidates,
            enableHireFriend = param.enableHireFriend, onLog = onLog
        )
        if (hireRes.code == 0 && !hireRes.storyId.isNullOrEmpty()) {
            return WorkDispatchResult(
                code = 0, storyId = hireRes.storyId, jobName = targetJob.eventName,
                placeName = placeName, subEventType = targetJob.subEventType, hiredFriend = hireRes.hiredFriend
            )
        }
        if (PetPureCalculations.isPetAlreadyOutError(hireRes.code, hireRes.errorMsg)) {
            val detail = describeOngoingOuting(bridge, petId, onLog).second
            val reason = detail ?: listOfNotNull(hireRes.errorMsg, "code=${hireRes.code}").joinToString(" ")
            onLog("[当前外出] 开工被拒绝。$reason")
        } else {
            onLog.warn("[打工调度] ${targetJob.eventName} 未开工 (code=${hireRes.code} ${hireRes.errorMsg ?: "无说明"})，不改派其它场所")
        }
        return WorkDispatchResult(
            code = hireRes.code, errorMsg = hireRes.errorMsg ?: "开工未生效",
            jobName = targetJob.eventName, placeName = placeName, subEventType = targetJob.subEventType
        )
    }

    private suspend fun describeOngoingOuting(
        bridge: QQPetDirectBridge,
        petId: String,
        onLog: TaskLogger? = null
    ): Pair<StoryStatusResult, String?> {
        val story = PetWorkTask.queryStoryStatusAwait(bridge, petId)
        onLog?.log(EngineLog.Level.INFO, "[外出判定] code=${story.code} 子状态=${story.status ?: "无"} 剩余=${story.remaining ?: "无"} 总时长=${story.total ?: "无"} story=${story.storyId ?: "无"}")
        if (!story.bodyNote.isNullOrBlank()) onLog?.log(EngineLog.Level.INFO, "[外出回包] ${story.bodyNote}")
        val rem = story.remaining
        val storyId = story.storyId?.takeIf { it.isNotEmpty() }
        val reject = when {
            story.code != 0 -> "状态查询未成功"
            rem == null -> "回包没有剩余时间。子状态为 0 或没有字段 1 时，不把这次当成外出"
            storyId == null -> "回包没有 StoryID"
            rem <= 0L -> "剩余时间是 0"
            else -> null
        }
        if (reject != null) {
            val prefix = if (story.code != 0) "状态没查完" else "因此视为未在外出"
            onLog?.log(EngineLog.Level.INFO, "[外出判定] $prefix：$reject")
            return Pair(story, null)
        }
        if (rem == null || rem <= 0L || storyId == null) return Pair(story, null)
        val kind = when {
            storyId.startsWith("6100") -> "学园修习"
            storyId.startsWith("6400") -> "小镇打工"
            else -> "森林探险"
        }
        return Pair(story, "$kind，StoryID=$storyId，剩余 ${PetPureCalculations.formatDuration(rem)}")
    }

    private fun resolveCareerTypeAndPlace(param: WorkDispatchParam): Pair<Int, String> {
        if (param.customWorkType > 0) {
            val matched = param.cachedWorkPlaces?.stages?.find { it.stage == param.customWorkType }
            return Pair(param.customWorkType, matched?.title ?: "小镇场所#${param.customWorkType}")
        }
        val unlockedCareers = param.cachedWorkPlaces?.stages?.filter { it.limitStatus == 0 }
        if (!unlockedCareers.isNullOrEmpty()) {
            val starTower = unlockedCareers.find { it.stage == 3 }
            if (starTower != null) return Pair(3, starTower.title)
            val pick = unlockedCareers[param.workJobCursor % unlockedCareers.size]
            return Pair(pick.stage, pick.title)
        }
        return Pair(param.customWorkType, "未缓存场所")
    }

    private fun filterAndSelectJob(jobs: List<QQPetDirectBridge.SelectEvent>, param: WorkDispatchParam): QQPetDirectBridge.SelectEvent? {
        val available = jobs.filter { it.canDo }.ifEmpty { jobs }
        if (available.isEmpty()) return null
        return when (param.customWorkDuration) {
            1 -> available.find { durationMatches(it.costTime, "10") }
            2 -> available.find { durationMatches(it.costTime, "45") }
            3 -> available.find { it.costTime.contains("2小时") }
            4 -> available.find { it.costTime.contains("4小时") }
            else -> if (param.enableFatigueToAdventure) available.lastOrNull { !it.isFatigued } ?: available.last() else available.last()
        }
    }

    private fun durationMatches(costTime: String, minuteMark: String): Boolean {
        return costTime.contains("${minuteMark}分") || costTime.contains("${minuteMark}分钟") || costTime.contains("${minuteMark}min")
    }
}

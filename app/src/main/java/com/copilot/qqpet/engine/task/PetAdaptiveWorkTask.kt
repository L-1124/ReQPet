package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.model.WorkDispatchParam
import com.copilot.qqpet.engine.model.WorkDispatchResult
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay

/**
 * 负责小镇场所自适应识别、岗位偏好匹配、空闲好友雇佣与打工派遣
 */
object PetAdaptiveWorkTask {

    suspend fun executeAdaptiveWork(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        param: WorkDispatchParam,
        onLog: (String) -> Unit
    ): WorkDispatchResult {
        val (targetCareerType, placeName) = resolveCareerTypeAndPlace(param)
        onLog("💼 [动态求职] 锁定场所: $placeName (Career=$targetCareerType)")

        val (evtCode, dynamicJobs) = PetWorkTask.querySelectEventsAwait(bridge, 6400L, petId, schoolStage = 0, careerType = targetCareerType)
        if (evtCode == 0 && dynamicJobs.isNotEmpty()) {
            onLog("💼 [岗位拉取] 服务端返回 ${dynamicJobs.size} 个打工岗位")
            val targetJob = filterAndSelectJob(dynamicJobs, param)
            if (param.enableFatigueToAdventure && targetJob.isFatigued) {
                return WorkDispatchResult(code = -2, isFatigued = true, fatigueTip = targetJob.eventTips)
            }
            onLog("💼 [小镇上岗] 锁定岗位: ${targetJob.eventName} (工时:${targetJob.costTime})，发起启程...")
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
                onLog("ℹ️ [动态求职] 小宠已在出行中 (${hireRes.errorMsg ?: "code=${hireRes.code}"})，跳过候选池")
                return WorkDispatchResult(
                    code = hireRes.code, errorMsg = hireRes.errorMsg ?: "小宠已在出行中",
                    jobName = targetJob.eventName, placeName = placeName, subEventType = targetJob.subEventType
                )
            }
            onLog("⚠️ [动态求职] ${targetJob.eventName} 派遣未生效 (code=${hireRes.code})，尝试候选池保底...")
        } else {
            onLog("⚠️ [动态求职] 岗位拉取回包 code=$evtCode，尝试候选池保底...")
        }

        return tryCandidatePoolWork(context, bridge, petId, param, onLog)
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
        return Pair(3, "星尘魔法塔")
    }

    private fun filterAndSelectJob(jobs: List<QQPetDirectBridge.SelectEvent>, param: WorkDispatchParam): QQPetDirectBridge.SelectEvent {
        val available = jobs.filter { it.canDo }.ifEmpty { jobs }
        return when (param.customWorkDuration) {
            1 -> available.find { it.costTime.contains("10") } ?: available.first()
            2 -> available.find { it.costTime.contains("45") } ?: available.first()
            3 -> available.find { it.costTime.contains("2小时") } ?: available.first()
            4 -> available.find { it.costTime.contains("4小时") } ?: available.first()
            else -> if (param.enableFatigueToAdventure) available.lastOrNull { !it.isFatigued } ?: available.last() else available.last()
        }
    }

    private suspend fun tryCandidatePoolWork(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        param: WorkDispatchParam,
        onLog: (String) -> Unit
    ): WorkDispatchResult {
        val pool = buildCandidatePool(param)
        for (job in pool) {
            onLog("💼 [小镇兼职] 尝试候选打工岗位: ${job.first} (subEvent=${job.third})...")
            val hireRes = PetWorkTask.startWorkWithOptionalHireAwait(
                context = context, bridge = bridge, petId = petId, jobName = job.first,
                page = job.second, subEventType = job.third, hireCandidates = param.hireCandidates,
                enableHireFriend = param.enableHireFriend, onLog = onLog
            )
            if (hireRes.code == 0 && !hireRes.storyId.isNullOrEmpty()) {
                return WorkDispatchResult(
                    code = 0, storyId = hireRes.storyId, jobName = job.first,
                    placeName = "小镇兼职", subEventType = job.third, hiredFriend = hireRes.hiredFriend
                )
            }
            if (PetPureCalculations.isPetAlreadyOutError(hireRes.code, hireRes.errorMsg)) {
                onLog("ℹ️ [小镇兼职] 小宠已在出行中 (${hireRes.errorMsg ?: "code=${hireRes.code}"})，立即中止候选")
                return WorkDispatchResult(
                    code = hireRes.code, errorMsg = hireRes.errorMsg ?: "小宠已在出行中",
                    jobName = job.first, placeName = "小镇兼职", subEventType = job.third
                )
            }
            delay(350L)
        }
        return WorkDispatchResult(code = -1, errorMsg = "所有候选兼职岗位均未能开工")
    }

    private fun buildCandidatePool(param: WorkDispatchParam): List<Triple<String, Long, Long>> {
        val list = mutableListOf<Triple<String, Long, Long>>()
        if (param.learnedWorkSubEvent != null && param.learnedWorkSubEvent > 0L) {
            list.add(Triple(param.learnedWorkName ?: "当前小镇兼职岗位", 6400L, param.learnedWorkSubEvent))
        }
        when (param.workMode) {
            1 -> list.addAll(PetWorkTask.CANDIDATE_JOBS_CLERK)
            2 -> list.addAll(PetWorkTask.CANDIDATE_JOBS_PHYSICAL)
            3 -> list.addAll(PetWorkTask.CANDIDATE_JOBS_PERFORM)
            else -> {
                val allDirections = listOf(
                    PetWorkTask.CANDIDATE_JOBS_CLERK,
                    PetWorkTask.CANDIDATE_JOBS_PHYSICAL,
                    PetWorkTask.CANDIDATE_JOBS_PERFORM
                )
                list.addAll(allDirections[param.workJobCursor % allDirections.size])
            }
        }
        return list.distinctBy { Pair(it.first, it.third) }
    }
}

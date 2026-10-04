package com.copilot.qqpet.engine.task

import com.copilot.qqpet.engine.model.StudyDispatchParam
import com.copilot.qqpet.engine.model.StudyDispatchResult
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责学园学习调度、科目自适应选择与年级升学课程派遣
 */
object PetStudyTask {

    private const val NETWORK_TIMEOUT_MS = 8000L

    val CANDIDATE_COURSES_INTELLECT = listOf(
        Triple("星空观察课", 6100L, 6101L),
        Triple("智力(文化课程)", 6100L, 6101L),
        Triple("文化学园初阶", 6100L, 6101L),
        Triple("中级智力课", 6100L, 6101L),
        Triple("", 6100L, 6101L)
    )
    val CANDIDATE_COURSES_STRENGTH = listOf(
        Triple("料理实验课", 6100L, 6201L),
        Triple("力量修习", 6100L, 6201L),
        Triple("体能锻炼初阶", 6100L, 6201L),
        Triple("中级力量课", 6100L, 6201L),
        Triple("", 6100L, 6201L)
    )
    val CANDIDATE_COURSES_CHARM = listOf(
        Triple("奇想夏令营", 6100L, 6301L),
        Triple("艺科修习", 6100L, 6301L),
        Triple("艺术修养初阶", 6100L, 6301L),
        Triple("中级魅力课", 6100L, 6301L),
        Triple("", 6100L, 6301L)
    )

    suspend fun startSchoolAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        courseName: String = "基础学园课程",
        page: Long = 6100L,
        subEventType: Long = 6101L,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, String?, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.startSchool(petId, courseName, page, subEventType) { code, storyId, _, errorMsg ->
                        if (cont.isActive) cont.resume(Triple(code, storyId, errorMsg))
                    }
                }
            } ?: Triple(-99, null, "网络响应超时")
        } catch (t: Throwable) {
            Triple(-99, null, t.message)
        }

    suspend fun executeAdaptiveStudy(
        bridge: QQPetDirectBridge,
        petId: String,
        param: StudyDispatchParam,
        onLog: (String) -> Unit
    ): StudyDispatchResult {
        val targetStage = resolveTargetStage(bridge, petId, param.customSchoolStage)
        onLog("📚 [学园阶段] 锁定目标学园阶段: stage=$targetStage")

        val (evtCode, dynamicEvents) = PetWorkTask.querySelectEventsAwait(bridge, 6100L, petId, schoolStage = targetStage, careerType = 0)
        if (evtCode == 0 && dynamicEvents.isNotEmpty()) {
            onLog("📚 [课程拉取] 服务端返回 ${dynamicEvents.size} 门课程")
            val targetCourse = filterAndSelectCourse(dynamicEvents, param)
            if (param.enableFatigueToAdventure && targetCourse.isFatigued) {
                return StudyDispatchResult(code = -2, isFatigued = true, fatigueTip = targetCourse.eventTips)
            }
            onLog("📚 [学园报名] 锁定课程: ${targetCourse.eventName} (时长:${targetCourse.costTime})，发起启程...")
            val (code, storyId, errMsg) = startSchoolAwait(bridge, petId, targetCourse.eventName, 6100L, targetCourse.subEventType)
            if (code == 0 && !storyId.isNullOrEmpty()) {
                return StudyDispatchResult(code = 0, storyId = storyId, courseName = targetCourse.eventName, subEventType = targetCourse.subEventType)
            }
            if (PetPureCalculations.isPetAlreadyOutError(code, errMsg)) {
                onLog("ℹ️ [学园报名] 小宠已在出行中 (${errMsg ?: "code=$code"})，跳过候选池")
                return StudyDispatchResult(code = code, errorMsg = errMsg ?: "小宠已在出行中", courseName = targetCourse.eventName, subEventType = targetCourse.subEventType)
            }
            onLog("⚠️ [动态选课] ${targetCourse.eventName} 报名未成功 (code=$code, err=${errMsg ?: "无"})，尝试同阶段备选...")
            val otherCandidates = dynamicEvents.filter { it.subEventType != targetCourse.subEventType && it.canDo }
            for (alt in otherCandidates) {
                onLog("📚 [学园备选] 尝试同阶段备选课程: ${alt.eventName} (subEvent=${alt.subEventType})...")
                val (altCode, altStoryId, altErrMsg) = startSchoolAwait(bridge, petId, alt.eventName, 6100L, alt.subEventType)
                if (altCode == 0 && !altStoryId.isNullOrEmpty()) {
                    return StudyDispatchResult(code = 0, storyId = altStoryId, courseName = alt.eventName, subEventType = alt.subEventType)
                }
                if (PetPureCalculations.isPetAlreadyOutError(altCode, altErrMsg)) {
                    onLog("ℹ️ [学园备选] 小宠已在出行中 (${altErrMsg ?: "code=$altCode"})，中止尝试")
                    return StudyDispatchResult(code = altCode, errorMsg = altErrMsg ?: "小宠已在出行中", courseName = alt.eventName, subEventType = alt.subEventType)
                }
                delay(350L)
            }
        } else {
            onLog("⚠️ [动态选课] 拉取动态课程回包 code=$evtCode，尝试候选池保底...")
        }

        if (targetStage > 1) {
            return StudyDispatchResult(code = -1, errorMsg = "阶段 $targetStage 所有动态课程均未能成功报名，阻止降级回初级学园")
        }
        return tryCandidatePoolStudy(bridge, petId, param, onLog)
    }

    private suspend fun resolveTargetStage(bridge: QQPetDirectBridge, petId: String, customStage: Int): Int {
        if (customStage > 0) return customStage
        val details = PetWorkTask.querySecondMapInfoDetailsAwait(bridge, 6100L, petId)
        return if (details.code == 0 && details.currentStage > 0) details.currentStage else 1
    }

    private fun filterAndSelectCourse(events: List<QQPetDirectBridge.SelectEvent>, param: StudyDispatchParam): QQPetDirectBridge.SelectEvent {
        val available = events.filter { it.canDo }.ifEmpty { events }
        val durationFiltered = when (param.customCourseDuration) {
            1 -> available.filter { it.costTime.contains("分") && !it.costTime.contains("90") && !it.costTime.contains("135") }
            2 -> available.filter { it.costTime.contains("小时") || it.costTime.contains("90") || it.costTime.contains("135") }
            else -> if (param.enableFatigueToAdventure) available.filter { !it.isFatigued }.ifEmpty { available } else available
        }.ifEmpty { available }

        return when (param.customCourseSubject) {
            1 -> durationFiltered.find { it.reward.contains("智力") } ?: durationFiltered.first()
            2 -> durationFiltered.find { it.reward.contains("力量") } ?: durationFiltered.first()
            3 -> durationFiltered.find { it.reward.contains("魅力") } ?: durationFiltered.first()
            else -> {
                val idx = param.studyAttributeCursor % durationFiltered.size
                durationFiltered[idx]
            }
        }
    }

    private suspend fun tryCandidatePoolStudy(
        bridge: QQPetDirectBridge,
        petId: String,
        param: StudyDispatchParam,
        onLog: (String) -> Unit
    ): StudyDispatchResult {
        val pool = buildCandidatePool(param)
        for (course in pool) {
            onLog("📚 [学园修行] 尝试修习候选课程: ${course.first} (subEvent=${course.third})...")
            val (code, storyId, errMsg) = startSchoolAwait(bridge, petId, course.first, course.second, course.third)
            if (code == 0 && !storyId.isNullOrEmpty()) {
                return StudyDispatchResult(code = 0, storyId = storyId, courseName = course.first, subEventType = course.third)
            }
            if (PetPureCalculations.isPetAlreadyOutError(code, errMsg)) {
                onLog("ℹ️ [学园修行] 小宠已在出行中 (${errMsg ?: "code=$code"})，中止候选修习")
                return StudyDispatchResult(code = code, errorMsg = errMsg ?: "小宠已在出行中", courseName = course.first, subEventType = course.third)
            }
            delay(350L)
        }
        return StudyDispatchResult(code = -1, errorMsg = "所有候选课程修习均未能开课")
    }

    private fun buildCandidatePool(param: StudyDispatchParam): List<Triple<String, Long, Long>> {
        val list = mutableListOf<Triple<String, Long, Long>>()
        if (param.learnedStudySubEvent != null && param.learnedStudySubEvent > 0L) {
            list.add(Triple(param.learnedStudyName ?: "当前学园主修课程", 6100L, param.learnedStudySubEvent))
        }
        val effectiveSubj = if (param.customCourseSubject > 0) param.customCourseSubject else param.studyMode
        when (effectiveSubj) {
            1 -> list.addAll(CANDIDATE_COURSES_INTELLECT)
            2 -> list.addAll(CANDIDATE_COURSES_STRENGTH)
            3 -> list.addAll(CANDIDATE_COURSES_CHARM)
            else -> {
                val directions = listOf(CANDIDATE_COURSES_INTELLECT, CANDIDATE_COURSES_STRENGTH, CANDIDATE_COURSES_CHARM)
                list.addAll(directions[param.studyAttributeCursor % directions.size])
            }
        }
        return list.distinctBy { Pair(it.first, it.third) }
    }
}

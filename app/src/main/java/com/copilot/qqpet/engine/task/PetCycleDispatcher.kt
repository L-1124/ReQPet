package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.engine.model.StudyDispatchParam
import com.copilot.qqpet.engine.model.WorkDispatchParam
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlin.coroutines.resume

/**
 * 负责主循环任务的分发、自适应学业、打工、探险与即时指令路由
 */
object PetCycleDispatcher {

    suspend fun dispatchNextAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Long {
        val available = mutableListOf<String>()
        if (PetAdventureEngine.enableStudy) available.add("study")
        if (PetAdventureEngine.enableWork) available.add("work")
        if (PetAdventureEngine.enableAdventure) available.add("adventure")
        if (available.isEmpty()) return 30000L

        val target = available[PetAdventureEngine.roundRobinCursor % available.size]
        PetAdventureEngine.roundRobinCursor = (PetAdventureEngine.roundRobinCursor + 1) % available.size
        executeAction(context, bridge, petId, target)
        return 5000L
    }

    suspend fun executeAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        action: String
    ) {
        if (dispatchCareerAction(context, bridge, petId, action)) return
        if (dispatchCareAction(context, bridge, petId, action)) return
        if (dispatchSocialAction(context, bridge, petId, action)) return
    }

    private suspend fun dispatchCareerAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        action: String
    ): Boolean {
        return when (action) {
            "study", "school" -> {
                val ok = dispatchStudy(context, bridge, petId)
                showToast(context, if (ok) "已成功安排学园课程修习" else "课程开课未生效，详情见日志")
                true
            }
            "work" -> {
                val ok = dispatchWork(context, bridge, petId)
                showToast(context, if (ok) "已成功安排兼职打工派遣" else "打工开工未生效，详情见日志")
                true
            }
            "adventure" -> {
                val ok = dispatchAdventure(context, bridge, petId)
                showToast(context, if (ok) "已成功启程森林探险巡航" else "探险启程未生效，详情见日志")
                true
            }
            "settle" -> {
                PetAdventureEngine.lastActiveStoryId?.let { sId ->
                    val (code, _) = PetHiredRecallTask.settleStoryAwait(bridge, sId, petId)
                    if (code == 0) {
                        PetSocialTask.claimOnceAfterSettle(
                            context, bridge, petId, PetAdventureEngine.currentActiveUin, PetAdventureEngine.enableClaimCoinBag
                        ) { PetAdventureEngine.sendLog(context, it) }
                    }
                    showToast(context, "已发起探险收益结算")
                } ?: showToast(context, "当前暂无待结算任务")
                true
            }
            "recall" -> {
                PetAdventureEngine.lastActiveStoryId?.let { sId ->
                    PetHiredRecallTask.recallStoryAwait(bridge, sId, petId)
                    showToast(context, "已发起宠物返程召回")
                } ?: showToast(context, "小宠当前未在外出派遣状态")
                true
            }
            else -> false
        }
    }

    private suspend fun dispatchCareAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        action: String
    ): Boolean {
        return when (action) {
            "care" -> {
                PetCareTask.feedWithAutoBuyAwait(context, bridge, petId, PetAdventureEngine.prefCareEnergyThreshold) { PetAdventureEngine.sendLog(context, it) }
                PetCareTask.bathWithAutoBuyAwait(context, bridge, petId, PetAdventureEngine.prefCareCleanThreshold) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已触发小宠进食与洗澡巡检")
                true
            }
            "feed" -> {
                PetCareTask.feedWithAutoBuyAwait(context, bridge, petId, PetAdventureEngine.prefCareEnergyThreshold) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已触发小宠进食补充体力")
                true
            }
            "bath" -> {
                PetCareTask.bathWithAutoBuyAwait(context, bridge, petId, PetAdventureEngine.prefCareCleanThreshold) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已触发小宠沐浴恢复清洁")
                true
            }
            "friend_care" -> {
                val params = PetFriendCareTask.FriendCareParams(
                    context = context,
                    bridge = bridge,
                    ownPetId = petId,
                    energyThreshold = PetAdventureEngine.prefFriendCareEnergyThreshold,
                    cleanThreshold = PetAdventureEngine.prefFriendCareCleanThreshold,
                    isManual = true
                )
                val summary = PetFriendCareTask.executeAutoFriendCare(params) { PetAdventureEngine.sendLog(context, it) }
                val msg = if (summary.checkedCount > 0) {
                    "好友照料完成：喂食 ${summary.fedCount} 位，洗澡 ${summary.bathedCount} 位"
                } else {
                    "暂未发现可照料的养宠好友"
                }
                showToast(context, msg)
                true
            }
            else -> false
        }
    }

    private suspend fun dispatchSocialAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        action: String
    ): Boolean {
        return when (action) {
            "like_back" -> {
                val friends = PetAdventureEngine.loadCachedHireableFriends(context)
                val params = PetSocialTask.LikeBackParams(
                    context = context,
                    bridge = bridge,
                    currentUin = PetAdventureEngine.currentActiveUin,
                    cachedFriends = friends,
                    isManual = true
                )
                val count = PetSocialTask.executeAutoLikeBack(params) { PetAdventureEngine.sendLog(context, it) }
                val msg = if (count > 0) "成功回赠 $count 位来访小伙伴" else "暂无可回踩的来访记录，详情见日志"
                showToast(context, msg)
                true
            }
            "active_visit" -> {
                val friends = PetAdventureEngine.loadCachedHireableFriends(context)
                PetActiveVisitTask.executeActiveVisitSession(
                    context = context,
                    bridge = bridge,
                    currentUin = PetAdventureEngine.currentActiveUin,
                    cachedFriends = friends,
                    enableFriends = PetAdventureEngine.prefActiveVisitFriends,
                    enableStrangers = PetAdventureEngine.prefActiveVisitStrangers,
                    dailyLimit = PetAdventureEngine.prefActiveVisitDailyLimit,
                    isManual = true
                ) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已触发主动串门送心")
                true
            }
            "claim_coinbag", "coinbag" -> {
                val count = PetSocialTask.executeAutoClaimCoinBags(context, bridge, petId, PetAdventureEngine.currentActiveUin, true) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, if (count > 0) "🎉 成功拆开 $count 个金币福袋！" else "当前暂无可领取的金币福袋")
                true
            }
            "pk_auto", "pk" -> {
                val school = PetAdventureEngine.cachedSchoolDetails
                val myTotal = if (school != null && (school.power + school.intel + school.charm > 0L)) {
                    school.power + school.intel + school.charm
                } else {
                    999999L
                }
                val friends = PetAdventureEngine.loadCachedHireableFriends(context)
                val candidates = PetPkTask.collectPkCandidates(context, bridge, petId, PetAdventureEngine.currentActiveUin, friends)
                val count = PetPkTask.executeSinglePk(context, bridge, petId, PetAdventureEngine.currentActiveUin, candidates, myTotal, 0L) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "自动 PK 挑战已执行 (今日第 $count 场)")
                true
            }
            else -> false
        }
    }

    private fun showToast(context: Context, text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    private suspend fun dispatchStudy(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        val param = StudyDispatchParam(
            studyMode = PetAdventureEngine.prefStudyMode,
            customSchoolStage = PetAdventureEngine.prefCustomSchoolStage,
            customCourseSubject = PetAdventureEngine.prefCustomCourseSubject,
            customCourseDuration = PetAdventureEngine.prefCustomCourseDuration,
            enableFatigueToAdventure = PetAdventureEngine.enableFatigueToAdventure,
            studyAttributeCursor = PetAdventureEngine.studyAttributeCursor,
            learnedStudySubEvent = PetAdventureEngine.learnedStudySubEvent,
            learnedStudyName = PetAdventureEngine.learnedStudyName
        )
        val res = PetStudyTask.executeAdaptiveStudy(bridge, petId, param) { PetAdventureEngine.sendLog(context, it) }
        if (res.isSuccess) {
            PetAdventureEngine.lastActiveStoryId = res.storyId
            PetAdventureEngine.currentTaskTypeName = "进阶修习中 (${res.courseName ?: "学园课程"})"
            PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
            PetAdventureEngine.currentStatusText = "正在进修 ${res.courseName ?: "学园课程"}"
            PetAdventureEngine.sendLog(context, "🎉 [开课成功] 顺利开启 ${res.courseName}！StoryID: ${res.storyId}，学分高速增长中")
            PetAdventureEngine.studyAttributeCursor++
            return true
        }
        if (res.isFatigued && PetAdventureEngine.enableFatigueToAdventure) {
            PetAdventureEngine.sendLog(context, "😴 [疲惫避让] 学园课程标记为疲惫 (${res.fatigueTip ?: "收益降低"})，智能避让转入森林探险...")
            dispatchAdventure(context, bridge, petId)
            return true
        }
        PetAdventureEngine.sendLog(context, "⚠️ [学业调度] 本轮选课未成功开课 (${res.errorMsg ?: "服务端拒绝"})")
        return false
    }

    private suspend fun dispatchWork(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        val hireCandidates = PetWorkTask.selectBestHireCandidatesAwait(
            context = context, bridge = bridge, ownPetId = petId, currentUin = PetAdventureEngine.currentActiveUin,
            enableHireFriend = PetAdventureEngine.enableHireFriend, cachedFriends = PetAdventureEngine.cachedHireableFriends
        ) { PetAdventureEngine.sendLog(context, it) }

        val param = WorkDispatchParam(
            workMode = PetAdventureEngine.prefWorkMode,
            customWorkType = PetAdventureEngine.prefCustomWorkType,
            customWorkDuration = PetAdventureEngine.prefCustomWorkDuration,
            enableHireFriend = PetAdventureEngine.enableHireFriend,
            enableFatigueToAdventure = PetAdventureEngine.enableFatigueToAdventure,
            cachedWorkPlaces = PetAdventureEngine.cachedWorkPlaces,
            hireCandidates = hireCandidates,
            workJobCursor = PetAdventureEngine.workJobCursor,
            learnedWorkSubEvent = PetAdventureEngine.learnedWorkSubEvent,
            learnedWorkName = PetAdventureEngine.learnedWorkName
        )
        val res = PetAdaptiveWorkTask.executeAdaptiveWork(context, bridge, petId, param) { PetAdventureEngine.sendLog(context, it) }
        if (res.isSuccess) {
            PetAdventureEngine.lastActiveStoryId = res.storyId
            val hireSuffix = if (res.hiredFriend != null) " · 雇佣:${res.hiredFriend.friendNick.ifEmpty { res.hiredFriend.uin.toString() }}" else ""
            PetAdventureEngine.currentTaskTypeName = "打工中 · ${res.placeName ?: "小镇"} (${res.jobName ?: "兼职"}$hireSuffix)"
            PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
            PetAdventureEngine.currentStatusText = "正在 ${res.placeName ?: "小镇"} 进行 ${res.jobName ?: "兼职"}$hireSuffix"
            if (res.hiredFriend != null) {
                val hiredName = res.hiredFriend.friendNick.ifEmpty { res.hiredFriend.uin.toString() }
                PetAdventureEngine.sendLog(context, "🎉 [雇佣打工成功] 顺利雇佣好友「$hiredName」协同开工 ${res.placeName} - ${res.jobName}！StoryID: ${res.storyId}")
                if (PetAdventureEngine.enableFriendCare) {
                    PetFriendCareTask.careJustHiredFriend(
                        PetFriendCareTask.FriendCareParams(
                            context = context,
                            bridge = bridge,
                            ownPetId = petId,
                            energyThreshold = PetAdventureEngine.prefFriendCareEnergyThreshold,
                            cleanThreshold = PetAdventureEngine.prefFriendCareCleanThreshold,
                            isManual = false
                        ),
                        res.hiredFriend
                    ) { PetAdventureEngine.sendLog(context, it) }
                }
            } else {
                PetAdventureEngine.sendLog(context, "🎉 [打工成功] 顺利开工 ${res.placeName} - ${res.jobName}！StoryID: ${res.storyId}，勤劳致富中")
            }
            PetAdventureEngine.workJobCursor++
            return true
        }
        if (res.isFatigued && PetAdventureEngine.enableFatigueToAdventure) {
            PetAdventureEngine.sendLog(context, "😴 [疲惫避让] 打工岗位标记为疲惫 (${res.fatigueTip ?: "收益降低"})，智能避让转入森林探险...")
            dispatchAdventure(context, bridge, petId)
            return true
        }
        if (res.code == PetAdaptiveWorkTask.CODE_ALREADY_OUT || PetPureCalculations.isPetAlreadyOutError(res.code, res.errorMsg)) {
            return false
        }
        if (res.jobName == null && res.code != 0) return false
        PetAdventureEngine.sendLog(context, "⚠️ [打工调度] 本轮打工未成功开工 (${res.errorMsg ?: "服务端拒绝"})")
        return false
    }

    private suspend fun dispatchAdventure(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        PetAdventureEngine.currentStatusText = "森林探险启程中..."
        PetAdventureEngine.sendLog(context, "🌲 [探险启程] 正在前往神秘森林发起探险巡航...")
        val adventureRes: Pair<Int, String?>? = kotlinx.coroutines.withTimeoutOrNull(8000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Pair<Int, String?>> { cont ->
                bridge.startAdventure(petId) { code, storyId, _, _ ->
                    if (cont.isActive) cont.resume(Pair(code, storyId))
                }
            }
        }
        val code = adventureRes?.first ?: -99
        val storyId = adventureRes?.second
        if (code == 0 && !storyId.isNullOrEmpty()) {
            PetAdventureEngine.lastActiveStoryId = storyId
            PetAdventureEngine.currentTaskTypeName = "森林探险中"
            PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + 1800 * 1000L
            PetAdventureEngine.currentStatusText = "正在神秘森林探险寻宝中"
            PetAdventureEngine.sendLog(context, "🎉 [探险成功] 顺利踏入神秘森林！StoryID: $storyId，奇遇宝藏探索中")
            return true
        }
        PetAdventureEngine.sendLog(context, "⚠️ [探险回包] 森林探险启程未生效 (code=$code)")
        return false
    }
}

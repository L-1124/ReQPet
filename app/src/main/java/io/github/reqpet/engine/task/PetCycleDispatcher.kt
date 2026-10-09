package io.github.reqpet.engine.task

import android.content.Context
import io.github.reqpet.engine.PetAccountGateway
import io.github.reqpet.engine.EngineLog
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.engine.RuntimeDiagnostics
import io.github.reqpet.engine.config.TimeConfigManager
import io.github.reqpet.engine.model.StudyDispatchParam
import io.github.reqpet.engine.model.WorkDispatchParam
import io.github.reqpet.engine.utils.PetPureCalculations
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlin.math.pow

/**
 * 负责主循环任务的分发、自适应学业、打工、探险与即时指令路由
 */
object PetCycleDispatcher {
    @Volatile
    private var consecutiveFailureCount = 0

    fun resetFailureCount() {
        consecutiveFailureCount = 0
    }


    suspend fun dispatchNextAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Long {
        val available = mutableListOf<String>()
        if (PetAdventureEngine.enableStudy) available.add("study")
        if (PetAdventureEngine.enableWork) available.add("work")
        if (PetAdventureEngine.enableAdventure) available.add("adventure")
        if (available.isEmpty()) {
            RuntimeDiagnostics.event(
                "dispatch_result", "cycle" to PetAdventureEngine.currentCycleId,
                "transaction" to PetAdventureEngine.currentTransactionId,
                "outcome" to "disabled", "sleep_ms" to 30_000L
            )
            return 30000L
        }

        val attempts = available.size
        var dispatched = false
        for (i in 0 until attempts) {
            val target = available[PetAdventureEngine.roundRobinCursor % available.size]
            PetAdventureEngine.roundRobinCursor = (PetAdventureEngine.roundRobinCursor + 1) % available.size
            if (executeAction(context, bridge, petId, target)) {
                dispatched = true
                break
            }
        }

        if (dispatched) {
            consecutiveFailureCount = 0
            RuntimeDiagnostics.event(
                "dispatch_result", "cycle" to PetAdventureEngine.currentCycleId,
                "transaction" to PetAdventureEngine.currentTransactionId,
                "outcome" to "started", "sleep_ms" to 5_000L
            )
            return 5000L
        }

        consecutiveFailureCount++
        val backoff = calculateFailureBackoff(consecutiveFailureCount)
        RuntimeDiagnostics.event(
            "dispatch_result", "cycle" to PetAdventureEngine.currentCycleId,
            "transaction" to PetAdventureEngine.currentTransactionId,
            "outcome" to "failed", "attempts" to attempts,
            "consecutive_failures" to consecutiveFailureCount, "sleep_ms" to backoff
        )
        PetAdventureEngine.sendLog(
            EngineLog.Level.WARN,
            "[任务调度] 全量可用任务(共${attempts}项)均派遣失败(连续失败${consecutiveFailureCount}次)，实施退避 ${backoff / 1000L} 秒"
        )
        return backoff
    }

    fun calculateFailureBackoff(consecutiveFailures: Int): Long {
        val maxBackoff = 10 * 60 * 1000L
        val baseBackoff = (30_000L * 1.5.pow(consecutiveFailures.coerceAtMost(8))).toLong() +
                Random.nextLong(5_000L, 15_000L)
        return baseBackoff.coerceAtMost(maxBackoff)
    }

    suspend fun executeAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        action: String
    ): Boolean {
        if (dispatchCareerAction(context, bridge, petId, action)) return true
        if (dispatchCareAction(context, bridge, petId, action)) return true
        if (dispatchSocialAction(context, bridge, petId, action)) return true
        return false
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
                ok
            }

            "work" -> {
                val ok = dispatchWork(context, bridge, petId)
                showToast(context, if (ok) "已成功安排兼职打工派遣" else "打工开工未生效，详情见日志")
                ok
            }

            "adventure" -> {
                val ok = PetAdventureDispatch.dispatchAdventure(context, bridge, petId)
                showToast(context, if (ok) "已成功启程森林探险巡航" else "探险启程未生效，详情见日志")
                ok
            }

            "settle" -> {
                var settled = false
                PetAdventureEngine.lastActiveStoryId?.let { sId ->
                    PetAdventureEngine.pendingSettlementStoryId = sId
                    val (code, _) = PetHiredRecallTask.settleStoryAwait(bridge, sId, petId)
                    if (code == 0 || code != 135004) {
                        PetAdventureEngine.clearSettledStory(sId)
                        settled = (code == 0)
                        if (code == 0) {
                            PetSocialTask.claimOnceAfterSettle(
                                context,
                                bridge,
                                petId,
                                PetAdventureEngine.currentActiveUin,
                                PetAdventureEngine.enableClaimCoinBag
                            ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                        }
                    }
                    showToast(context, if (code == 0) "已发起探险收益结算" else "任务已在手Q结清或已失效，已同步状态")
                } ?: showToast(context, "当前暂无待结算任务")
                settled
            }

            "recall" -> {
                var recalled = false
                PetAdventureEngine.lastActiveStoryId?.let { sId ->
                    val (code, _) = PetHiredRecallTask.recallStoryAwait(bridge, sId, petId)
                    recalled = (code == 0)
                    if (recalled) {
                        PetAdventureEngine.markStoryRecalled(sId)
                    }
                    showToast(context, "已发起宠物返程召回")
                } ?: showToast(context, "小宠当前未在外出派遣状态")
                recalled
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
                PetCareTask.feedWithAutoBuyAwait(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.prefCareEnergyThreshold
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                PetCareTask.bathWithAutoBuyAwait(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.prefCareCleanThreshold
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                showToast(context, "已触发小宠进食与洗澡巡检")
                true
            }

            "feed" -> {
                PetCareTask.feedWithAutoBuyAwait(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.prefCareEnergyThreshold
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                showToast(context, "已触发小宠进食补充体力")
                true
            }

            "bath" -> {
                PetCareTask.bathWithAutoBuyAwait(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.prefCareCleanThreshold
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
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
                val summary =
                    PetFriendCareTask.executeAutoFriendCare(params) { level, msg ->
                        PetAdventureEngine.sendLog(
                            level,
                            msg
                        )
                    }
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
                val friends = PetAccountGateway.loadCachedHireableFriends(context)
                val params = PetSocialTask.LikeBackParams(
                    context = context,
                    bridge = bridge,
                    currentUin = PetAdventureEngine.currentActiveUin,
                    cachedFriends = friends,
                    isManual = true
                )
                val count =
                    PetSocialTask.executeAutoLikeBack(params) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                val msg = if (count > 0) "成功回赠 $count 位来访小伙伴" else "暂无可回踩的来访记录，详情见日志"
                showToast(context, msg)
                true
            }

            "active_visit" -> {
                val friends = PetAccountGateway.loadCachedHireableFriends(context)
                PetActiveVisitTask.executeActiveVisitSession(
                    context = context,
                    bridge = bridge,
                    currentUin = PetAdventureEngine.currentActiveUin,
                    cachedFriends = friends,
                    enableFriends = PetAdventureEngine.prefActiveVisitFriends,
                    enableStrangers = PetAdventureEngine.prefActiveVisitStrangers,
                    dailyLimit = PetAdventureEngine.prefActiveVisitDailyLimit,
                    isManual = true
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                showToast(context, "已触发主动串门送心")
                true
            }

            "claim_coinbag", "coinbag" -> {
                val count = PetSocialTask.executeAutoClaimCoinBags(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.currentActiveUin,
                    true
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                showToast(context, if (count > 0) "成功拆开 $count 个金币福袋！" else "当前暂无可领取的金币福袋")
                true
            }

            "pk_auto", "pk" -> {
                val school = PetAdventureEngine.cachedSchoolDetails
                val myTotal = if (school != null && (school.power + school.intel + school.charm > 0L)) {
                    school.power + school.intel + school.charm
                } else {
                    999999L
                }
                val friends = PetAccountGateway.loadCachedHireableFriends(context)
                val candidates =
                    PetPkTask.collectPkCandidates(context, bridge, petId, PetAdventureEngine.currentActiveUin, friends)
                val count = PetPkTask.executeSinglePk(
                    context,
                    bridge,
                    petId,
                    PetAdventureEngine.currentActiveUin,
                    candidates,
                    myTotal,
                    0L
                ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                showToast(context, "自动 PK 挑战已执行 (今日第 $count 场)")
                true
            }

            else -> false
        }
    }

    private suspend fun showToast(context: Context, text: String) {
        val appContext = context.applicationContext ?: context
        withContext(Dispatchers.Main) {
            try {
                android.widget.Toast.makeText(appContext, text, android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
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
        val res = PetStudyTask.executeAdaptiveStudy(bridge, petId, param) { level, msg ->
            PetAdventureEngine.sendLog(
                level,
                msg
            )
        }
        if (res.isSuccess) {
            PetAdventureEngine.lastActiveStoryId = res.storyId
            PetAdventureEngine.currentTaskTypeName = "进阶修习中 (${res.courseName ?: "学园课程"})"
            PetAdventureEngine.currentTaskEndTimeMillis =
                System.currentTimeMillis() + TimeConfigManager.getCurrentDuration("STUDY") * 1000L
            PetAdventureEngine.currentStatusText = "正在进修 ${res.courseName ?: "学园课程"}"
            RuntimeDiagnostics.event(
                "task_started", "transaction" to PetAdventureEngine.currentTransactionId,
                "cycle" to PetAdventureEngine.currentCycleId, "kind" to "study",
                "story_id" to RuntimeDiagnostics.id(res.storyId),
                "task_end_ms" to PetAdventureEngine.currentTaskEndTimeMillis
            )
            PetAdventureEngine.sendLog(
                "[开课成功] 顺利开启 ${res.courseName}！StoryID: ${res.storyId}，学分高速增长中"
            )
            PetAdventureEngine.studyAttributeCursor++
            return true
        }
        if (res.isFatigued && PetAdventureEngine.enableFatigueToAdventure) {
            PetAdventureEngine.sendLog(
                "[疲惫避让] 学园课程标记为疲惫 (${res.fatigueTip ?: "收益降低"})，智能避让转入森林探险..."
            )
            return PetAdventureDispatch.dispatchAdventure(context, bridge, petId)
        }
        PetAdventureEngine.sendLog(
            EngineLog.Level.WARN,
            "[学业调度] 本轮选课未成功开课 (${res.errorMsg ?: "服务端拒绝"})"
        )
        return false
    }

    private suspend fun dispatchWork(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        val hireCandidates = PetWorkTask.selectBestHireCandidatesAwait(
            context = context,
            bridge = bridge,
            ownPetId = petId,
            currentUin = PetAdventureEngine.currentActiveUin,
            enableHireFriend = PetAdventureEngine.enableHireFriend,
            cachedFriends = PetAdventureEngine.cachedHireableFriends
        ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }

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
        val res = PetAdaptiveWorkTask.executeAdaptiveWork(context, bridge, petId, param) { level, msg ->
            PetAdventureEngine.sendLog(level, msg)
        }
        if (res.isSuccess) {
            PetAdventureEngine.lastActiveStoryId = res.storyId
            val hireSuffix =
                if (res.hiredFriend != null) " · 雇佣:${res.hiredFriend.friendNick.ifEmpty { res.hiredFriend.uin.toString() }}" else ""
            PetAdventureEngine.currentTaskTypeName =
                "打工中 · ${res.placeName ?: "小镇"} (${res.jobName ?: "兼职"}$hireSuffix)"
            PetAdventureEngine.currentTaskEndTimeMillis =
                System.currentTimeMillis() + TimeConfigManager.getCurrentDuration("WORK") * 1000L
            PetAdventureEngine.currentStatusText =
                "正在 ${res.placeName ?: "小镇"} 进行 ${res.jobName ?: "兼职"}$hireSuffix"
            RuntimeDiagnostics.event(
                "task_started", "transaction" to PetAdventureEngine.currentTransactionId,
                "cycle" to PetAdventureEngine.currentCycleId, "kind" to "work",
                "story_id" to RuntimeDiagnostics.id(res.storyId), "hired" to (res.hiredFriend != null),
                "task_end_ms" to PetAdventureEngine.currentTaskEndTimeMillis
            )
            if (res.hiredFriend != null) {
                val hiredName = res.hiredFriend.friendNick.ifEmpty { res.hiredFriend.uin.toString() }
                PetAdventureEngine.sendLog(
                    "[雇佣打工成功] 顺利雇佣好友「$hiredName」协同开工 ${res.placeName} - ${res.jobName}！StoryID: ${res.storyId}"
                )
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
                    ) { level, msg -> PetAdventureEngine.sendLog(level, msg) }
                }
            } else {
                PetAdventureEngine.sendLog(
                    "[打工成功] 顺利开工 ${res.placeName} - ${res.jobName}！StoryID: ${res.storyId}，勤劳致富中"
                )
            }
            PetAdventureEngine.workJobCursor++
            return true
        }
        if (res.isFatigued && PetAdventureEngine.enableFatigueToAdventure) {
            PetAdventureEngine.sendLog(
                "[疲惫避让] 打工岗位标记为疲惫 (${res.fatigueTip ?: "收益降低"})，智能避让转入森林探险..."
            )
            return PetAdventureDispatch.dispatchAdventure(context, bridge, petId)
        }
        if (res.code == PetAdaptiveWorkTask.CODE_ALREADY_OUT || PetPureCalculations.isPetAlreadyOutError(
                res.code,
                res.errorMsg
            )
        ) {
            return false
        }
        if (res.jobName == null && res.code != 0) return false
        PetAdventureEngine.sendLog(
            EngineLog.Level.WARN,
            "[打工调度] 本轮打工未成功开工 (${res.errorMsg ?: "服务端拒绝"})"
        )
        return false
    }
}

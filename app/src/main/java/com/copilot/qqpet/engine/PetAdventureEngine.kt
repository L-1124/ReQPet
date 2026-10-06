package com.copilot.qqpet.engine

import android.content.Context
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.model.*
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.engine.task.*
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.hook.HookLog as Log
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.util.UiDescUtils
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * Q宠后台全功能自动化调度引擎 (v1.0.75 架构解耦门面)
 * 采用 Round-Robin 时间片智能轮转算法，调度学业、打工、探险、自理、社交与竞技。
 */
class PetAdventureEngine(private var bridge: QQPetDirectBridge) {

    companion object {
        private const val TAG = "PetAdventureEngine"
        private const val NETWORK_TIMEOUT_MS = 8000L
        var cachedPetId: String? = null
        var lastActiveStoryId: String? = null
        @Volatile var lastReportedOngoingStoryId: String? = null
        @Volatile var currentActiveUin: String = ""
        @Volatile var isLoopRunning = false
        @Volatile var lastFatigueSwitchTimeMillis = 0L

        @Volatile var enableStudy = true; @Volatile var enableWork = true; @Volatile var enableCare = true
        @Volatile var enableAdventure = false; @Volatile var enableSettle = true; @Volatile var enableLikeBack = true
        @Volatile var enableClaimCoinBag = true; @Volatile var enableFatigueToAdventure = true; @Volatile var enableAutoPk = false
        @Volatile var lastPkTimeMillis = 0L; @Volatile var pkCooldownMillis = 60 * 1000L
        @Volatile var prefHumanLikeSleep = true; @Volatile var prefNightSleepMode = true; @Volatile var prefScreenOffSilent = true
        @Volatile var prefHideQQSettingEntry = false; @Volatile var prefDebugLog = false
        @Volatile var enableHireFriend = true; @Volatile var prefHireFriendUinsCsv = ""; @Volatile var prefPkBlacklistUinsCsv = ""
        @Volatile var prefHiredRecallProgress = 72; @Volatile var enableActiveVisit = true
        @Volatile var prefActiveVisitFriends = true; @Volatile var prefActiveVisitStrangers = true
        @Volatile var prefActiveVisitDailyLimit = 20; @Volatile var lastActiveVisitTimeMillis = 0L
        @Volatile var cachedHireableFriends: List<QQPetDirectBridge.HireableFriend> = emptyList()
        @Volatile var enableFriendCare = false; @Volatile var prefFriendCareEnergyThreshold = 60; @Volatile var prefFriendCareCleanThreshold = 60

        @Volatile var currentStatusText = "全自动守护中 · 一刻不停三维轮转"; @Volatile var currentTaskEndTimeMillis = 0L; @Volatile var currentTaskTypeName = "进阶修习中"
        @Volatile var roundRobinCursor = 0; @Volatile var studyAttributeCursor = 0; @Volatile var workJobCursor = 0
        @Volatile var prefStudyMode = 0; @Volatile var prefWorkMode = 0; @Volatile var prefCustomSchoolStage = 0
        @Volatile var prefCustomCourseSubject = 0; @Volatile var prefCustomCourseDuration = 0; @Volatile var prefCustomWorkType = 0
        @Volatile var prefCustomWorkDuration = 0; @Volatile var prefCareEnergyThreshold = 60; @Volatile var prefCareCleanThreshold = 60
        @Volatile var lastCareTimeMillis = 0L; @Volatile var lastLikeBackTimeMillis = 0L; @Volatile var lastCoinBagTimeMillis = 0L
        @Volatile var lastOwnPetCheckMillis = 0L
        @Volatile var cachedSchoolDetails: QQPetDirectBridge.SecondMapDetails? = null; @Volatile var cachedSchoolCourses: List<QQPetDirectBridge.SelectEvent>? = null
        @Volatile var cachedWorkPlaces: QQPetDirectBridge.SecondMapDetails? = null; @Volatile var cachedWorkJobs: List<QQPetDirectBridge.SelectEvent>? = null
        @Volatile var learnedStudySubEvent: Long? = null; @Volatile var learnedStudyName: String? = null
        @Volatile var learnedWorkSubEvent: Long? = null; @Volatile var learnedWorkName: String? = null

        fun calculateHiredProgress(totalSec: Long, remainingSec: Long): Double = PetPureCalculations.calculateHiredProgress(totalSec, remainingSec)
        fun resolveEffectiveTotalSec(totalSec: Long, remainingSec: Long): Long = PetPureCalculations.resolveEffectiveTotalSec(totalSec, remainingSec)
        fun calculateHiredRemainingToTarget(totalSec: Long, remainingSec: Long, targetThreshold: Int): Long = PetPureCalculations.calculateHiredRemainingToTarget(totalSec, remainingSec, targetThreshold)
        fun shouldTriggerHiredRecall(currentProgress: Double, targetThreshold: Int): Boolean = PetPureCalculations.shouldTriggerHiredRecall(currentProgress, targetThreshold)
        fun isEmployedByFriend(employedUin: Long, selfUin: Long): Boolean = PetPureCalculations.isEmployedByFriend(employedUin, selfUin)

        fun isPetAlreadyOutError(code: Int, errMsg: String?): Boolean = PetPureCalculations.isPetAlreadyOutError(code, errMsg)
        fun shouldUpdateCachedPetId(cachedPetId: String?, remotePetId: String?): Boolean = PetPureCalculations.shouldUpdateCachedPetId(cachedPetId, remotePetId)
        fun isPetInvalidOrMismatchError(code: Int, errMsg: String?): Boolean = PetPureCalculations.isPetInvalidOrMismatchError(code, errMsg)

        fun getDailyPkCount(context: Context): Int = AccountSessionStore.getDailyPkCount(context, currentActiveUin)
        fun incrementDailyPkCount(context: Context): Int = AccountSessionStore.incrementDailyPkCount(context, currentActiveUin)

        fun clearAccountBoundMemoryCache() { AccountSessionStore.clearAccountBoundMemoryCache(); cachedSchoolDetails = null; cachedWorkPlaces = null }

        fun parseHireFriendUins(csv: String = prefHireFriendUinsCsv): Set<Long> = PetPureCalculations.parseHireFriendUins(csv)
        fun loadSavedHireFriendUins(context: Context): Set<Long> = AccountSessionStore.loadSavedHireFriendUins(context, currentActiveUin)
        fun saveHireFriendUins(context: Context, uins: Collection<Long>) = AccountSessionStore.saveHireFriendUins(context, currentActiveUin, uins)
        fun parsePkBlacklistUins(csv: String = prefPkBlacklistUinsCsv): Set<Long> = PetPureCalculations.parsePkBlacklistUins(csv)
        fun loadSavedPkBlacklistUins(context: Context): Set<Long> = AccountSessionStore.loadSavedPkBlacklistUins(context, currentActiveUin)
        fun savePkBlacklistUins(context: Context, uins: Collection<Long>) = AccountSessionStore.savePkBlacklistUins(context, currentActiveUin, uins)

        fun loadCachedHireableFriends(context: Context): List<QQPetDirectBridge.HireableFriend> =
            AccountSessionStore.loadCachedHireableFriends(context, currentActiveUin).also { if (it.isNotEmpty()) cachedHireableFriends = it }.ifEmpty { cachedHireableFriends }
        fun saveCachedHireableFriends(context: Context, list: List<QQPetDirectBridge.HireableFriend>) { cachedHireableFriends = list; AccountSessionStore.saveCachedHireableFriends(context, currentActiveUin, list) }

        fun saveScopedPetId(context: Context, petId: String, runtimeUin: String? = null) {
            AccountSessionStore.saveScopedPetId(context, petId, runtimeUin)
            cachedPetId = petId
            val owner = AccountSessionGuard.extractOwnerUinFromPetId(petId).ifEmpty { runtimeUin?.trim().orEmpty() }
            if (AccountSessionGuard.isValidUin(owner)) currentActiveUin = owner
        }

        fun getLiveRemainingSeconds(): Long = (currentTaskEndTimeMillis - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L

        /** 日志统一出口；context 为历史参数，已无用 */
        @Suppress("UNUSED_PARAMETER")
        fun sendLog(context: Context, message: String) = EngineLog.i(message)

        fun formatLiveStatusText(): String {
            val sec = getLiveRemainingSeconds()
            if (sec <= 0L) return if (currentTaskEndTimeMillis > 0L) { currentTaskEndTimeMillis = 0L; "任务已修毕 · 正在自动结算收益..." } else currentStatusText
            return "$currentTaskTypeName · 剩余 ${PetPureCalculations.formatDuration(sec)}"
        }

    }

    data class PreloadedPetData(
        val schoolDetails: QQPetDirectBridge.SecondMapDetails?,
        val schoolCourses: List<QQPetDirectBridge.SelectEvent>?,
        val workPlaces: QQPetDirectBridge.SecondMapDetails?,
        val workJobs: List<QQPetDirectBridge.SelectEvent>?
    )

    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun updateBridge(newBridge: QQPetDirectBridge) { this.bridge = newBridge }
    fun sendLog(context: Context, message: String) = Companion.sendLog(context, message)

    fun sendReadySignal(context: Context) { sendLog(context, "🟢 [内核连接] 发包引擎与代理已成功接驳就绪") }

    fun startBackgroundLoop(context: Context) {
        if (isLoopRunning) return
        isLoopRunning = true
        loopJob = scope.launch {
            while (isActive && isLoopRunning) {
                val delayMs = try { executeMasterCycle(context) } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "主循环异常: ${t.message}")
                    15000L
                }
                delay(delayMs)
            }
        }
    }

    fun stopBackgroundLoop() {
        isLoopRunning = false
        loopJob?.cancel()
        loopJob = null
    }

    fun wakeUpMasterCycle(context: Context) {
        reloadConfig(context)
        isLoopRunning = true
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive && isLoopRunning) {
                val delayMs = try { executeMasterCycle(context) } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "主循环异常: ${t.message}")
                    15000L
                }
                delay(delayMs)
            }
        }
        sendLog(context, "⚡ [即刻唤醒] 外部指令触发，调度协程已重置并立即巡检")
    }

    suspend fun executeMasterCycle(context: Context): Long {
        reloadConfig(context)
        checkStealthWindows(context)?.let { return it }
        ensureReadyBridge(context)?.let { return it }
        val petId = ensurePetId(context) ?: return 30 * 1000L
        sendLog(context, "🔎 [主循环] 正在查询外出状态")
        val story = queryStoryStatusAwait(petId)
        if (story.code != 0) {
            sendLog(context, "🧭 [主循环] 外出状态没查完 code=${story.code} ${story.bodyNote ?: ""}")
            performMaintenance(context, petId)
            return sleepForMaintenance(context, 8_000L)
        }
        if ((story.remaining ?: 0L) <= 0L || story.storyId.isNullOrEmpty()) {
            sendLog(context, "🧭 [主循环] 状态查询成功，当前没有进行中的外出 子状态=${story.status ?: "无"} 剩余=${story.remaining ?: "无"} story=${story.storyId ?: "无"}")
            if (!story.bodyNote.isNullOrBlank()) sendLog(context, "🧭 [主循环回包] ${story.bodyNote}")
        }
        val hiredSleep = handleOngoingStory(context, petId, story)
        handleStorySettlement(context, petId, story)
        performMaintenance(context, petId)
        val rem = story.remaining ?: 0L
        if (story.code == 0 && rem > 0L) {
            val kind = currentTaskTypeName.ifEmpty { "外出" }
            val outingSleep = if (hiredSleep != null && hiredSleep > 0L) {
                hiredSleep
            } else {
                StealthScheduler.calculateTaskSleepSeconds(rem, prefHumanLikeSleep) * 1000L
            }
            val waitMs = sleepForMaintenance(context, outingSleep)
            val waitNote = if (waitMs < outingSleep) "按照料提前到 ${waitMs / 1000L} 秒后再查" else "${waitMs / 1000L} 秒后再查"
            sendLog(context, "⏳ [任务进行中] 仍在$kind，剩余 ${PetPureCalculations.formatDuration(rem)}，$waitNote，StoryID=${story.storyId ?: "无"}")
            return waitMs
        }
        return dispatchNextTask(context, petId)
    }

    private fun checkStealthWindows(context: Context): Long? {
        if (prefNightSleepMode && StealthScheduler.isNightSilentWindow(true)) {
            val s = StealthScheduler.calculateNightSleepMillis()
            sendLog(context, "🌙 [夜间静默] 深夜防风控窗口中，预计 ${s / 3600000L} 小时后恢复")
            return s
        }
        if (prefScreenOffSilent && !StealthScheduler.isScreenInteractive(context)) {
            val s = StealthScheduler.calculateIdleCycleDelayMillis(prefHumanLikeSleep)
            sendLog(context, "📱 [熄屏静默] 屏幕已熄灭，拟人休眠 ${s / 1000L} 秒直至亮屏")
            return s
        }
        return null
    }

    private fun ensureReadyBridge(context: Context): Long? {
        if (!bridge.isReady) {
            HookEntry.globalBridge?.let { if (it.isReady) bridge = it }
                ?: HookEntry.reconnectBridgeIfAvailable(context)
        }
        if (!bridge.isReady) {
            currentStatusText = "发包代理连接中..."
            sendLog(context, "⏳ [挂起] QQ 内部发包代理尚未就绪，等待 10 秒...")
            return 10000L
        }
        return null
    }

    suspend fun ensurePetId(context: Context): String? {
        var petId = cachedPetId
        if (petId.isNullOrEmpty()) {
            val (_, fetched) = queryOwnPetAwait()
            if (fetched.isNullOrEmpty()) {
                sendLog(context, "❌ [巡检] 获取宠物 ID 失败，30 秒后重试")
                return null
            }
            saveScopedPetId(context, fetched)
            petId = fetched
            sendLog(context, "✅ [巡检] 成功锁定宠物 ID: $petId")
        }
        return petId
    }

    private suspend fun handleOngoingStory(context: Context, petId: String, story: StoryStatusResult): Long? {
        val rem = story.remaining ?: return null
        val total = story.total ?: 0L
        val storyId = story.storyId ?: return null
        if (story.code != 0 || rem <= 0) return null

        currentTaskEndTimeMillis = System.currentTimeMillis() + rem * 1000L
        currentTaskTypeName = when { storyId.startsWith("6100") -> "进阶修习中"; storyId.startsWith("6400") -> "小镇打工中"; else -> "森林探险中" }
        currentStatusText = "$currentTaskTypeName · 剩余 ${PetPureCalculations.formatDuration(rem)}"

        if (lastReportedOngoingStoryId != storyId) {
            lastReportedOngoingStoryId = storyId
            sendLog(context, "⏳ [任务进行中] 小宠正在 $currentTaskTypeName (剩余 ${PetPureCalculations.formatDuration(rem)})，到期后将自动结算")
        }

        val selfUin = currentActiveUin.toLongOrNull() ?: 0L
        val decision = PetHiredRecallTask.evaluateHiredMonitor(
            bridge, petId,
            PetHiredRecallTask.RecallCheckParam(storyId, rem, total, selfUin, prefHiredRecallProgress)
        ) { sendLog(context, it) }
        if (decision.isHired) {
            if (decision.hasRecalled) {
                lastActiveStoryId = null
                lastReportedOngoingStoryId = null
                currentTaskEndTimeMillis = 0L
            }
            if (decision.settled) {
                PetSocialTask.claimOnceAfterSettle(
                    context, bridge, petId, currentActiveUin, enableClaimCoinBag
                ) { sendLog(context, it) }
            }
            return decision.nextSleepMillis
        }
        return null
    }

    private suspend fun handleStorySettlement(context: Context, petId: String, story: StoryStatusResult) {
        val pendingId = lastActiveStoryId ?: story.storyId
        if ((story.remaining ?: 0L) <= 0L && enableSettle && !pendingId.isNullOrEmpty()) {
            sendLog(context, "🎁 [结算] 自动发起收益结算 (StoryID: $pendingId)...")
            val (code, _) = PetHiredRecallTask.settleStoryAwait(bridge, pendingId, petId)
            if (code == 0) {
                sendLog(context, "✅ [结算] 收益结算成功！金币与经验已入账")
                PetSocialTask.claimOnceAfterSettle(
                    context, bridge, petId, currentActiveUin, enableClaimCoinBag
                ) { sendLog(context, it) }
            }
            lastActiveStoryId = null
            lastReportedOngoingStoryId = null
            currentTaskEndTimeMillis = 0L
            delay(1500L)
        }
    }

    private suspend fun performMaintenance(context: Context, petId: String) {
        com.copilot.qqpet.engine.task.PetMaintenanceCoordinator.performMaintenance(context, bridge, petId)
    }

    /** 外出只推迟学业、打工和冒险。照料、结算、福袋、踩踩和 PK 按自己的间隔醒来。 */
    private fun sleepForMaintenance(context: Context, outingSleep: Long): Long {
        val maintenanceWait = com.copilot.qqpet.engine.task.PetMaintenanceCoordinator.millisUntilNextCheck(context)
        return minOf(outingSleep, maintenanceWait).coerceAtLeast(1_000L)
    }

    private suspend fun dispatchNextTask(context: Context, petId: String): Long =
        PetCycleDispatcher.dispatchNextAction(context, bridge, petId)

    fun runAction(context: Context, action: String) {
        scope.launch {
            val petId = ensurePetId(context) ?: return@launch
            if (action == "cycle") {
                executeMasterCycle(context)
            } else if (action == "query_work_places" || action == "query_account_status") {
                preloadAccountData(context)
            } else {
                PetCycleDispatcher.executeAction(context, bridge, petId, action)
            }
        }
    }

    suspend fun queryOwnPetAwait(timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bridge.queryOwnPet { code, petId, _ -> if (cont.isActive) cont.resume(Pair(code, petId)) }
            }
        } ?: Pair(-99, null)

    suspend fun queryStoryStatusAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): StoryStatusResult =
        PetWorkTask.queryStoryStatusAwait(bridge, petId, timeoutMs)

    suspend fun startAdventureAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bridge.startAdventure(petId) { code, storyId, _, _ -> if (cont.isActive) cont.resume(Pair(code, storyId)) }
            }
        } ?: Pair(-99, null)



    /** 预加载学园 / 职业小镇数据到进程内缓存 */
    fun preloadAccountData(context: Context) {
        if (cachedWorkPlaces != null && cachedSchoolDetails != null) return
        scope.launch {
            val petId = ensurePetId(context) ?: return@launch
            preloadAccountDataAwait(petId)
        }
    }

    fun verifyAndSyncAccountSession(context: Context): String {
        val liveUin = bridge.getCurrentRuntimeUin()
        if (AccountSessionGuard.isValidUin(liveUin) && liveUin != currentActiveUin) {
            clearAccountBoundMemoryCache()
            currentActiveUin = liveUin
        }
        return liveUin
    }

    suspend fun queryPetAttributesAwait(petId: String, isSelf: Boolean = true): QQPetDirectBridge.PetAttributes? =
        PetCareTask.queryPetAttributesAwait(bridge, petId, isSelf)

    suspend fun querySecondMapInfoDetailsAwait(eventType: Long, petId: String): QQPetDirectBridge.SecondMapDetails =
        PetWorkTask.querySecondMapInfoDetailsAwait(bridge, eventType, petId)

    suspend fun querySelectEventsAwait(page: Long, petId: String, schoolStage: Int = 0, careerType: Int = 0): Pair<Int, List<QQPetDirectBridge.SelectEvent>> =
        PetWorkTask.querySelectEventsAwait(bridge, page, petId, schoolStage, careerType)

    suspend fun preloadAccountDataAwait(petId: String): PreloadedPetData {
        val details = querySecondMapInfoDetailsAwait(6100L, petId).also { if (it.code == 0) cachedSchoolDetails = it }
        val targetStage = if (details.currentStage > 0) details.currentStage else 3
        querySelectEventsAwait(6100L, petId, schoolStage = targetStage).second.let { if (it.isNotEmpty()) cachedSchoolCourses = it }
        querySecondMapInfoDetailsAwait(6400L, petId).let { if (it.code == 0) cachedWorkPlaces = it }
        querySelectEventsAwait(6400L, petId, careerType = 3).second.let { if (it.isNotEmpty()) cachedWorkJobs = it }
        return PreloadedPetData(cachedSchoolDetails, cachedSchoolCourses, cachedWorkPlaces, cachedWorkJobs)
    }

    suspend fun fetchAllHireableFriendsAwait(context: Context, enrichSelectedAndTop: Boolean = true): List<QQPetDirectBridge.HireableFriend> =
        PetWorkTask.fetchAllHireableFriendsAwait(context, bridge, currentActiveUin, enrichSelectedAndTop)

    suspend fun enrichFriendDetailsAwait(friend: QQPetDirectBridge.HireableFriend): QQPetDirectBridge.HireableFriend =
        PetWorkTask.enrichFriendDetailsAwait(bridge, friend)

    suspend fun fetchLikeListAwait(extra: String = ""): Pair<Int, List<QQPetDirectBridge.LikeMember>> =
        PetSocialTask.fetchLikeListAwait(bridge, extra)

    fun reloadConfig(context: Context) {
        try {
            val p = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            enableStudy = p.getBoolean("key_study", true)
            enableWork = p.getBoolean("key_work", true)
            enableCare = p.getBoolean("key_care", true)
            enableAdventure = p.getBoolean("key_adventure", false)
            enableSettle = p.getBoolean("key_settle", true)
            enableLikeBack = p.getBoolean(PreferencesHelper.KEY_LIKE_BACK, true)
            enableClaimCoinBag = p.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, true)
            enableFatigueToAdventure = p.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
            enableAutoPk = p.getBoolean(PreferencesHelper.KEY_AUTO_PK, false)
            prefHiredRecallProgress = p.getInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72)
            prefNightSleepMode = p.getBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
            prefScreenOffSilent = p.getBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
            prefHumanLikeSleep = p.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
            prefStudyMode = p.getInt("key_study_mode", 0)
            prefWorkMode = p.getInt("key_work_mode", 0)
            prefCustomSchoolStage = p.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
            prefCustomCourseSubject = p.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
            prefCustomCourseDuration = p.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
            prefCustomWorkType = p.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
            prefCustomWorkDuration = p.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
            prefCareEnergyThreshold = p.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
            prefCareCleanThreshold = p.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
            prefHideQQSettingEntry = p.getBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
            prefDebugLog = p.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
            // 调试开关即刻生效（原先要等 QQ 进程重启才会重新读取）
            com.copilot.qqpet.hook.HookLog.isDebugEnabled = prefDebugLog
            enableHireFriend = p.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
            prefHireFriendUinsCsv = p.getString(PreferencesHelper.KEY_HIRE_FRIEND_UINS, "") ?: ""
            enableFriendCare = p.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
            prefFriendCareEnergyThreshold = p.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
            prefFriendCareCleanThreshold = p.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
            prefPkBlacklistUinsCsv = p.getString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, "") ?: ""
            enableActiveVisit = p.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, true)
            prefActiveVisitFriends = p.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, true)
            prefActiveVisitStrangers = p.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, true)
            prefActiveVisitDailyLimit = p.getInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, 20)
        } catch (_: Throwable) {}
    }

    /** 打印配置摘要 */
    fun logConfigSummary(context: Context) {
        val hireCount = prefHireFriendUinsCsv.split(',').count { it.isNotBlank() }
        sendLog(
            context,
            "⚙️ 配置生效 学习:${onOff(enableStudy)} 打工:${onOff(enableWork)} 照顾:${onOff(enableCare)} 冒险:${onOff(enableAdventure)} " +
                "结算:${onOff(enableSettle)} 雇佣:${onOff(enableHireFriend)}(${hireCount}人) 好友照料:${onOff(enableFriendCare)} " +
                "回踩:${onOff(enableLikeBack)} 福袋:${onOff(enableClaimCoinBag)} 串门:${onOff(enableActiveVisit)} " +
                "PK:${onOff(enableAutoPk)} 疲惫转探险:${onOff(enableFatigueToAdventure)}"
        )
        val placeTitle = cachedWorkPlaces?.stages?.find { it.stage == prefCustomWorkType }?.title
        sendLog(
            context,
            "⚙️ 调度明细 学园:${UiDescUtils.schoolStageLabel(prefCustomSchoolStage)} " +
                "科目:${UiDescUtils.courseSubjectLabel(prefCustomCourseSubject)} " +
                "课时:${UiDescUtils.courseDurationLabel(prefCustomCourseDuration)} " +
                "场所:${UiDescUtils.workTypeLabel(prefCustomWorkType, placeTitle)} " +
                "工时:${UiDescUtils.workDurationLabel(prefCustomWorkDuration)} " +
                "体力≤$prefCareEnergyThreshold 清洁≤$prefCareCleanThreshold " +
                "好友体力≤$prefFriendCareEnergyThreshold 好友清洁≤$prefFriendCareCleanThreshold " +
                "召回:${if (prefHiredRecallProgress > 0) "${prefHiredRecallProgress}%" else "关"} " +
                "拟人:${onOff(prefHumanLikeSleep)} 夜间静默:${onOff(prefNightSleepMode)} 熄屏静默:${onOff(prefScreenOffSilent)}"
        )
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "开" else "关"

}

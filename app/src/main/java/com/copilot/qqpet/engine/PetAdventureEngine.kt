package com.copilot.qqpet.engine

import android.content.Context
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.RuntimeSwitches
import com.copilot.qqpet.engine.config.TimeConfigManager
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

        @Volatile var masterEnabled = false
        @Volatile var enableStudy = false

        @Volatile var enableWork = false
        @Volatile var enableCare = false

        @Volatile var enableAdventure = false
        @Volatile var enableSettle = false

        @Volatile var enableLikeBack = false
        @Volatile var enableClaimCoinBag = false

        @Volatile var enableFatigueToAdventure = false
        @Volatile var enableAutoPk = false

        @Volatile var lastPkTimeMillis = 0L
        @Volatile var pkCooldownMillis = 60 * 1000L

        @Volatile var prefHumanLikeSleep = true
        @Volatile var prefNightSleepMode = true

        @Volatile var prefScreenOffSilent = true
        @Volatile var prefHideQQSettingEntry = false

        @Volatile var prefDebugLog = false
        @Volatile var enableHireFriend = false

        @Volatile var prefHireFriendUinsCsv: String = ""
        @Volatile var prefPkBlacklistUinsCsv: String = ""

        @Volatile var prefHiredRecallProgress = 72
        @Volatile var enableActiveVisit = false

        @Volatile var prefActiveVisitFriends = true
        @Volatile var prefActiveVisitStrangers = true

        @Volatile var prefActiveVisitDailyLimit = 20
        @Volatile var lastActiveVisitTimeMillis = 0L

        @Volatile var cachedHireableFriends: List<QQPetDirectBridge.HireableFriend> = emptyList()
        @Volatile var enableFriendCare = false

        @Volatile var prefFriendCareEnergyThreshold = 60
        @Volatile var prefFriendCareCleanThreshold = 60

        @Volatile var currentStatusText = "全自动守护中 · 一刻不停三维轮转"
        @Volatile var currentTaskEndTimeMillis = 0L

        @Volatile var currentTaskTypeName = "进阶修习中"
        @Volatile var roundRobinCursor = 0

        @Volatile var studyAttributeCursor = 0
        @Volatile var workJobCursor = 0

        @Volatile var prefStudyMode = 0
        @Volatile var prefWorkMode = 0

        @Volatile var prefCustomSchoolStage = 0
        @Volatile var prefCustomCourseSubject = 0

        @Volatile var prefCustomCourseDuration = 0
        @Volatile var prefCustomWorkType = 0

        @Volatile var prefCustomWorkDuration = 0
        @Volatile var prefCareEnergyThreshold = 60

        @Volatile var prefCareCleanThreshold = 60
        @Volatile var lastCareTimeMillis = 0L

        @Volatile var lastLikeBackTimeMillis = 0L
        @Volatile var lastCoinBagTimeMillis = 0L

        @Volatile var lastOwnPetCheckMillis = 0L
        @Volatile var cachedSchoolDetails: QQPetDirectBridge.SecondMapDetails? = null

        @Volatile var cachedSchoolCourses: List<QQPetDirectBridge.SelectEvent>? = null
        @Volatile var cachedWorkPlaces: QQPetDirectBridge.SecondMapDetails? = null

        @Volatile var cachedWorkJobs: List<QQPetDirectBridge.SelectEvent>? = null
        @Volatile var learnedStudySubEvent: Long? = null

        @Volatile var learnedStudyName: String? = null
        @Volatile var learnedWorkSubEvent: Long? = null

        @Volatile var learnedWorkName: String? = null

        fun clearAccountBoundMemoryCache(context: Context) {
            AccountSessionStore.clearAccountBoundMemoryCache(context); cachedSchoolDetails = null; cachedWorkPlaces =
                null
        }

        fun saveScopedPetId(context: Context, petId: String, runtimeUin: String? = null) {
            AccountSessionStore.saveScopedPetId(context, petId, runtimeUin)
            cachedPetId = petId
            val owner = AccountSessionGuard.extractOwnerUinFromPetId(petId).ifEmpty { runtimeUin?.trim().orEmpty() }
            if (AccountSessionGuard.isValidUin(owner)) currentActiveUin = owner
        }

        fun getLiveRemainingSeconds(): Long =
            (currentTaskEndTimeMillis - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L

        /** 日志统一出口；context 为历史参数，已无用 */
        @Suppress("UNUSED_PARAMETER")
        fun sendLog(context: Context, message: String) = EngineLog.i(message)

        fun formatLiveStatusText(): String {
            if (!masterEnabled) return "总开关未开启 · 模块待命中"
            val sec = getLiveRemainingSeconds()
            if (sec <= 0L) return if (currentTaskEndTimeMillis > 0L) {
                currentTaskEndTimeMillis = 0L; "任务已修毕 · 正在自动结算收益..."
            } else currentStatusText
            return "$currentTaskTypeName · 剩余 ${PetPureCalculations.formatDuration(sec)}"
        }

    }

    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun updateBridge(newBridge: QQPetDirectBridge) {
        this.bridge = newBridge
    }

    fun sendLog(context: Context, message: String) = Companion.sendLog(context, message)

    fun sendReadySignal(context: Context) {
        sendLog(context, "🟢 [内核连接] 发包引擎与代理已成功接驳就绪")
    }

    fun startBackgroundLoop(context: Context) {
        reloadConfig(context)
        if (!masterEnabled) {
            sendLog(context, "🛑 [总开关] 未开启，主循环不启动（默认关闭，请在设置页打开总开关）")
            return
        }
        if (isLoopRunning) return
        isLoopRunning = true
        launchLoop(context)
    }

    fun stopBackgroundLoop() {
        isLoopRunning = false
        loopJob?.cancel()
        loopJob = null
    }

    fun wakeUpMasterCycle(context: Context) {
        reloadConfig(context)
        if (!masterEnabled) {
            stopBackgroundLoop()
            sendLog(context, "🛑 [总开关] 已关闭，调度已停止（配置已保存）")
            return
        }
        isLoopRunning = true
        launchLoop(context)
        sendLog(context, "⚡ [即刻唤醒] 外部指令触发，调度协程已重置并立即巡检")
    }

    private fun launchLoop(context: Context) {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive && isLoopRunning) {
                val delayMs = try {
                    executeMasterCycle(context)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "主循环异常: ${t.message}")
                    15000L
                }
                delay(delayMs)
            }
        }
    }

    suspend fun executeMasterCycle(context: Context): Long {
        reloadConfig(context)
        if (!masterEnabled) {
            sendLog(context, "🛑 [总开关] 未开启，本轮巡检跳过")
            return 60 * 1000L
        }
        checkStealthWindows(context)?.let { return it }
        // 桥接就绪检查：优先复用全局新桥，未就绪则尝试重连
        if (!bridge.isReady) {
            HookEntry.globalBridge?.let { if (it.isReady) bridge = it }
                ?: HookEntry.reconnectBridgeIfAvailable(context)
        }
        if (!bridge.isReady) {
            currentStatusText = "发包代理连接中..."
            sendLog(context, "⏳ [挂起] QQ 内部发包代理尚未就绪，等待 10 秒...")
            return 10000L
        }
        val petId = ensurePetId(context) ?: return 30 * 1000L
        sendLog(context, "🔎 [主循环] 正在查询外出状态")
        val story = queryStoryStatusAwait(petId)
        if (story.code == 0) {
            // 从服务器回包动态校准任务时长，替代纯硬编码
            TimeConfigManager.extractAndConfigureDuration(story)
        }
        if (story.code != 0) {
            sendLog(context, "🧭 [主循环] 外出状态没查完 code=${story.code} ${story.bodyNote ?: ""}")
            performMaintenance(context, petId)
            return sleepForMaintenance(context, 8_000L)
        }
        if ((story.remaining ?: 0L) <= 0L || story.storyId.isNullOrEmpty()) {
            sendLog(
                context,
                "🧭 [主循环] 状态查询成功，当前没有进行中的外出 子状态=${story.status ?: "无"} 剩余=${story.remaining ?: "无"} story=${story.storyId ?: "无"}"
            )
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
            val waitNote =
                if (waitMs < outingSleep) "按照料提前到 ${waitMs / 1000L} 秒后再查" else "${waitMs / 1000L} 秒后再查"
            sendLog(
                context,
                "⏳ [任务进行中] 仍在$kind，剩余 ${PetPureCalculations.formatDuration(rem)}，$waitNote，StoryID=${story.storyId ?: "无"}"
            )
            return waitMs
        }
        return dispatchNextTask(context, petId)
    }

    private fun checkStealthWindows(context: Context): Long? =
        EngineGates.checkStealthWindows(context)

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

    private suspend fun handleOngoingStory(context: Context, petId: String, story: StoryStatusResult): Long? =
        PetStoryHandlers.handleOngoingStory(context, bridge, petId, story)

    private suspend fun handleStorySettlement(context: Context, petId: String, story: StoryStatusResult) =
        PetStoryHandlers.handleStorySettlement(context, bridge, petId, story)

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
        if (!masterEnabled) {
            sendLog(context, "🛑 [总开关] 未开启，忽略手动指令：$action")
            return
        }
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
        PetEngineApi.queryOwnPetAwait(bridge, timeoutMs)

    suspend fun queryStoryStatusAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): StoryStatusResult =
        PetWorkTask.queryStoryStatusAwait(bridge, petId, timeoutMs)

    suspend fun startAdventureAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        PetEngineApi.startAdventureAwait(bridge, petId, timeoutMs)

    /** 预加载学园 / 职业小镇数据到进程内缓存 */
    fun preloadAccountData(context: Context) {
        if (cachedWorkPlaces != null && cachedSchoolDetails != null) return
        scope.launch {
            val petId = ensurePetId(context) ?: return@launch
            PetPreloader.preloadAccountDataAwait(bridge, petId)
        }
    }

    suspend fun preloadAccountDataAwait(petId: String) = PetPreloader.preloadAccountDataAwait(bridge, petId)

    fun verifyAndSyncAccountSession(context: Context): String {
        val liveUin = bridge.getCurrentRuntimeUin()
        if (AccountSessionGuard.isValidUin(liveUin) && liveUin != currentActiveUin) {
            clearAccountBoundMemoryCache(context)
            currentActiveUin = liveUin
        }
        return liveUin
    }

    suspend fun queryPetAttributesAwait(petId: String, isSelf: Boolean = true): QQPetDirectBridge.PetAttributes? =
        PetEngineApi.queryPetAttributesAwait(bridge, petId, isSelf)

    suspend fun querySelectEventsAwait(
        page: Long,
        petId: String,
        schoolStage: Int = 0,
        careerType: Int = 0
    ): Pair<Int, List<QQPetDirectBridge.SelectEvent>> =
        PetEngineApi.querySelectEventsAwait(bridge, page, petId, schoolStage, careerType)

    suspend fun fetchAllHireableFriendsAwait(
        context: Context,
        enrichSelectedAndTop: Boolean = true
    ): List<QQPetDirectBridge.HireableFriend> =
        PetEngineApi.fetchAllHireableFriendsAwait(bridge, context, currentActiveUin, enrichSelectedAndTop)

    suspend fun enrichFriendDetailsAwait(friend: QQPetDirectBridge.HireableFriend): QQPetDirectBridge.HireableFriend =
        PetEngineApi.enrichFriendDetailsAwait(bridge, friend)

    suspend fun fetchLikeListAwait(extra: String = ""): Pair<Int, List<QQPetDirectBridge.LikeMember>> =
        PetEngineApi.fetchLikeListAwait(bridge, extra)

    fun reloadConfig(context: Context) {
        EngineConfigLoader.reload(context, onDebugLogChanged = { prefDebugLog = it })
    }

    fun logConfigSummary(context: Context) = EngineConfigLoader.logSummary(context)
}

package com.copilot.qqpet.hook.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.engine.WakeLockHelper
import com.copilot.qqpet.hook.HookLog
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.util.UiDescUtils

/**
 * 负责与主界面及外部交互的跨进程广播接收与指令分发 (IPC 通道)
 */
object EngineActionReceiver {

    private const val TAG = "QQPetIPC"
    const val MODULE_PACKAGE = "io.github.congsmile.qqpet"
    const val ACTION_TRIGGER_ADVENTURE = "io.github.congsmile.qqpet.ACTION_TRIGGER_ADVENTURE"
    const val ACTION_TRIGGER_ACTION = "io.github.congsmile.qqpet.ACTION_TRIGGER_ACTION"
    const val ACTION_UPDATE_CONFIG = "io.github.congsmile.qqpet.ACTION_UPDATE_CONFIG"
    const val ACTION_PING = "io.github.congsmile.qqpet.ACTION_PING"
    const val ACTION_PONG = "io.github.congsmile.qqpet.ACTION_PONG"

    @Volatile
    private var isRegistered = false
    private var lastContext: Context? = null
    private var receiver: BroadcastReceiver? = null

    fun register(context: Context) {
        if (isRegistered) return
        lastContext = context
        val filter = IntentFilter().apply {
            addAction(ACTION_TRIGGER_ADVENTURE)
            addAction(ACTION_TRIGGER_ACTION)
            addAction(ACTION_UPDATE_CONFIG)
            addAction(ACTION_PING)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PING -> handlePing(ctx)
                    ACTION_UPDATE_CONFIG -> handleUpdateConfig(ctx, intent)
                    ACTION_TRIGGER_ACTION -> handleTriggerAction(ctx, intent)
                    ACTION_TRIGGER_ADVENTURE -> handleTriggerAdventure(ctx)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        isRegistered = true
    }

    fun unregister() {
        if (isRegistered && receiver != null) {
            try { lastContext?.unregisterReceiver(receiver) } catch (_: Throwable) {}
            receiver = null
            isRegistered = false
        }
    }

    fun sendPong(context: Context, reason: String) {
        try {
            val pongIntent = Intent(ACTION_PONG).apply {
                setPackage(MODULE_PACKAGE)
                putExtra("extra_time", System.currentTimeMillis())
                putExtra("extra_reason", reason)
                putExtra("extra_engine_ready", HookEntry.globalBridge?.isReady == true)
                putExtra("extra_loop_running", PetAdventureEngine.isLoopRunning)
            }
            context.sendBroadcast(pongIntent)
        } catch (t: Throwable) {
            HookLog.log(TAG, "回传 Pong 异常: ${t.message}")
        }
    }

    private fun handlePing(ctx: Context) {
        HookLog.log(TAG, "收到伴侣 Ping 探测广播，立即回传 Pong 确认激活！")
        if (HookEntry.globalBridge?.isReady != true) {
            HookEntry.reconnectBridgeIfAvailable(ctx)
        }
        sendPong(ctx, "收到Ping")
    }

    private fun handleTriggerAction(ctx: Context, intent: Intent) {
        val action = intent.getStringExtra(PetAdventureEngine.EXTRA_ACTION) ?: "cycle"
        HookLog.log(TAG, "收到动作指令: $action")
        if (HookEntry.globalBridge?.isReady != true) {
            HookEntry.reconnectBridgeIfAvailable(ctx)
        }
        if (action == "query_work_places" || action == "query_account_status") {
            HookEntry.globalEngine?.preloadAndBroadcastAccountStatus(ctx)
        } else {
            HookEntry.globalEngine?.runAction(ctx, action)
        }
        sendPong(ctx, "执行指令:$action")
    }

    private fun handleTriggerAdventure(ctx: Context) {
        HookLog.log(TAG, "收到一键测试冒险探索指令！")
        if (HookEntry.globalBridge?.isReady != true) {
            HookEntry.reconnectBridgeIfAvailable(ctx)
        }
        HookEntry.globalEngine?.runAction(ctx, "adventure")
        sendPong(ctx, "触发冒险")
    }

    private fun handleUpdateConfig(ctx: Context, intent: Intent) {
        val prefs = ctx.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val study = if (intent.hasExtra("extra_study")) intent.getBooleanExtra("extra_study", true) else prefs.getBoolean("key_study", true)
        val work = if (intent.hasExtra("extra_work")) intent.getBooleanExtra("extra_work", true) else prefs.getBoolean("key_work", true)
        val care = if (intent.hasExtra("extra_care")) intent.getBooleanExtra("extra_care", true) else prefs.getBoolean("key_care", true)
        val adv = if (intent.hasExtra("extra_adventure")) intent.getBooleanExtra("extra_adventure", false) else prefs.getBoolean("key_adventure", false)
        val settle = if (intent.hasExtra("extra_settle")) intent.getBooleanExtra("extra_settle", true) else prefs.getBoolean("key_settle", true)
        val likeBack = if (intent.hasExtra("extra_like_back")) intent.getBooleanExtra("extra_like_back", true) else prefs.getBoolean(PreferencesHelper.KEY_LIKE_BACK, true)
        val claimBag = if (intent.hasExtra("extra_claim_coinbag")) intent.getBooleanExtra("extra_claim_coinbag", true) else prefs.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, true)
        val fatigueToAdv = if (intent.hasExtra("extra_fatigue_to_adventure")) intent.getBooleanExtra("extra_fatigue_to_adventure", true) else prefs.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
        val studyMode = if (intent.hasExtra("extra_study_mode")) intent.getIntExtra("extra_study_mode", 0) else prefs.getInt("key_study_mode", 0)
        val workMode = if (intent.hasExtra("extra_work_mode")) intent.getIntExtra("extra_work_mode", 0) else prefs.getInt("key_work_mode", 0)
        val schoolStage = if (intent.hasExtra("extra_school_stage")) intent.getIntExtra("extra_school_stage", 0) else prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val courseSubject = if (intent.hasExtra("extra_course_subject")) intent.getIntExtra("extra_course_subject", 0) else prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
        val courseDuration = if (intent.hasExtra("extra_course_duration")) intent.getIntExtra("extra_course_duration", 0) else prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
        val workType = if (intent.hasExtra("extra_work_type")) intent.getIntExtra("extra_work_type", 0) else prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val workDuration = if (intent.hasExtra("extra_work_duration")) intent.getIntExtra("extra_work_duration", 0) else prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
        val careEnergy = if (intent.hasExtra("extra_care_energy_threshold")) intent.getIntExtra("extra_care_energy_threshold", 60) else prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
        val careClean = if (intent.hasExtra("extra_care_clean_threshold")) intent.getIntExtra("extra_care_clean_threshold", 60) else prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
        val humanLikeSleep = if (intent.hasExtra("extra_human_like_sleep")) intent.getBooleanExtra("extra_human_like_sleep", true) else prefs.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
        val nightSleep = if (intent.hasExtra("extra_night_sleep_mode")) intent.getBooleanExtra("extra_night_sleep_mode", true) else prefs.getBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
        val screenOffSilent = if (intent.hasExtra("extra_screen_off_silent")) intent.getBooleanExtra("extra_screen_off_silent", true) else prefs.getBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
        val hideSetting = if (intent.hasExtra("extra_hide_qq_setting_entry")) intent.getBooleanExtra("extra_hide_qq_setting_entry", false) else prefs.getBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
        val debugLog = if (intent.hasExtra("extra_debug_log")) intent.getBooleanExtra("extra_debug_log", false) else prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
        val hireFriend = if (intent.hasExtra("extra_hire_friend_enabled")) intent.getBooleanExtra("extra_hire_friend_enabled", true) else prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
        val hireUinsCsv = if (intent.hasExtra("extra_hire_friend_uins")) (intent.getStringExtra("extra_hire_friend_uins") ?: "") else (prefs.getString(PreferencesHelper.KEY_HIRE_FRIEND_UINS, "") ?: "")
        val friendCareEnabled = if (intent.hasExtra("extra_friend_care_enabled")) intent.getBooleanExtra("extra_friend_care_enabled", false) else prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
        val friendCareEnergy = if (intent.hasExtra("extra_friend_care_energy_threshold")) intent.getIntExtra("extra_friend_care_energy_threshold", 60) else prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
        val friendCareClean = if (intent.hasExtra("extra_friend_care_clean_threshold")) intent.getIntExtra("extra_friend_care_clean_threshold", 60) else prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
        val autoPk = if (intent.hasExtra("extra_auto_pk")) intent.getBooleanExtra("extra_auto_pk", false) else prefs.getBoolean(PreferencesHelper.KEY_AUTO_PK, false)
        val pkBlacklistUinsCsv = if (intent.hasExtra("extra_pk_blacklist_uins")) (intent.getStringExtra("extra_pk_blacklist_uins") ?: "") else (prefs.getString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, "") ?: "")
        val hiredRecall = if (intent.hasExtra("extra_hired_recall_progress")) intent.getIntExtra("extra_hired_recall_progress", 72) else prefs.getInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72)
        val activeVisit = if (intent.hasExtra("extra_active_visit")) intent.getBooleanExtra("extra_active_visit", true) else prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, true)
        val activeVisitFriends = if (intent.hasExtra("extra_active_visit_friends")) intent.getBooleanExtra("extra_active_visit_friends", true) else prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, true)
        val activeVisitStrangers = if (intent.hasExtra("extra_active_visit_strangers")) intent.getBooleanExtra("extra_active_visit_strangers", true) else prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, true)
        val activeVisitDailyLimit = if (intent.hasExtra("extra_active_visit_daily_limit")) intent.getIntExtra("extra_active_visit_daily_limit", 20) else prefs.getInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, 20)
        val disableTinker = if (intent.hasExtra("extra_disable_tinker_patch")) intent.getBooleanExtra("extra_disable_tinker_patch", false) else prefs.getBoolean(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false)

        HookEntry.globalEngine?.updateConfig(
            study, work, care, adv, settle, likeBack, claimBag, fatigueToAdv, studyMode, workMode, schoolStage, courseSubject,
            courseDuration, workType, workDuration, careEnergy, careClean, humanLikeSleep, nightSleep, screenOffSilent, hideSetting,
            debugLog, hireFriend, hireUinsCsv, friendCareEnabled, friendCareEnergy, friendCareClean, autoPk, pkBlacklistUinsCsv,
            hiredRecall, activeVisit, activeVisitFriends, activeVisitStrangers, activeVisitDailyLimit
        )
        try {
            editor.putBoolean("key_study", study).putBoolean("key_work", work).putBoolean("key_care", care).putBoolean("key_adventure", adv)
            editor.putBoolean("key_settle", settle).putBoolean(PreferencesHelper.KEY_LIKE_BACK, likeBack).putBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, claimBag)
            editor.putBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, fatigueToAdv).putInt("key_study_mode", studyMode).putInt("key_work_mode", workMode)
            editor.putInt(PreferencesHelper.KEY_SCHOOL_STAGE, schoolStage).putInt(PreferencesHelper.KEY_COURSE_SUBJECT, courseSubject)
            editor.putInt(PreferencesHelper.KEY_COURSE_DURATION, courseDuration).putInt(PreferencesHelper.KEY_WORK_TYPE, workType)
            editor.putInt(PreferencesHelper.KEY_WORK_DURATION, workDuration).putInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, careEnergy)
            editor.putInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, careClean).putBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, humanLikeSleep)
            editor.putBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, nightSleep).putBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, screenOffSilent)
            editor.putBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, hideSetting).putBoolean(PreferencesHelper.KEY_DEBUG_LOG, debugLog)
            editor.putBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, hireFriend).putString(PreferencesHelper.KEY_HIRE_FRIEND_UINS, hireUinsCsv)
            editor.putBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, friendCareEnabled).putInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, friendCareEnergy)
            editor.putInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, friendCareClean).putBoolean(PreferencesHelper.KEY_AUTO_PK, autoPk)
            editor.putString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, pkBlacklistUinsCsv).putInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, hiredRecall)
            editor.putBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, activeVisit).putBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, activeVisitFriends)
            editor.putBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, activeVisitStrangers).putInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, activeVisitDailyLimit)
            editor.putBoolean(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, disableTinker).commit()
        } catch (_: Throwable) {}
        HookLog.log(TAG, "跨进程配置更新完成并写入偏好存储")
        val placeTitle = PetAdventureEngine.cachedWorkPlaces?.stages?.find { it.stage == workType }?.title
        val hireCount = hireUinsCsv.split(',').count { it.isNotBlank() }
        HookEntry.globalEngine?.sendLog(
            ctx,
            "⚙️ [配置已同步到 QQ 调度] 学习=${onOff(study)}，打工=${onOff(work)}，照顾=${onOff(care)}，冒险=${onOff(adv)}，结算=${onOff(settle)}，雇佣好友=${onOff(hireFriend)}（${hireCount}人），好友照顾=${onOff(friendCareEnabled)}，回踩=${onOff(likeBack)}，福袋=${onOff(claimBag)}，串门=${onOff(activeVisit)}，自动PK=${onOff(autoPk)}，疲惫转探险=${onOff(fatigueToAdv)}"
        )
        HookEntry.globalEngine?.sendLog(
            ctx,
            "⚙️ [调度明细] 学园=${UiDescUtils.schoolStageLabel(schoolStage)}，科目=${UiDescUtils.courseSubjectLabel(courseSubject)}，课时=${UiDescUtils.courseDurationLabel(courseDuration)}，打工场所=${UiDescUtils.workTypeLabel(workType, placeTitle)}，工时=${UiDescUtils.workDurationLabel(workDuration)}，体力≤$careEnergy，清洁≤$careClean，好友体力≤$friendCareEnergy，好友清洁≤$friendCareClean，召回=${if (hiredRecall > 0) "${hiredRecall}%" else "关闭"}，拟人休眠=${onOff(humanLikeSleep)}，夜间静默=${onOff(nightSleep)}，熄屏静默=${onOff(screenOffSilent)}"
        )
        WakeLockHelper.wakeUpImmediately()
        HookEntry.globalEngine?.wakeUpMasterCycle(ctx)
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "开" else "关"
}

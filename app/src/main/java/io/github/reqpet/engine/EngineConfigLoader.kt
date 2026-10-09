package io.github.reqpet.engine

import android.content.Context
import io.github.reqpet.RuntimeSwitches
import io.github.reqpet.hook.HookLog
import io.github.reqpet.ui.PreferencesHelper
import io.github.reqpet.ui.util.UiDescUtils

/**
 * 配置装载器：从单一真源 qqpet_inproc_prefs 读取全部开关并写入引擎伴生状态
 *
 * 独立成对象以保持 PetAdventureEngine 聚焦调度逻辑；字段写入通过调用方回调完成
 */
internal object EngineConfigLoader {

    fun reload(context: Context, onDebugLogChanged: (Boolean) -> Unit) {
        try {
            val p = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            PetAdventureEngine.masterEnabled = p.getBoolean(PreferencesHelper.KEY_MASTER_ENABLED, false)
            RuntimeSwitches.masterEnabled = PetAdventureEngine.masterEnabled
            PetAdventureEngine.enableStudy = p.getBoolean("key_study", false)
            PetAdventureEngine.enableWork = p.getBoolean("key_work", false)
            PetAdventureEngine.enableCare = p.getBoolean("key_care", false)
            PetAdventureEngine.enableAdventure = p.getBoolean("key_adventure", false)
            val hasAnyTask =
                PetAdventureEngine.enableStudy || PetAdventureEngine.enableWork || PetAdventureEngine.enableAdventure
            PetAdventureEngine.enableSettle = p.getBoolean("key_settle", true) || hasAnyTask
            PetAdventureEngine.enableLikeBack = p.getBoolean(PreferencesHelper.KEY_LIKE_BACK, false)
            PetAdventureEngine.enableClaimCoinBag = p.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, false)
            PetAdventureEngine.enableFatigueToAdventure =
                p.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, false)
            PetAdventureEngine.enableAutoPk = p.getBoolean(PreferencesHelper.KEY_AUTO_PK, false)
            PetAdventureEngine.prefHiredRecallProgress = p.getInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72)
            PetAdventureEngine.prefNightSleepMode = p.getBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
            PetAdventureEngine.prefScreenOffSilent = p.getBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
            PetAdventureEngine.prefHumanLikeSleep = p.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
            PetAdventureEngine.prefStudyMode = p.getInt("key_study_mode", 0)
            PetAdventureEngine.prefWorkMode = p.getInt("key_work_mode", 0)
            PetAdventureEngine.prefCustomSchoolStage = p.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
            PetAdventureEngine.prefCustomCourseSubject = p.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
            PetAdventureEngine.prefCustomCourseDuration = p.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
            PetAdventureEngine.prefCustomWorkType = p.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
            PetAdventureEngine.prefCustomWorkDuration = p.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
            PetAdventureEngine.prefCareEnergyThreshold = p.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
            PetAdventureEngine.prefCareCleanThreshold = p.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
            val debugLog = p.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
            // 调试开关即刻生效（原先要等 QQ 进程重启才会重新读取）
            HookLog.isDebugEnabled = debugLog
            onDebugLogChanged(debugLog)
            RuntimeDiagnostics.detailedMode = try {
                p.getBoolean(PreferencesHelper.KEY_DIAGNOSTICS_DETAIL, false)
            } catch (_: Throwable) {
                false
            }
            PetAdventureEngine.enableHireFriend = p.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, false)
            PetAdventureEngine.prefHireFriendUinsCsv =
                p.getString(PreferencesHelper.KEY_HIRE_FRIEND_UINS, "") ?: ""
            PetAdventureEngine.enableFriendCare = p.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
            PetAdventureEngine.prefFriendCareEnergyThreshold =
                p.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
            PetAdventureEngine.prefFriendCareCleanThreshold =
                p.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
            PetAdventureEngine.prefPkBlacklistUinsCsv =
                p.getString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, "") ?: ""
            PetAdventureEngine.enableActiveVisit = p.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, false)
            PetAdventureEngine.prefActiveVisitFriends =
                p.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, true)
            PetAdventureEngine.prefActiveVisitStrangers =
                p.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, true)
            PetAdventureEngine.prefActiveVisitDailyLimit =
                p.getInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, 20)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "开" else "关"

    fun logSummary(context: Context) {
        val masterEnabled = PetAdventureEngine.masterEnabled
        val enableStudy = PetAdventureEngine.enableStudy
        val enableWork = PetAdventureEngine.enableWork
        val enableCare = PetAdventureEngine.enableCare
        val enableAdventure = PetAdventureEngine.enableAdventure
        val enableSettle = PetAdventureEngine.enableSettle
        val enableHireFriend = PetAdventureEngine.enableHireFriend
        val enableFriendCare = PetAdventureEngine.enableFriendCare
        val enableLikeBack = PetAdventureEngine.enableLikeBack
        val enableClaimCoinBag = PetAdventureEngine.enableClaimCoinBag
        val enableActiveVisit = PetAdventureEngine.enableActiveVisit
        val enableAutoPk = PetAdventureEngine.enableAutoPk
        val enableFatigueToAdventure = PetAdventureEngine.enableFatigueToAdventure
        val prefHireFriendUinsCsv = PetAdventureEngine.prefHireFriendUinsCsv
        val prefCustomSchoolStage = PetAdventureEngine.prefCustomSchoolStage
        val prefCustomCourseSubject = PetAdventureEngine.prefCustomCourseSubject
        val prefCustomCourseDuration = PetAdventureEngine.prefCustomCourseDuration
        val prefCustomWorkType = PetAdventureEngine.prefCustomWorkType
        val prefCustomWorkDuration = PetAdventureEngine.prefCustomWorkDuration
        val prefCareEnergyThreshold = PetAdventureEngine.prefCareEnergyThreshold
        val prefCareCleanThreshold = PetAdventureEngine.prefCareCleanThreshold
        val prefFriendCareEnergyThreshold = PetAdventureEngine.prefFriendCareEnergyThreshold
        val prefFriendCareCleanThreshold = PetAdventureEngine.prefFriendCareCleanThreshold
        val prefHiredRecallProgress = PetAdventureEngine.prefHiredRecallProgress
        val prefHumanLikeSleep = PetAdventureEngine.prefHumanLikeSleep
        val prefNightSleepMode = PetAdventureEngine.prefNightSleepMode
        val prefScreenOffSilent = PetAdventureEngine.prefScreenOffSilent

        PetAdventureEngine.sendLog("总开关:${onOff(masterEnabled)}（默认关闭；关闭时模块不发起任何请求）")
        val hireCount = prefHireFriendUinsCsv.split(',').count { it.isNotBlank() }
        PetAdventureEngine.sendLog(
            "配置生效 学习:${onOff(enableStudy)} 打工:${onOff(enableWork)} 照顾:${onOff(enableCare)} 冒险:${
                onOff(
                    enableAdventure
                )
            } " +
                    "结算:${onOff(enableSettle)} 雇佣:${onOff(enableHireFriend)}(${hireCount}人) 好友照料:${
                        onOff(
                            enableFriendCare
                        )
                    } " +
                    "回踩:${onOff(enableLikeBack)} 福袋:${onOff(enableClaimCoinBag)} 串门:${onOff(enableActiveVisit)} " +
                    "PK:${onOff(enableAutoPk)} 疲惫转探险:${onOff(enableFatigueToAdventure)}"
        )
        val placeTitle =
            PetAdventureEngine.cachedWorkPlaces?.stages?.find { it.stage == prefCustomWorkType }?.title
        PetAdventureEngine.sendLog(
            "调度明细 学园:${UiDescUtils.schoolStageLabel(prefCustomSchoolStage)} " +
                    "科目:${UiDescUtils.courseSubjectLabel(prefCustomCourseSubject)} " +
                    "课时:${UiDescUtils.courseDurationLabel(prefCustomCourseDuration)} " +
                    "场所:${UiDescUtils.workTypeLabel(prefCustomWorkType, placeTitle)} " +
                    "工时:${UiDescUtils.workDurationLabel(prefCustomWorkDuration)} " +
                    "体力≤$prefCareEnergyThreshold 清洁≤$prefCareCleanThreshold " +
                    "好友体力≤$prefFriendCareEnergyThreshold 好友清洁≤$prefFriendCareCleanThreshold " +
                    "召回:${if (prefHiredRecallProgress > 0) "${prefHiredRecallProgress}%" else "关"} " +
                    "拟人:${onOff(prefHumanLikeSleep)} 夜间静默:${onOff(prefNightSleepMode)} 熄屏静默:${
                        onOff(
                            prefScreenOffSilent
                        )
                    }"
        )
    }
}

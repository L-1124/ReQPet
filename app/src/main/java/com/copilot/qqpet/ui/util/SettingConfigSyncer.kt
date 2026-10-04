package com.copilot.qqpet.ui.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.ui.PreferencesHelper

object SettingConfigSyncer {

    fun triggerAction(context: Context, engine: PetAdventureEngine?, action: String) {
        if (engine != null) {
            engine.runAction(context, action)
        } else {
            val intent = Intent(HookEntry.ACTION_TRIGGER_ACTION).apply {
                setPackage("com.tencent.mobileqq")
                putExtra(PetAdventureEngine.EXTRA_ACTION, action)
            }
            context.sendBroadcast(intent)
        }
        (engine ?: HookEntry.globalEngine)?.wakeUpMasterCycle(context)
    }

    fun syncConfig(prefs: SharedPreferences, engine: PetAdventureEngine?, context: Context) {
        val study = prefs.getBoolean("key_study", true)
        val work = prefs.getBoolean("key_work", true)
        val care = prefs.getBoolean("key_care", true)
        val adv = prefs.getBoolean("key_adventure", false)
        val settle = prefs.getBoolean("key_settle", true)
        val likeBack = prefs.getBoolean(PreferencesHelper.KEY_LIKE_BACK, true)
        val claimCoinBag = prefs.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, true)
        val fatigueToAdv = prefs.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
        val studyMode = prefs.getInt("key_study_mode", 0)
        val workMode = prefs.getInt("key_work_mode", 0)
        val schoolStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val courseSubject = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
        val courseDuration = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
        val workType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val workDuration = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
        val careEnergy = prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
        val careClean = prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
        val humanLikeSleep = prefs.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
        val nightSleep = prefs.getBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
        val screenOffSilent = prefs.getBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
        val hideQQSetting = prefs.getBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
        val debugLog = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
        val hireFriend = prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
        val hireUinsCsv = PetAdventureEngine.loadSavedHireFriendUins(context).joinToString(",")
        val friendCareEnabled = prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
        val friendCareEnergy = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
        val friendCareClean = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
        val autoPk = prefs.getBoolean(PreferencesHelper.KEY_AUTO_PK, false)
        val pkBlacklistUinsCsv = PetAdventureEngine.loadSavedPkBlacklistUins(context).joinToString(",")
        val hiredRecall = prefs.getInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72)
        val activeVisit = prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, true)
        val activeVisitFriends = prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, true)
        val activeVisitStrangers = prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, true)
        val activeVisitDailyLimit = prefs.getInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, 20)
        val disableTinker = prefs.getBoolean(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false)

        HookEntry.globalEngine?.updateConfig(
            study, work, care, adv, settle, likeBack, claimCoinBag, fatigueToAdv,
            studyMode, workMode, schoolStage, courseSubject, courseDuration,
            workType, workDuration, careEnergy, careClean, humanLikeSleep,
            nightSleep, screenOffSilent, hideQQSetting, debugLog, hireFriend,
            hireUinsCsv, friendCareEnabled, friendCareEnergy, friendCareClean,
            autoPk, pkBlacklistUinsCsv, hiredRecall, activeVisit,
            activeVisitFriends, activeVisitStrangers, activeVisitDailyLimit
        )
        if (engine != null && engine !== HookEntry.globalEngine) {
            engine.updateConfig(
                study, work, care, adv, settle, likeBack, claimCoinBag, fatigueToAdv,
                studyMode, workMode, schoolStage, courseSubject, courseDuration,
                workType, workDuration, careEnergy, careClean, humanLikeSleep,
                nightSleep, screenOffSilent, hideQQSetting, debugLog, hireFriend,
                hireUinsCsv, friendCareEnabled, friendCareEnergy, friendCareClean,
                autoPk, pkBlacklistUinsCsv, hiredRecall, activeVisit,
                activeVisitFriends, activeVisitStrangers, activeVisitDailyLimit
            )
        }
        val intent = Intent(HookEntry.ACTION_UPDATE_CONFIG).apply {
            setPackage("com.tencent.mobileqq")
            putExtra("extra_time", System.currentTimeMillis())
            putExtra("extra_study", study)
            putExtra("extra_work", work)
            putExtra("extra_care", care)
            putExtra("extra_adventure", adv)
            putExtra("extra_settle", settle)
            putExtra("extra_like_back", likeBack)
            putExtra("extra_claim_coinbag", claimCoinBag)
            putExtra("extra_fatigue_to_adventure", fatigueToAdv)
            putExtra("extra_study_mode", studyMode)
            putExtra("extra_work_mode", workMode)
            putExtra("extra_school_stage", schoolStage)
            putExtra("extra_course_subject", courseSubject)
            putExtra("extra_course_duration", courseDuration)
            putExtra("extra_work_type", workType)
            putExtra("extra_work_duration", workDuration)
            putExtra("extra_care_energy_threshold", careEnergy)
            putExtra("extra_care_clean_threshold", careClean)
            putExtra("extra_human_like_sleep", humanLikeSleep)
            putExtra("extra_night_sleep_mode", nightSleep)
            putExtra("extra_screen_off_silent", screenOffSilent)
            putExtra("extra_hide_qq_setting_entry", hideQQSetting)
            putExtra("extra_debug_log", debugLog)
            putExtra("extra_hire_friend_enabled", hireFriend)
            putExtra("extra_hire_friend_uins", hireUinsCsv)
            putExtra("extra_friend_care_enabled", friendCareEnabled)
            putExtra("extra_friend_care_energy_threshold", friendCareEnergy)
            putExtra("extra_friend_care_clean_threshold", friendCareClean)
            putExtra("extra_auto_pk", autoPk)
            putExtra("extra_pk_blacklist_uins", pkBlacklistUinsCsv)
            putExtra("extra_hired_recall_progress", hiredRecall)
            putExtra("extra_active_visit", activeVisit)
            putExtra("extra_active_visit_friends", activeVisitFriends)
            putExtra("extra_active_visit_strangers", activeVisitStrangers)
            putExtra("extra_active_visit_daily_limit", activeVisitDailyLimit)
            putExtra("extra_disable_tinker_patch", disableTinker)
        }
        context.sendBroadcast(intent)
        (engine ?: HookEntry.globalEngine)?.wakeUpMasterCycle(context)
    }
}

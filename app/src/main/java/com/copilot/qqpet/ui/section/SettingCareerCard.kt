package com.copilot.qqpet.ui.section

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.component.AppleSegmentedControl
import com.copilot.qqpet.ui.component.AppleSwitchView
import com.copilot.qqpet.ui.component.SegmentItem
import com.copilot.qqpet.ui.dialog.HireFriendWhitelistDialog
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.CardUiBuilder
import com.copilot.qqpet.ui.util.SettingConfigSyncer
import com.copilot.qqpet.ui.util.UiAnimUtils
import com.copilot.qqpet.ui.util.UiDescUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingCareerCard(
    private val context: Context,
    private val colors: ThemeColors,
    private val prefs: SharedPreferences,
    private val engine: PetAdventureEngine?
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var studyStageSeg: AppleSegmentedControl? = null
    private var studySubtitleTv: TextView? = null
    private var workTypeSeg: AppleSegmentedControl? = null
    private var workSubtitleTv: TextView? = null
    private var workDurSeg: AppleSegmentedControl? = null

    fun build(container: LinearLayout): View {
        CardUiBuilder.addSectionHeader(container, "自动轮转调度", colors)
        val card = CardUiBuilder.createGroupCard(context, colors)
        buildStudySection(card)
        card.addView(CardUiBuilder.createDivider(context, colors))
        buildWorkSection(card)
        card.addView(CardUiBuilder.createDivider(context, colors).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 4))
        })
        buildHiredRecallSection(card)
        container.addView(card)
        return card
    }

    private fun buildStudySection(card: LinearLayout) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiAnimUtils.dp(context, 13), 0, UiAnimUtils.dp(context, 13))
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, UiAnimUtils.dp(context, 10), 0)
            }
        }
        textCol.addView(TextView(context).apply {
            text = "进阶学力研修"; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText)
        })
        val currentStagePref = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val curHighest = PetAdventureEngine.cachedSchoolDetails?.currentStage ?: 0
        val studySubtitle = TextView(context).apply {
            text = UiDescUtils.getSchoolStageDesc(currentStagePref, curHighest)
            textSize = 13f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0)
        }
        studySubtitleTv = studySubtitle
        textCol.addView(studySubtitle); row.addView(textCol)

        val studyPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, UiAnimUtils.dp(context, 12))
        }
        buildStudyControls(studyPanel, currentStagePref, studySubtitle)

        val studyInitialChecked = prefs.getBoolean("key_study", true)
        val studySwitch = AppleSwitchView(context, colors.isNight).apply {
            setCheckedImmediately(studyInitialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean("key_study", isChecked).commit()
                UiAnimUtils.animateExpandCollapse(studyPanel, isChecked)
                SettingConfigSyncer.syncConfig(engine, context)
            }
        }
        row.addView(studySwitch); card.addView(row)
        if (!studyInitialChecked) studyPanel.visibility = View.GONE
        card.addView(studyPanel)

        updateSchoolUnlockStates(PetAdventureEngine.cachedSchoolDetails)
    }

    private fun buildStudyControls(panel: LinearLayout, currentStagePref: Int, subtitleTv: TextView) {
        panel.addView(TextView(context).apply { text = "学园阶段"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 4)) })
        val stageItems = UiDescUtils.buildSchoolStageOptions(PetAdventureEngine.cachedSchoolDetails)
        val stageSeg = AppleSegmentedControl(context, stageItems, currentStagePref, isNight = colors.isNight) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_SCHOOL_STAGE, sel).commit()
            val highest = PetAdventureEngine.cachedSchoolDetails?.currentStage ?: 0
            subtitleTv.text = UiDescUtils.getSchoolStageDesc(sel, highest)
            SettingConfigSyncer.syncConfig(engine, context)
        }
        studyStageSeg = stageSeg
        panel.addView(stageSeg)

        panel.addView(TextView(context).apply { text = "专攻科目 (按官方属性加点)"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 4)) })
        val currentSubjPref = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
        panel.addView(AppleSegmentedControl(context, listOf("智能轮换", "智力(文科)", "力量(体育)", "魅力(艺术)"), currentSubjPref, isNight = colors.isNight) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_COURSE_SUBJECT, sel).commit()
            SettingConfigSyncer.syncConfig(engine, context)
        })
        panel.addView(TextView(context).apply { text = "课时时长偏好"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 4)) })
        val currentDurPref = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
        panel.addView(AppleSegmentedControl(context, listOf("任意课时", "基础短课(10-45m)", "进阶长课(1-2.25h)"), currentDurPref, isNight = colors.isNight) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_COURSE_DURATION, sel).commit()
            SettingConfigSyncer.syncConfig(engine, context)
        })
    }

    fun updateSchoolUnlockStates(details: QQPetDirectBridge.SecondMapDetails?) {
        if (details == null || details.code != 0) return
        val options = UiDescUtils.buildSchoolStageOptions(details)
        studyStageSeg?.updateItemStates(options)
        val curStage = details.currentStage
        val curStageName = when (curStage) {
            1 -> "初级学园"
            2 -> "中级学园"
            3 -> "高级学园"
            4 -> "进修学园"
            else -> "第${curStage}阶段学园"
        }
        val attrPart = if (details.power > 0 || details.intel > 0 || details.charm > 0) {
            " · 力量${details.power} 智力${details.intel} 魅力${details.charm}"
        } else ""
        val savedStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        studySubtitleTv?.text = if (savedStage == 0) {
            "智能自适应: 当前就读 $curStageName$attrPart"
        } else {
            "${UiDescUtils.getSchoolStageDesc(savedStage, curStage)}$attrPart"
        }
    }

    fun updateWorkUnlockStates(details: QQPetDirectBridge.SecondMapDetails?, jobs: List<QQPetDirectBridge.SelectEvent>?) {
        if (details == null || details.code != 0) return
        val options = UiDescUtils.buildWorkPlaceOptions(details)
        val curWorkType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val selectedIdx = options.indexOfFirst { it.careerId == curWorkType }.let { if (it >= 0) it else 0 }
        val segItems = options.map { SegmentItem(it.title, enabled = it.enabled, disabledTip = it.disabledTip) }
        workTypeSeg?.rebuildItems(segItems, selectedIdx)
        val curOpt = options.getOrNull(selectedIdx)
        workSubtitleTv?.text = UiDescUtils.getWorkTypeDesc(curWorkType, curOpt?.title, details)
        updateWorkDurSeg(workDurSeg, jobs)
    }

    private fun buildWorkSection(card: LinearLayout) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiAnimUtils.dp(context, 13), 0, UiAnimUtils.dp(context, 13))
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, UiAnimUtils.dp(context, 10), 0)
            }
        }
        textCol.addView(TextView(context).apply { text = "全自动打工派遣"; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText) })
        val workPlaceOptions = UiDescUtils.buildWorkPlaceOptions(PetAdventureEngine.cachedWorkPlaces)
        val currentWorkTypePref = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val initialWorkPlaceIndex = workPlaceOptions.indexOfFirst { it.careerId == currentWorkTypePref }.let { if (it >= 0) it else 0 }
        val workSubtitle = TextView(context).apply {
            val curOption = workPlaceOptions.getOrNull(initialWorkPlaceIndex)
            text = UiDescUtils.getWorkTypeDesc(currentWorkTypePref, curOption?.title, PetAdventureEngine.cachedWorkPlaces)
            textSize = 13f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0)
        }
        workSubtitleTv = workSubtitle
        textCol.addView(workSubtitle); row.addView(textCol)

        val workPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, UiAnimUtils.dp(context, 12)) }
        buildWorkControls(workPanel, workSubtitle, initialWorkPlaceIndex)

        val workInitialChecked = prefs.getBoolean("key_work", true)
        val workSwitch = AppleSwitchView(context, colors.isNight).apply {
            setCheckedImmediately(workInitialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean("key_work", isChecked).commit()
                UiAnimUtils.animateExpandCollapse(workPanel, isChecked)
                SettingConfigSyncer.syncConfig(engine, context)
            }
        }
        row.addView(workSwitch); card.addView(row)
        if (!workInitialChecked) workPanel.visibility = View.GONE
        card.addView(workPanel)
        updateWorkUnlockStates(PetAdventureEngine.cachedWorkPlaces, PetAdventureEngine.cachedWorkJobs)
    }

    private fun buildWorkControls(panel: LinearLayout, subtitleTv: TextView, initialIdx: Int) {
        val workPlaceOptions = UiDescUtils.buildWorkPlaceOptions(PetAdventureEngine.cachedWorkPlaces)
        panel.addView(TextView(context).apply { text = "打工场所 (职业小镇动态识别)"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 4)) })

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val typeSeg = AppleSegmentedControl(context, workPlaceOptions.map { SegmentItem(it.title, enabled = it.enabled, disabledTip = it.disabledTip) }, initialIdx, isScrollable = true, isNight = colors.isNight) { sel ->
            val opt = workPlaceOptions.getOrNull(sel) ?: return@AppleSegmentedControl
            prefs.edit().putInt(PreferencesHelper.KEY_WORK_TYPE, opt.careerId).commit()
            subtitleTv.text = UiDescUtils.getWorkTypeDesc(opt.careerId, opt.title, PetAdventureEngine.cachedWorkPlaces)
            SettingConfigSyncer.syncConfig(engine, context)
            asyncRefreshWorkJobs(opt.careerId) { jobs -> updateWorkDurSeg(workDurSeg, jobs) }
        }
        workTypeSeg = typeSeg
        scroll.addView(typeSeg, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll)

        panel.addView(TextView(context).apply { text = "打工时长偏好 (官方实测阶梯工时)"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 4)) })
        val durPref = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
        val durSeg = AppleSegmentedControl(context, listOf("智能挂机", "10分钟", "45分钟", "2小时", "4小时"), durPref, isNight = colors.isNight) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_WORK_DURATION, sel).commit()
            SettingConfigSyncer.syncConfig(engine, context)
        }
        workDurSeg = durSeg
        updateWorkDurSeg(durSeg, PetAdventureEngine.cachedWorkJobs)
        panel.addView(durSeg)

        buildHireFriendSubRow(panel)
    }

    private fun buildHireFriendSubRow(panel: LinearLayout) {
        panel.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, UiAnimUtils.dp(context, 12), 0, UiAnimUtils.dp(context, 4)) }
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 8)) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply { setMargins(0, 0, UiAnimUtils.dp(context, 10), 0) } }
        col.addView(TextView(context).apply { text = "打工自动雇佣好友"; textSize = 14.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText) })
        col.addView(TextView(context).apply { text = "仅在已勾选的好友中，默认雇佣空闲且收益最高的好友"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0) })
        row.addView(col)
        row.addView(AppleSwitchView(context, colors.isNight).apply {
            setCheckedImmediately(prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true))
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, isChecked).commit()
                SettingConfigSyncer.syncConfig(engine, context)
            }
        })
        panel.addView(row)

        val summaryTv = TextView(context).apply { text = HireFriendWhitelistDialog.formatHireWhitelistSummary(context); textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0) }
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(if (this@SettingCareerCard.colors.isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F7")); cornerRadius = UiAnimUtils.dp(context, 9).toFloat() }
            setPadding(UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 2)) }
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener { HireFriendWhitelistDialog.showHireFriendWhitelistDialog(context, colors, engine) { summaryTv.text = HireFriendWhitelistDialog.formatHireWhitelistSummary(context) } }
        }
        val btnCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply { setMargins(0, 0, UiAnimUtils.dp(context, 8), 0) } }
        btnCol.addView(TextView(context).apply { text = "选择雇佣好友白名单 (支持名字/QQ号搜索)"; textSize = 13.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.actionBlueText) })
        btnCol.addView(summaryTv)
        btnRow.addView(btnCol)
        btnRow.addView(TextView(context).apply { text = "勾选 ›"; textSize = 13.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.actionBlueText) })
        panel.addView(btnRow)
    }

    private fun buildHiredRecallSection(card: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 4)) }
        row.addView(TextView(context).apply { text = "被雇佣打工提前召回 (自选进度锁定奖金)"; textSize = 13.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText) })
        val values = listOf(0, 12, 42, 72)
        if (!prefs.contains(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS)) {
            try { prefs.edit().putInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72).commit() } catch (_: Throwable) {}
        }
        var curHiredRecall = prefs.getInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72)
        val descTv = TextView(context).apply { text = UiDescUtils.getHiredRecallDesc(curHiredRecall); textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0) }
        row.addView(descTv)
        val initialIdx = values.indexOf(curHiredRecall).let { if (it >= 0) it else 3 }
        row.addView(AppleSegmentedControl(context, listOf("关闭", "12% 极速", "42% 均衡", "72% 顶格"), initialIdx, isNight = colors.isNight) { sel ->
            val v = values.getOrElse(sel) { 72 }
            curHiredRecall = v
            prefs.edit().putInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, v).commit()
            descTv.text = UiDescUtils.getHiredRecallDesc(curHiredRecall)
            SettingConfigSyncer.syncConfig(engine, context)
        })
        card.addView(row)
    }

    private fun updateWorkDurSeg(seg: AppleSegmentedControl?, jobs: List<QQPetDirectBridge.SelectEvent>?) {
        if (!jobs.isNullOrEmpty() && seg != null) {
            val can10 = jobs.find { it.costTime.contains("10") }?.canDo ?: true
            val can45 = jobs.find { it.costTime.contains("45") }?.canDo ?: true
            val can2h = jobs.find { it.costTime.contains("2小时") }?.canDo ?: true
            val can4h = jobs.find { it.costTime.contains("4小时") }?.canDo ?: true
            seg.updateItemStates(listOf(
                SegmentItem("智能挂机", enabled = true),
                SegmentItem(if (can10) "10分钟" else "10分(锁)", enabled = can10, disabledTip = if (!can10) "10分钟兼职暂未满足解锁条件" else null),
                SegmentItem(if (can45) "45分钟" else "45分(锁)", enabled = can45, disabledTip = if (!can45) "45分钟兼职暂未满足解锁条件" else null),
                SegmentItem(if (can2h) "2小时" else "2小时(锁)", enabled = can2h, disabledTip = if (!can2h) "2小时兼职暂未满足解锁条件" else null),
                SegmentItem(if (can44(can4h)) "4小时" else "4小时(锁)", enabled = can4h, disabledTip = if (!can4h) "4小时兼职暂未满足解锁条件" else null)
            ))
        }
    }

    private fun can44(can4h: Boolean): Boolean = can4h

    private fun asyncRefreshWorkJobs(careerId: Int, onResult: (List<QQPetDirectBridge.SelectEvent>) -> Unit) {
        val active = engine ?: HookEntry.globalEngine ?: return
        CoroutineScope(Dispatchers.IO).launch {
            active.verifyAndSyncAccountSession(context)
            val petId = PetAdventureEngine.cachedPetId ?: active.queryOwnPetAwait().second?.also { PetAdventureEngine.saveScopedPetId(context, it) }
            if (!petId.isNullOrEmpty()) {
                val (jCode, jobs) = active.querySelectEventsAwait(6400L, petId, schoolStage = 0, careerType = if (careerId > 0) careerId else 3)
                if (jCode == 0 && jobs.isNotEmpty()) {
                    PetAdventureEngine.cachedWorkJobs = jobs
                    mainHandler.post { onResult(jobs) }
                }
            }
        }
    }
}

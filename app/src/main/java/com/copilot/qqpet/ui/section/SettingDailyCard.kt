package com.copilot.qqpet.ui.section

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.ui.component.AppleSegmentedControl
import com.copilot.qqpet.ui.component.AppleSwitchView
import com.copilot.qqpet.ui.dialog.PkBlacklistDialog
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.CardUiBuilder
import com.copilot.qqpet.ui.util.SettingConfigSyncer
import com.copilot.qqpet.ui.util.UiAnimUtils
import com.copilot.qqpet.ui.util.UiDescUtils

class SettingDailyCard(
    private val context: Context,
    private val colors: ThemeColors,
    private val prefs: SharedPreferences,
    private val engine: PetAdventureEngine?
) {

    private val thresholdValues = listOf(40, 50, 60, 70, 80, 90, 100)
    private val thresholdLabels = listOf("40", "50", "60", "70", "80", "90", "100")

    fun build(container: LinearLayout): View {
        CardUiBuilder.addSectionHeader(container, "日常起居与历练", colors)
        val card = CardUiBuilder.createGroupCard(context, colors)
        buildCareSection(card)
        buildFriendCareSection(card)
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "自动回踩访客", "定时巡检并自动回赠所有造访小家的好友与陌生访客", PreferencesHelper.KEY_LIKE_BACK, false, false)
        buildActiveVisitSection(card)
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "自动领取福袋", "自动扫描并拆取自己小窝及好友掉落的金币福袋", PreferencesHelper.KEY_CLAIM_COINBAG, false, false)
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "疲惫时自动转冒险", "检测到疲惫收益减少时，取消打工和学习转去冒险直至恢复", PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, false, false)
        buildPkSection(card)
        buildSafetySwitches(card)
        container.addView(card)
        return card
    }

    private fun buildCareSection(card: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, UiAnimUtils.dp(context, 13), 0, UiAnimUtils.dp(context, 13)) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply { setMargins(0, 0, UiAnimUtils.dp(context, 10), 0) } }
        col.addView(TextView(context).apply { text = "自动进食与沐浴"; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText) })
        var curEnergy = prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
        var curClean = prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
        val subTv = TextView(context).apply { text = UiDescUtils.getCareSubtitle(curEnergy, curClean); textSize = 13f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0) }
        col.addView(subTv); row.addView(col)

        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, UiAnimUtils.dp(context, 12)) }
        panel.addView(TextView(context).apply { text = "进食体力阈值 (缺粮时自动采购爱心饼干)"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 4)) })
        val energyIdx = thresholdValues.indexOf(curEnergy).let { if (it >= 0) it else 2 }
        panel.addView(AppleSegmentedControl(context, thresholdLabels, energyIdx, isNight = colors.isNight) { sel ->
            curEnergy = thresholdValues.getOrElse(sel) { 60 }
            prefs.edit().putInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, curEnergy).commit()
            subTv.text = UiDescUtils.getCareSubtitle(curEnergy, curClean)
            SettingConfigSyncer.syncConfig(engine, context)
        })
        panel.addView(TextView(context).apply { text = "洗澡清洁阈值 (零消耗温水香皂触控洗护)"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 4)) })
        val cleanIdx = thresholdValues.indexOf(curClean).let { if (it >= 0) it else 2 }
        panel.addView(AppleSegmentedControl(context, thresholdLabels, cleanIdx, isNight = colors.isNight) { sel ->
            curClean = thresholdValues.getOrElse(sel) { 60 }
            prefs.edit().putInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, curClean).commit()
            subTv.text = UiDescUtils.getCareSubtitle(curEnergy, curClean)
            SettingConfigSyncer.syncConfig(engine, context)
        })

        val initialChecked = prefs.getBoolean("key_care", false)
        row.addView(AppleSwitchView(context, colors.isNight).apply {
            setCheckedImmediately(initialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean("key_care", isChecked).commit()
                UiAnimUtils.animateExpandCollapse(panel, isChecked)
                SettingConfigSyncer.syncConfig(engine, context)
            }
        })
        card.addView(row)
        if (!initialChecked) panel.visibility = View.GONE
        card.addView(panel)
        card.addView(CardUiBuilder.createDivider(context, colors))
    }

    private fun buildFriendCareSection(card: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, UiAnimUtils.dp(context, 13), 0, UiAnimUtils.dp(context, 13)) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply { setMargins(0, 0, UiAnimUtils.dp(context, 10), 0) } }
        col.addView(TextView(context).apply { text = "好友宠物自动喂食洗澡"; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText) })
        var curEnergy = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
        var curClean = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
        val subTv = TextView(context).apply { text = UiDescUtils.getFriendCareSubtitle(curEnergy, curClean); textSize = 13f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0) }
        col.addView(subTv); row.addView(col)

        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, UiAnimUtils.dp(context, 12)) }
        panel.addView(TextView(context).apply { text = "体力阈值（雇佣后低于该值才喂，喂到不低于该值）"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 4)) })
        val energyIdx = thresholdValues.indexOf(curEnergy).let { if (it >= 0) it else 2 }
        panel.addView(AppleSegmentedControl(context, thresholdLabels, energyIdx, isNight = colors.isNight) { sel ->
            curEnergy = thresholdValues.getOrElse(sel) { 60 }
            prefs.edit().putInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, curEnergy).commit()
            subTv.text = UiDescUtils.getFriendCareSubtitle(curEnergy, curClean)
            SettingConfigSyncer.syncConfig(engine, context)
        })
        panel.addView(TextView(context).apply { text = "清洁阈值（雇佣后低于该值才洗，洗到不低于该值）"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 4)) })
        val cleanIdx = thresholdValues.indexOf(curClean).let { if (it >= 0) it else 2 }
        panel.addView(AppleSegmentedControl(context, thresholdLabels, cleanIdx, isNight = colors.isNight) { sel ->
            curClean = thresholdValues.getOrElse(sel) { 60 }
            prefs.edit().putInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, curClean).commit()
            subTv.text = UiDescUtils.getFriendCareSubtitle(curEnergy, curClean)
            SettingConfigSyncer.syncConfig(engine, context)
        })

        val initialChecked = prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
        row.addView(AppleSwitchView(context, colors.isNight).apply {
            setCheckedImmediately(initialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, isChecked).commit()
                UiAnimUtils.animateExpandCollapse(panel, isChecked)
                SettingConfigSyncer.syncConfig(engine, context)
            }
        })
        card.addView(row)
        if (!initialChecked) panel.visibility = View.GONE
        card.addView(panel)
        card.addView(CardUiBuilder.createDivider(context, colors))
    }

    private fun buildActiveVisitSection(card: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, UiAnimUtils.dp(context, 10), 0, UiAnimUtils.dp(context, 10)) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f) }
        col.addView(TextView(context).apply { text = "自动主动串门踩踩"; textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.primaryText) })
        col.addView(TextView(context).apply { text = "主动串门送心，支持全量养宠好友与全自动随机陌生小宠"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0) })
        row.addView(col)

        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 4), UiAnimUtils.dp(context, 8), UiAnimUtils.dp(context, 12)) }
        CardUiBuilder.addSimpleToggleRow(panel, context, colors, prefs, engine, "主动踩全部好友", "每天自动遍历好友小宠小窝，主动串门送心续火花", PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, true, false)
        CardUiBuilder.addSimpleToggleRow(panel, context, colors, prefs, engine, "主动踩随机陌生人", "自动从活跃陌生小宠池每日洗牌随机抽取串门，引流回踩", PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, true, false)
        panel.addView(TextView(context).apply { text = "单日主动串门安全上限 (防风控，离散拟人发包)"; textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 8), 0, UiAnimUtils.dp(context, 4)) })
        val limitLabels = listOf("10人", "20人 (推荐)", "30人", "50人")
        val limitValues = listOf(10, 20, 30, 50)
        val curLimit = prefs.getInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, 20)
        val limitIdx = limitValues.indexOf(curLimit).let { if (it >= 0) it else 1 }
        panel.addView(AppleSegmentedControl(context, limitLabels, limitIdx, isNight = colors.isNight) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, limitValues.getOrElse(sel) { 20 }).commit()
            SettingConfigSyncer.syncConfig(engine, context)
        })

        val initialChecked = prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, false)
        row.addView(AppleSwitchView(context, colors.isNight).apply {
            setCheckedImmediately(initialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, isChecked).commit()
                UiAnimUtils.animateExpandCollapse(panel, isChecked)
                SettingConfigSyncer.syncConfig(engine, context)
            }
        })
        card.addView(row)
        if (!initialChecked) panel.visibility = View.GONE
        card.addView(panel)
        card.addView(CardUiBuilder.createDivider(context, colors))
    }

    private fun buildPkSection(card: LinearLayout) {
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "自动对决挑战 (PK)", "每日自动与好友或访客PK 10场，三维筛查稳赢挑战，冷却1~3分钟", PreferencesHelper.KEY_AUTO_PK, false, false)
        val summaryTv = TextView(context).apply { text = PkBlacklistDialog.formatPkBlacklistSummary(context); textSize = 12f; setTextColor(colors.secondaryText); setPadding(0, UiAnimUtils.dp(context, 2), 0, 0) }
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(if (this@SettingDailyCard.colors.isNight) Color.parseColor("#252528") else Color.parseColor("#F6F6F9")); cornerRadius = UiAnimUtils.dp(context, 9).toFloat() }
            setPadding(UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 10), UiAnimUtils.dp(context, 12), UiAnimUtils.dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, UiAnimUtils.dp(context, 4), 0, UiAnimUtils.dp(context, 4)) }
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener { PkBlacklistDialog.showPkBlacklistDialog(context, colors, engine) { summaryTv.text = PkBlacklistDialog.formatPkBlacklistSummary(context) } }
        }
        val btnCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply { setMargins(0, 0, UiAnimUtils.dp(context, 8), 0) } }
        btnCol.addView(TextView(context).apply { text = "PK 免战黑名单 (支持搜索与好友/访客勾选)"; textSize = 13.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.actionBlueText) })
        btnCol.addView(summaryTv)
        btnRow.addView(btnCol)
        btnRow.addView(TextView(context).apply { text = "管理 ›"; textSize = 13.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(colors.actionBlueText) })
        card.addView(btnRow)
    }

    private fun buildSafetySwitches(card: LinearLayout) {
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "神秘森林冒险", "自动深入野外林区探秘与冒险", "key_adventure", false, false)
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "探险收益结算", "历练归来自动领取全部掉落收益", "key_settle", false, false)
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "动态拟人休眠", "随机1~3分钟非固定周期休眠，有效避免行为时序聚类识别", PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true, false)
        card.addView(CardUiBuilder.createDivider(context, colors))
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "夜间防风控静默", "凌晨01:30~06:30暂停唤醒与轮转，完全符合人类作息时序", PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true, false)
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "熄屏防风控静默", "手机熄屏锁屏时暂停主动发包调度，亮屏恢复，避免黑屏发包特征", PreferencesHelper.KEY_SCREEN_OFF_SILENT, true, false)
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "调试详细日志", "默认静默，开启后向 XposedBridge 打印详细发包日志", PreferencesHelper.KEY_DEBUG_LOG, false, false)
        card.addView(CardUiBuilder.createDivider(context, colors))
        CardUiBuilder.addSimpleToggleRow(card, context, colors, prefs, engine, "禁止 Tinker 热补丁", "默认关闭；开启后阻断 QQ 静默热更新，防止混淆变更导致模块失效，但会跳过官方 Bug 修复", PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false, true)
    }
}

package com.copilot.qqpet.ui.compose.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.ActionRow
import com.copilot.qqpet.ui.compose.CardDivider
import com.copilot.qqpet.ui.compose.ExpandablePanel
import com.copilot.qqpet.ui.compose.ChoiceToggleRow
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsGroup
import com.copilot.qqpet.ui.compose.SettingsState
import com.copilot.qqpet.ui.compose.SliderRow
import com.copilot.qqpet.ui.compose.ToggleRow
import com.copilot.qqpet.ui.compose.dialog.ConfirmDialog
import com.copilot.qqpet.ui.compose.dialog.PkBlacklistDialog
import com.copilot.qqpet.ui.util.UiDescUtils

private val FRIEND_CARE_THRESHOLD_VALUES = listOf(40, 50, 60, 70, 80, 90, 100)

private val ACTIVE_VISIT_LIMITS = listOf(10, 20, 30, 50)
private val ACTIVE_VISIT_LIMIT_LABELS = listOf("10人", "20人", "30人", "50人")

private data class DangerousSwitch(
    val key: String,
    val confirmTitle: String,
    val confirmMessage: String,
    val confirmText: String
)

private val ACTIVE_VISIT_CONFIRM = DangerousSwitch(
    PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED,
    "开启自动主动串门踩踩？",
    "开启后将每日自动遍历养宠好友与随机陌生小宠串门送心，请确认单日安全上限后再继续。",
    "确认开启"
)

private val AUTO_PK_CONFIRM = DangerousSwitch(
    PreferencesHelper.KEY_AUTO_PK,
    "开启自动对决挑战 (PK)？",
    "开启后将每日自动与三维低于我方的对手连打 10 场，冷却 1~3 分钟，请先确认免战黑名单。",
    "确认开启"
)

private val TINKER_CONFIRM = DangerousSwitch(
    PreferencesHelper.KEY_DISABLE_TINKER_PATCH,
    "开启禁止 Tinker 热补丁？",
    "开启后将阻断 QQ 静默热更新，防止混淆变更导致模块失效，但会跳过官方 Bug 修复。",
    "确认开启"
)

@Composable
fun AutomationSection(state: SettingsState) {
    var pendingConfirm by remember { mutableStateOf<DangerousSwitch?>(null) }
    var showPkBlacklist by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("自动化与静默守护")
        SettingsGroup {
            val friendCareEnabled = state.bool(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
            item {
                ToggleRow(
                    title = "好友宠物自动喂食洗澡",
                    checked = friendCareEnabled,
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, it) },
                    subtitle = UiDescUtils.getFriendCareSubtitle(
                        state.int(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60),
                        state.int(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
                    )
                )
            }
            expandableItem(friendCareEnabled) {
                FriendCareThresholdSliderRow(
                    state = state,
                    key = PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD,
                    title = "体力阈值",
                    defaultValue = 60
                )
            }
            expandableItem(friendCareEnabled) {
                FriendCareThresholdSliderRow(
                    state = state,
                    key = PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD,
                    title = "清洁阈值",
                    defaultValue = 60
                )
            }

            val activeVisitEnabled = state.bool(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, false)
            item {
                ToggleRow(
                    title = "自动主动串门踩踩",
                    checked = activeVisitEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) pendingConfirm = ACTIVE_VISIT_CONFIRM
                        else state.setBool(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, false)
                    },
                    subtitle = "主动串门送心，支持全量养宠好友与全自动随机陌生小宠"
                )
            }
            expandableItem(activeVisitEnabled) {
                ToggleRow(
                    title = "主动踩全部好友",
                    checked = state.bool(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, it) },
                    subtitle = "每天自动遍历好友小宠小窝，主动串门送心续火花"
                )
            }
            expandableItem(activeVisitEnabled) {
                ToggleRow(
                    title = "主动踩随机陌生人",
                    checked = state.bool(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, it) },
                    subtitle = "自动从活跃陌生小宠池每日洗牌随机抽取串门，引流回踩"
                )
            }
            expandableItem(activeVisitEnabled) {
                val limit = state.int(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, 20)
                val limitIndex = ACTIVE_VISIT_LIMITS.indexOf(limit).let { if (it >= 0) it else 1 }
                ChoiceToggleRow(
                    title = "单日主动串门安全上限",
                    options = ACTIVE_VISIT_LIMIT_LABELS,
                    selectedIndex = limitIndex,
                    onSelect = {
                        state.setInt(
                            PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT,
                            ACTIVE_VISIT_LIMITS.getOrElse(it) { 20 }
                        )
                    }
                )
            }

            val autoPkEnabled = state.bool(PreferencesHelper.KEY_AUTO_PK, false)
            item {
                ToggleRow(
                    title = "自动对决挑战 (PK)",
                    checked = autoPkEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) pendingConfirm = AUTO_PK_CONFIRM
                        else state.setBool(PreferencesHelper.KEY_AUTO_PK, false)
                    },
                    subtitle = "每日自动与好友或访客PK 10场，三维筛查稳赢挑战，冷却1~3分钟"
                )
            }
            expandableItem(autoPkEnabled) {
                ActionRow(
                    title = "PK 免战黑名单",
                    subtitle = state.pkBlacklistSummary,
                    trailing = "管理",
                    onClick = { showPkBlacklist = true }
                )
            }

            item {
                ToggleRow(
                    title = "动态拟人休眠",
                    checked = state.bool(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, it) },
                    subtitle = "随机1~3分钟非固定周期休眠，有效避免行为时序聚类识别"
                )
            }
            item {
                ToggleRow(
                    title = "夜间防风控静默",
                    checked = state.bool(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, it) },
                    subtitle = "凌晨01:30~06:30暂停唤醒与轮转，完全符合人类作息时序"
                )
            }
            item {
                ToggleRow(
                    title = "熄屏防风控静默",
                    checked = state.bool(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_SCREEN_OFF_SILENT, it) },
                    subtitle = "手机熄屏锁屏时暂停主动发包调度，亮屏恢复，避免黑屏发包特征"
                )
            }
            item {
                ToggleRow(
                    title = "隐藏 QQ 设置入口",
                    checked = state.bool(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, it) },
                    subtitle = "开启后不再向 QQ 设置页注入本模块入口卡片，隐藏模块存在痕迹"
                )
            }
            item {
                ToggleRow(
                    title = "调试详细日志",
                    checked = state.bool(PreferencesHelper.KEY_DEBUG_LOG, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_DEBUG_LOG, it) },
                    subtitle = "默认静默，开启后向 libxposed 打印详细发包日志"
                )
            }
            item {
                ToggleRow(
                    title = "禁止 Tinker 热补丁",
                    checked = state.bool(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false),
                    onCheckedChange = { enabled ->
                        if (enabled) pendingConfirm = TINKER_CONFIRM
                        else state.setBool(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false)
                    },
                    subtitle = "默认关闭；开启后阻断 QQ 静默热更新，防止混淆变更导致模块失效，但会跳过官方 Bug 修复"
                )
            }
        }
    }

    pendingConfirm?.let { target ->
        ConfirmDialog(
            title = target.confirmTitle,
            message = target.confirmMessage,
            confirmText = target.confirmText,
            onConfirm = {
                state.setBool(target.key, true)
                pendingConfirm = null
            },
            onDismiss = { pendingConfirm = null }
        )
    }

    if (showPkBlacklist) {
        PkBlacklistDialog(state = state, onDismiss = { showPkBlacklist = false })
    }
}

@Composable
private fun FriendCareThresholdSliderRow(
    state: SettingsState,
    key: String,
    title: String,
    defaultValue: Int
) {
    val current = state.int(key, defaultValue)
    val index = FRIEND_CARE_THRESHOLD_VALUES.indexOf(current)
        .let { if (it >= 0) it else FRIEND_CARE_THRESHOLD_VALUES.indexOf(defaultValue).coerceAtLeast(0) }
    SliderRow(
        title = title,
        value = index,
        range = 0..FRIEND_CARE_THRESHOLD_VALUES.lastIndex,
        valueLabel = FRIEND_CARE_THRESHOLD_VALUES[index].toString(),
        onValueChange = { state.setInt(key, FRIEND_CARE_THRESHOLD_VALUES.getOrElse(it) { defaultValue }) }
    )
}

@Preview(showBackground = true)
@Composable
private fun AutomationSectionPreview() {
    MaterialTheme {
        AutomationSection(state = SettingsState(LocalContext.current, null))
    }
}

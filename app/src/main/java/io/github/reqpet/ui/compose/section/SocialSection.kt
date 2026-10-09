package io.github.reqpet.ui.compose.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.reqpet.ui.PreferencesHelper
import io.github.reqpet.ui.compose.ChoiceToggleRow
import io.github.reqpet.ui.compose.ExpandablePanel
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.compose.ToggleRow
import io.github.reqpet.ui.compose.dialog.ConfirmDialog

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
    "开启后将按所选范围访问好友与随机陌生小宠，请确认每日数量上限后再继续。",
    "确认开启"
)

@Composable
fun SocialSection(state: SettingsState) {
    var pendingConfirm by remember { mutableStateOf<DangerousSwitch?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("社交互动")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "自动回踩访客",
                    checked = state.bool(PreferencesHelper.KEY_LIKE_BACK, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_LIKE_BACK, it) },
                    subtitle = "定时巡检并自动回赠所有造访小家的好友与陌生访客"
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
                ExpandablePanel(activeVisitEnabled) {
                    ToggleRow(
                        title = "主动踩全部好友",
                        checked = state.bool(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, true),
                        onCheckedChange = { state.setBool(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, it) },
                        subtitle = "每天自动遍历好友小宠小窝，主动串门送心续火花"
                    )
                    ToggleRow(
                        title = "主动踩随机陌生人",
                        checked = state.bool(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, true),
                        onCheckedChange = { state.setBool(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, it) },
                        subtitle = "自动从活跃陌生小宠池每日洗牌随机抽取串门，引流回踩"
                    )
                    val limit = state.int(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, 20)
                    val limitIndex = ACTIVE_VISIT_LIMITS.indexOf(limit).let { if (it >= 0) it else 1 }
                    ChoiceToggleRow(
                        title = "每日主动串门数量上限",
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
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SocialActions(state = state)
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
}

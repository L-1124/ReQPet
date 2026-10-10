package io.github.reqpet.ui.compose.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.reqpet.ui.PreferencesHelper
import io.github.reqpet.ui.compose.ExpandablePanel
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.compose.SliderRow
import io.github.reqpet.ui.compose.ToggleRow
import io.github.reqpet.ui.util.UiDescUtils

private val CARE_THRESHOLD_VALUES = listOf(40, 50, 60, 70, 80, 90, 100)
private val FRIEND_CARE_THRESHOLD_VALUES = listOf(40, 50, 60, 70, 80, 90, 100)

@Composable
fun CareSection(state: SettingsState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("宠物照料")
        SettingsGroup {
            val oneClickCareEnabled = state.bool(PreferencesHelper.KEY_ONE_CLICK_CARE, false)
            val careEnabled = state.bool(PreferencesHelper.KEY_CARE, false)
            item {
                ToggleRow(
                    title = "一键呵护恢复",
                    checked = oneClickCareEnabled,
                    onCheckedChange = { enabled ->
                        state.setBool(PreferencesHelper.KEY_ONE_CLICK_CARE, enabled)
                        if (enabled) {
                            state.setBool(PreferencesHelper.KEY_CARE, false)
                        }
                    },
                    subtitle = "体力或清洁降低时，一键原子加满三围并自动补购缺口道具"
                )
            }
            item {
                ToggleRow(
                    title = "自动进食与沐浴",
                    checked = !oneClickCareEnabled && careEnabled,
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_CARE, it) },
                    enabled = !oneClickCareEnabled,
                    subtitle = if (oneClickCareEnabled) "已开启一键呵护恢复，逐步进食与沐浴已自动接管并停用" else UiDescUtils.getCareSubtitle(
                        state.int(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60),
                        state.int(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
                    )
                )
                ExpandablePanel(!oneClickCareEnabled && careEnabled) {
                    ThresholdSliderRow(
                        state = state,
                        key = PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD,
                        title = "进食体力阈值",
                        defaultValue = 60
                    )
                    ThresholdSliderRow(
                        state = state,
                        key = PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD,
                        title = "洗澡清洁阈值",
                        defaultValue = 60
                    )
                }
            }

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
                ExpandablePanel(friendCareEnabled) {
                    FriendCareThresholdSliderRow(
                        state = state,
                        key = PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD,
                        title = "体力阈值",
                        defaultValue = 60
                    )
                    FriendCareThresholdSliderRow(
                        state = state,
                        key = PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD,
                        title = "清洁阈值",
                        defaultValue = 60
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        CareActions(state = state)
    }
}

@Composable
private fun ThresholdSliderRow(
    state: SettingsState,
    key: String,
    title: String,
    defaultValue: Int
) {
    val current = state.int(key, defaultValue)
    val index = CARE_THRESHOLD_VALUES.indexOf(current)
        .let { if (it >= 0) it else CARE_THRESHOLD_VALUES.indexOf(defaultValue).coerceAtLeast(0) }
    SliderRow(
        title = title,
        value = index,
        range = CARE_THRESHOLD_VALUES.indices,
        valueLabel = CARE_THRESHOLD_VALUES[index].toString(),
        onValueChange = { state.setInt(key, CARE_THRESHOLD_VALUES.getOrElse(it) { defaultValue }) }
    )
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
        range = FRIEND_CARE_THRESHOLD_VALUES.indices,
        valueLabel = FRIEND_CARE_THRESHOLD_VALUES[index].toString(),
        onValueChange = { state.setInt(key, FRIEND_CARE_THRESHOLD_VALUES.getOrElse(it) { defaultValue }) }
    )
}

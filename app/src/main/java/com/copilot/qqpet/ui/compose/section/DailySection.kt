package com.copilot.qqpet.ui.compose.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.CardDivider
import com.copilot.qqpet.ui.compose.ExpandablePanel
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsCard
import com.copilot.qqpet.ui.compose.SettingsState
import com.copilot.qqpet.ui.compose.SliderRow
import com.copilot.qqpet.ui.compose.ToggleRow
import com.copilot.qqpet.ui.util.UiDescUtils

private val CARE_THRESHOLD_VALUES = listOf(40, 50, 60, 70, 80, 90, 100)

@Composable
fun DailySection(state: SettingsState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("日常起居与历练")
        SettingsCard {
            val careEnabled = state.bool(PreferencesHelper.KEY_CARE, false)
            ToggleRow(
                title = "自动进食与沐浴",
                checked = careEnabled,
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_CARE, it) },
                subtitle = UiDescUtils.getCareSubtitle(
                    state.int(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60),
                    state.int(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
                )
            )
            ExpandablePanel(careEnabled) {
                ThresholdSliderRow(
                    state = state,
                    key = PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD,
                    title = "进食体力阈值 (缺粮时自动采购爱心饼干)",
                    defaultValue = 60
                )
                ThresholdSliderRow(
                    state = state,
                    key = PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD,
                    title = "洗澡清洁阈值 (零消耗温水香皂触控洗护)",
                    defaultValue = 60
                )
            }
            CardDivider()
            ToggleRow(
                title = "自动回踩访客",
                checked = state.bool(PreferencesHelper.KEY_LIKE_BACK, false),
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_LIKE_BACK, it) },
                subtitle = "定时巡检并自动回赠所有造访小家的好友与陌生访客"
            )
            CardDivider()
            ToggleRow(
                title = "自动领取福袋",
                checked = state.bool(PreferencesHelper.KEY_CLAIM_COINBAG, false),
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_CLAIM_COINBAG, it) },
                subtitle = "自动扫描并拆取自己小窝及好友掉落的金币福袋"
            )
            CardDivider()
            ToggleRow(
                title = "疲惫时自动转冒险",
                checked = state.bool(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, false),
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, it) },
                subtitle = "检测到疲惫收益减少时，取消打工和学习转去冒险直至恢复"
            )
            CardDivider()
            ToggleRow(
                title = "神秘森林冒险",
                checked = state.bool(PreferencesHelper.KEY_ADVENTURE, false),
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_ADVENTURE, it) },
                subtitle = "自动深入野外林区探秘与冒险"
            )
            CardDivider()
            ToggleRow(
                title = "探险收益结算",
                checked = state.bool(PreferencesHelper.KEY_SETTLE, false),
                onCheckedChange = { state.setBool(PreferencesHelper.KEY_SETTLE, it) },
                subtitle = "历练归来自动领取全部掉落收益"
            )
        }
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
        range = 0..CARE_THRESHOLD_VALUES.lastIndex,
        valueLabel = CARE_THRESHOLD_VALUES[index].toString(),
        onValueChange = { state.setInt(key, CARE_THRESHOLD_VALUES.getOrElse(it) { defaultValue }) }
    )
}

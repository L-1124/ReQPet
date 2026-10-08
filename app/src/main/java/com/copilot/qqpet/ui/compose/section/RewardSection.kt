package com.copilot.qqpet.ui.compose.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsGroup
import com.copilot.qqpet.ui.compose.SettingsState
import com.copilot.qqpet.ui.compose.ToggleRow

@Composable
fun RewardSection(state: SettingsState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("福袋领取")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "自动领取福袋",
                    checked = state.bool(PreferencesHelper.KEY_CLAIM_COINBAG, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_CLAIM_COINBAG, it) },
                    subtitle = "自动扫描并拆取自己小窝及好友掉落的金币福袋"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        RewardActions(state = state)
    }
}

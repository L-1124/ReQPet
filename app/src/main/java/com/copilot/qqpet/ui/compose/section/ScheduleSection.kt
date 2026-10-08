package com.copilot.qqpet.ui.compose.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsGroup
import com.copilot.qqpet.ui.compose.SettingsState
import com.copilot.qqpet.ui.compose.ToggleRow

@Composable
fun ScheduleSection(state: SettingsState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("调度策略")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "夜间静默",
                    checked = state.bool(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, it) },
                    subtitle = "凌晨01:30~06:30暂停主动轮转调度，到期结算任务仍可通行"
                )
            }
            item {
                ToggleRow(
                    title = "熄屏静默",
                    checked = state.bool(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_SCREEN_OFF_SILENT, it) },
                    subtitle = "设备熄屏锁屏时暂停主动发包调度，亮屏后恢复"
                )
            }
            item {
                ToggleRow(
                    title = "随机休眠",
                    checked = state.bool(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, it) },
                    subtitle = "各业务轮转间隙增加1~3分钟随机浮动休眠，非固定周期"
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScheduleSectionPreview() {
    MaterialTheme {
        ScheduleSection(state = SettingsState(LocalContext.current, null, previewMode = true))
    }
}

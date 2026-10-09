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
import io.github.reqpet.ui.compose.ActionRow
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.compose.ToggleRow
import io.github.reqpet.ui.compose.dialog.ConfirmDialog
import io.github.reqpet.ui.compose.dialog.PkBlacklistDialog

private data class PkSwitchConfirmation(
    val key: String,
    val confirmTitle: String,
    val confirmMessage: String,
    val confirmText: String
)

private val AUTO_PK_CONFIRM = PkSwitchConfirmation(
    PreferencesHelper.KEY_AUTO_PK,
    "开启自动对决挑战 (PK)？",
    "开启后将每日自动与三维低于我方的对手连打 10 场，冷却 1~3 分钟，请先确认免战黑名单。",
    "确认开启"
)

@Composable
fun PkSection(state: SettingsState) {
    var pendingConfirm by remember { mutableStateOf<PkSwitchConfirmation?>(null) }
    var showPkBlacklist by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("对决挑战")
        SettingsGroup {
            val autoPkEnabled = state.bool(PreferencesHelper.KEY_AUTO_PK, false)
            item {
                ToggleRow(
                    title = "自动对决挑战 (PK)",
                    checked = autoPkEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) pendingConfirm = AUTO_PK_CONFIRM
                        else state.setBool(PreferencesHelper.KEY_AUTO_PK, false)
                    },
                    subtitle = "筛选三维属性较低的对手，每轮最多10场，场间等待1~3分钟"
                )
            }
            item {
                ActionRow(
                    title = "PK 免战名单",
                    subtitle = state.pkBlacklistSummary,
                    trailing = "管理",
                    onClick = { showPkBlacklist = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        PkActions(state = state)
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

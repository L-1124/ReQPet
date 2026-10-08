package com.copilot.qqpet.ui.compose.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsGroup
import com.copilot.qqpet.ui.compose.SettingsState
import com.copilot.qqpet.ui.compose.ToggleRow
import com.copilot.qqpet.ui.compose.dialog.ConfirmDialog

@Composable
fun ModuleSection(state: SettingsState) {
    var showTinkerConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("模块设置")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "禁止 Tinker 热补丁",
                    checked = state.bool(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false),
                    onCheckedChange = { enabled ->
                        if (enabled) showTinkerConfirm = true
                        else state.setBool(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false)
                    },
                    subtitle = "阻断 QQ 的 Tinker 静默热更新，会跳过官方补丁修复；只影响宿主补丁，不是模块热重载"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        MoreSection(state)
    }

    if (showTinkerConfirm) {
        ConfirmDialog(
            title = "开启禁止 Tinker 热补丁？",
            message = "开启后会阻断 QQ 的 Tinker 热补丁，跳过官方补丁修复。此选项不涉及模块热重载。",
            confirmText = "确认开启",
            onConfirm = {
                state.setBool(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, true)
                showTinkerConfirm = false
            },
            onDismiss = { showTinkerConfirm = false }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ModuleSectionPreview() {
    MaterialTheme {
        ModuleSection(state = SettingsState(LocalContext.current, null, previewMode = true))
    }
}

package com.copilot.qqpet.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.copilot.qqpet.ui.PreferencesHelper

@Composable
private fun SettingsPreview(page: SettingsPage, dark: Boolean = false) {
    val context = LocalContext.current
    val state = remember(context) { SettingsState(context, null, previewMode = true) }
    QPetExpressiveTheme(dark = dark) {
        QPetSettingsContent(state, onBack = {}, initialPage = page)
    }
}

@Preview(name = "首页 · 浅色", showBackground = true, widthDp = 412, heightDp = 1150)
@Composable
private fun SettingsHomePreview() = SettingsPreview(SettingsPage.HOME)

@Preview(name = "首页 · 深色", showBackground = true, widthDp = 412, heightDp = 1150)
@Composable
private fun SettingsHomeDarkPreview() = SettingsPreview(SettingsPage.HOME, dark = true)

@Preview(name = "任务养成", showBackground = true, widthDp = 412, heightDp = 1150)
@Composable
private fun SettingsTasksPreview() = SettingsPreview(SettingsPage.TASKS)

@Preview(name = "社交互动", showBackground = true, widthDp = 412, heightDp = 1000)
@Composable
private fun SettingsSocialPreview() = SettingsPreview(SettingsPage.SOCIAL)

@Preview(name = "调度策略", showBackground = true, widthDp = 412, heightDp = 844)
@Composable
private fun SettingsSchedulePreview() = SettingsPreview(SettingsPage.SCHEDULE)

@Preview(name = "窄屏大字体", showBackground = true, widthDp = 320, heightDp = 844, fontScale = 1.3f)
@Composable
private fun SettingsNarrowPreview() = SettingsPreview(SettingsPage.HOME)

@Preview(name = "任务选项展开", showBackground = true, widthDp = 700, heightDp = 1250)
@Composable
private fun SettingsTaskOptionsPreview() {
    val context = LocalContext.current
    val state = remember(context) {
        SettingsState(context, null, previewMode = true).apply {
            setBool(PreferencesHelper.KEY_STUDY, true)
            setBool(PreferencesHelper.KEY_WORK, true)
        }
    }
    QPetExpressiveTheme(dark = false) {
        QPetSettingsContent(state, onBack = {}, initialPage = SettingsPage.TASKS)
    }
}

@Preview(name = "宠物照料", showBackground = true, widthDp = 412, heightDp = 1150)
@Composable
private fun SettingsCarePreview() {
    val context = LocalContext.current
    val state = remember(context) {
        SettingsState(context, null, previewMode = true).apply {
            setBool(PreferencesHelper.KEY_CARE, true)
            setBool(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, true)
        }
    }
    QPetExpressiveTheme(dark = false) {
        QPetSettingsContent(state, onBack = {}, initialPage = SettingsPage.CARE)
    }
}

@Preview(name = "社交选项展开", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
private fun SettingsSocialOptionsPreview() {
    val context = LocalContext.current
    val state = remember(context) {
        SettingsState(context, null, previewMode = true).apply {
            setBool(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, true)
        }
    }
    QPetExpressiveTheme(dark = false) {
        QPetSettingsContent(state, onBack = {}, initialPage = SettingsPage.SOCIAL)
    }
}

@Preview(name = "对决挑战", showBackground = true, widthDp = 412, heightDp = 844)
@Composable
private fun SettingsPkPreview() = SettingsPreview(SettingsPage.PK)

@Preview(name = "福袋领取", showBackground = true, widthDp = 412, heightDp = 700)
@Composable
private fun SettingsRewardsPreview() = SettingsPreview(SettingsPage.REWARDS)

@Preview(name = "模块设置", showBackground = true, widthDp = 412, heightDp = 1000)
@Composable
private fun SettingsModulePreview() = SettingsPreview(SettingsPage.MODULE)

@Preview(name = "日志与诊断", showBackground = true, widthDp = 412, heightDp = 1400)
@Composable
private fun SettingsDiagnosticsPreview() = SettingsPreview(SettingsPage.DIAGNOSTICS)

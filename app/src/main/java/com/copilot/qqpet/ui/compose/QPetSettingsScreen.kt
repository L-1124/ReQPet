package com.copilot.qqpet.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.section.CareerSection
import com.copilot.qqpet.ui.compose.section.CareSection
import com.copilot.qqpet.ui.compose.section.DiagnosticsSection
import com.copilot.qqpet.ui.compose.section.ModuleSection
import com.copilot.qqpet.ui.compose.section.OverviewActions
import com.copilot.qqpet.ui.compose.section.PkSection
import com.copilot.qqpet.ui.compose.section.RewardSection
import com.copilot.qqpet.ui.compose.section.ScheduleSection
import com.copilot.qqpet.ui.compose.section.SocialSection
import kotlinx.coroutines.delay

internal enum class SettingsPage(val title: String) {
    HOME("Q宠后台伴侣"),
    TASKS("任务养成"),
    CARE("宠物照料"),
    SOCIAL("社交互动"),
    PK("对决挑战"),
    REWARDS("福袋领取"),
    SCHEDULE("调度策略"),
    DIAGNOSTICS("日志与诊断"),
    MODULE("模块设置")
}

@Composable
fun QPetSettingsScreen(
    state: SettingsState,
    onBack: () -> Unit
) {
    QPetSettingsContent(state, onBack)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun QPetSettingsContent(
    state: SettingsState,
    onBack: () -> Unit,
    initialPage: SettingsPage = SettingsPage.HOME
) {
    var page by remember { mutableStateOf(initialPage) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        if (!state.previewMode) {
            while (true) {
                delay(1_000L)
                state.refresh()
            }
        }
    }
    DisposableEffect(state) {
        state.attach(scope)
        onDispose { state.detach() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (page == SettingsPage.HOME) onBack() else page = SettingsPage.HOME
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (page == SettingsPage.HOME) "返回 QQ" else "返回上一页"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (page == SettingsPage.HOME) {
                    item(key = "master") {
                        SettingsGroup {
                            item {
                                ToggleRow(
                                    title = "自动托管",
                                    subtitle = "关闭时不发起模块请求；配置保留，下次开启继续使用",
                                    checked = state.bool(PreferencesHelper.KEY_MASTER_ENABLED, false),
                                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_MASTER_ENABLED, it) }
                                )
                            }
                        }
                    }
                    item(key = "status") {
                        SettingsCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("运行状态", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(state.statusText.ifBlank { "状态尚未确认" },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 8.dp))
                                Text(state.attributesText.ifBlank { "暂无小宠数据" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    item(key = "features") {
                        SectionHeader("功能")
                        SettingsGroup {
                            item { CategoryRow(SettingsPage.TASKS, taskSummary(state), { page = it }) }
                            item { CategoryRow(SettingsPage.CARE, "自己与好友的小宠 · 照料阈值", { page = it }) }
                            item { CategoryRow(SettingsPage.SOCIAL, "回踩访客 · 主动串门 · 每日上限", { page = it }) }
                            item { CategoryRow(SettingsPage.PK, "自动挑战 · 免战名单", { page = it }) }
                            item { CategoryRow(SettingsPage.REWARDS, "自动领取 · 立即领取", { page = it }) }
                        }
                    }
                    item(key = "system") {
                        SectionHeader("系统")
                        SettingsGroup {
                            item { CategoryRow(SettingsPage.SCHEDULE, "夜间与熄屏静默 · 随机休眠", { page = it }) }
                            item { CategoryRow(SettingsPage.DIAGNOSTICS, "近期日志 · 长期日志位置 · 引擎指标", { page = it }) }
                            item { CategoryRow(SettingsPage.MODULE, "宿主兼容 · 反馈", { page = it }) }
                        }
                    }
                    item(key = "overview_actions") { OverviewActions(state) }
                } else {
                    item(key = page.name) {
                        when (page) {
                            SettingsPage.TASKS -> CareerSection(state)
                            SettingsPage.CARE -> CareSection(state)
                            SettingsPage.SOCIAL -> SocialSection(state)
                            SettingsPage.PK -> PkSection(state)
                            SettingsPage.REWARDS -> RewardSection(state)
                            SettingsPage.SCHEDULE -> ScheduleSection(state)
                            SettingsPage.DIAGNOSTICS -> DiagnosticsSection(state)
                            SettingsPage.MODULE -> ModuleSection(state)
                            SettingsPage.HOME -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(page: SettingsPage, summary: String, onSelect: (SettingsPage) -> Unit) {
    ActionRow(title = page.title, subtitle = summary, onClick = { onSelect(page) })
}

@Composable
private fun taskSummary(state: SettingsState): String {
    val study = state.bool(PreferencesHelper.KEY_STUDY, false)
    val work = state.bool(PreferencesHelper.KEY_WORK, false)
    val adventure = state.bool(PreferencesHelper.KEY_ADVENTURE, false)
    return remember(study, work, adventure) {
        val enabled = buildList {
            if (study) add("学习")
            if (work) add("打工")
            if (adventure) add("冒险")
        }
        if (enabled.isEmpty()) "学习 · 打工 · 冒险 · 结算"
        else "已开启：${enabled.joinToString("、")}"
    }
}

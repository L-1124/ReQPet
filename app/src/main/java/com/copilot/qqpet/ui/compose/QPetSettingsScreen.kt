package com.copilot.qqpet.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
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
import com.copilot.qqpet.ui.compose.dialog.ConfirmDialog
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

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
    onBack: () -> Unit,
    onBackHandlerChanged: (handler: (() -> Boolean)?) -> Unit
) {
    QPetSettingsContent(state, onBack, onBackHandlerChanged = onBackHandlerChanged)
}

@Composable
internal fun QPetSettingsContent(
    state: SettingsState,
    onBack: () -> Unit,
    initialPage: SettingsPage = SettingsPage.HOME,
    onBackHandlerChanged: ((handler: (() -> Boolean)?) -> Unit)? = null
) {
    val navigation = rememberSaveable(saver = SettingsBackStack.Saver) { SettingsBackStack(initialPage) }
    val page = navigation.currentPage
    val scope = rememberCoroutineScope()
    val stateHolder = rememberSaveableStateHolder()
    var showInspectionConfirm by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, lifecycleOwner) {
        if (!state.previewMode) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(1_000L.milliseconds)
                    state.refresh()
                }
            }
        }
    }
    DisposableEffect(state) {
        state.attach(scope)
        onDispose { state.detach() }
    }
    DisposableEffect(navigation, onBackHandlerChanged) {
        onBackHandlerChanged?.invoke(navigation::popBack)
        onDispose { onBackHandlerChanged?.invoke(null) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!navigation.popBack()) onBack()
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
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
            contentKey = { it },
            transitionSpec = {
                val direction = if (navigation.isBackNavigation) {
                    AnimatedContentTransitionScope.SlideDirection.End
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Start
                }
                val spatialSpec = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 380f)
                val effectsSpec = spring<Float>(dampingRatio = 1.0f, stiffness = 1600f)
                (slideIntoContainer(direction, spatialSpec, initialOffset = { it / 8 }) +
                        fadeIn(effectsSpec)) togetherWith
                        (slideOutOfContainer(direction, spatialSpec, targetOffset = { it / 8 }) +
                                fadeOut(effectsSpec))
            },
            label = "settings_page"
        ) { displayedPage ->
            stateHolder.SaveableStateProvider(displayedPage.name) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight(),
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(if (displayedPage == SettingsPage.HOME) 0.dp else 12.dp)
                ) {
                    if (displayedPage == SettingsPage.HOME) {
                        item(key = "master", contentType = "toggle") {
                            SettingsGroupItem(index = 0, total = 1) {
                                ToggleRow(
                                    title = "自动托管",
                                    subtitle = "关闭时不发起模块请求；配置保留，下次开启继续使用",
                                    checked = state.bool(PreferencesHelper.KEY_MASTER_ENABLED, false),
                                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_MASTER_ENABLED, it) }
                                )
                            }
                        }
                        item(key = "status", contentType = "status") {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(
                                        "运行状态", style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        state.statusText.ifBlank { "状态尚未确认" },
                                        style = MaterialTheme.typography.titleMediumEmphasized,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                    Text(
                                        state.attributesText.ifBlank { "暂无小宠数据" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Button(
                                        onClick = { showInspectionConfirm = true },
                                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                    ) {
                                        Text(
                                            "立即执行全套巡检与养成",
                                            style = MaterialTheme.typography.labelLargeEmphasized
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "features", contentType = "category_header") {
                            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                SectionHeader("功能")
                                SettingsGroupItem(index = 0, total = 5) {
                                    CategoryRow(SettingsPage.TASKS, taskSummary(state), navigation::navigate)
                                }
                            }
                        }
                        item(key = "care", contentType = "category") {
                            SettingsGroupItem(index = 1, total = 5, modifier = Modifier.padding(top = 3.dp)) {
                                CategoryRow(SettingsPage.CARE, "自己与好友的小宠 · 照料阈值", navigation::navigate)
                            }
                        }
                        item(key = "social", contentType = "category") {
                            SettingsGroupItem(index = 2, total = 5, modifier = Modifier.padding(top = 3.dp)) {
                                CategoryRow(SettingsPage.SOCIAL, "回踩访客 · 主动串门 · 每日上限", navigation::navigate)
                            }
                        }
                        item(key = "pk", contentType = "category") {
                            SettingsGroupItem(index = 3, total = 5, modifier = Modifier.padding(top = 3.dp)) {
                                CategoryRow(SettingsPage.PK, "自动挑战 · 免战名单", navigation::navigate)
                            }
                        }
                        item(key = "rewards", contentType = "category") {
                            SettingsGroupItem(index = 4, total = 5, modifier = Modifier.padding(top = 3.dp)) {
                                CategoryRow(SettingsPage.REWARDS, "自动领取 · 立即领取", navigation::navigate)
                            }
                        }
                        item(key = "system", contentType = "category_header") {
                            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                SectionHeader("系统")
                                SettingsGroupItem(index = 0, total = 3) {
                                    CategoryRow(
                                        SettingsPage.SCHEDULE,
                                        "夜间与熄屏静默 · 随机休眠",
                                        navigation::navigate
                                    )
                                }
                            }
                        }
                        item(key = "diagnostics", contentType = "category") {
                            SettingsGroupItem(index = 1, total = 3, modifier = Modifier.padding(top = 3.dp)) {
                                CategoryRow(
                                    SettingsPage.DIAGNOSTICS,
                                    "近期日志 · 长期日志位置 · 引擎指标",
                                    navigation::navigate
                                )
                            }
                        }
                        item(key = "module", contentType = "category") {
                            SettingsGroupItem(index = 2, total = 3, modifier = Modifier.padding(top = 3.dp)) {
                                CategoryRow(SettingsPage.MODULE, "宿主兼容 · 反馈", navigation::navigate)
                            }
                        }
                        if (state.hasOngoingTask) {
                            item(key = "overview_actions", contentType = "actions") {
                                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                    OverviewActions(state)
                                }
                            }
                        }
                    } else {
                        item(key = displayedPage.name) {
                            when (displayedPage) {
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
    if (showInspectionConfirm) {
        ConfirmDialog(
            title = "执行全套巡检与养成？",
            message = "将立即同步小宠最新资质与起居状态，按需触发进食洗澡，并依序规划自适应日程。",
            confirmText = "立即执行",
            isDestructive = false,
            onConfirm = {
                state.action("cycle")
                showInspectionConfirm = false
            },
            onDismiss = { showInspectionConfirm = false }
        )
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

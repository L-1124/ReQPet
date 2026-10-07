package com.copilot.qqpet.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.section.ActionSection
import com.copilot.qqpet.ui.compose.section.AutomationSection
import com.copilot.qqpet.ui.compose.section.CareerSection
import com.copilot.qqpet.ui.compose.section.DailySection
import com.copilot.qqpet.ui.compose.section.LogSection
import com.copilot.qqpet.ui.compose.section.MetricsSection
import com.copilot.qqpet.ui.compose.section.MoreSection
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun QPetSettingsScreen(state: SettingsState, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L.milliseconds)
            state.refresh()
        }
    }
    DisposableEffect(Unit) {
        state.attach()
        onDispose { state.detach() }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Q宠后台伴侣") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader("总开关")
                SettingsCard {
                    ToggleRow(
                        title = "全自动托管",
                        subtitle = "默认关闭；关闭时模块不发起任何请求，下面的功能开关也不会生效",
                        checked = state.bool(PreferencesHelper.KEY_MASTER_ENABLED, false),
                        onCheckedChange = { state.setBool(PreferencesHelper.KEY_MASTER_ENABLED, it) }
                    )
                }
            }
            item {
                SectionHeader("运行状态")
                SettingsCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = state.statusText,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "小宠资质 · ${state.attributesText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            item { CareerSection(state) }
            item { DailySection(state) }
            item { AutomationSection(state) }
            item { ActionSection(state) }
            item { LogSection(state) }
            item { MetricsSection(state.engineMetrics) }
            item { MoreSection(state) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun QPetSettingsScreenPreview() {
    MaterialTheme {
        QPetSettingsScreen(state = SettingsState(LocalContext.current, null), onBack = {})
    }
}
package com.copilot.qqpet.ui.compose.section

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.ui.compose.CardDivider
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsCard
import com.copilot.qqpet.ui.compose.SettingsState

private const val MAX_RENDER_LINES = 80
private const val PLACEHOLDER = "暂无日志"

@Composable
fun LogSection(state: SettingsState) {
    val lines = state.logLines
    val listState = rememberLazyListState()

    // 环形缓冲写满后 lines.size 恒定，必须以最后一条内容为 key；
    // 用户上翻离开底部时不打扰，仅在贴底时跟随新日志滚动。
    val lastLine = lines.lastOrNull()
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }
    LaunchedEffect(lastLine) {
        if (lastLine != null && atBottom) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    SectionHeader("运行日志")
    SettingsCard {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(132.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.medium
                ),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            val visible = lines.takeLast(MAX_RENDER_LINES)
            if (visible.isEmpty()) {
                item(key = "placeholder") {
                    Text(
                        text = PLACEHOLDER,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            } else {
                itemsIndexed(visible) { _, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 1.dp)
                    )
                }
            }
        }
        Text(
            text = "共 ${lines.size} 条 · 上限 ${EngineLog.CAPACITY} 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
        )
        CardDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = false, role = Role.Button, onValueChange = { state.clearLogs() })
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "清空日志",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "🧹",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
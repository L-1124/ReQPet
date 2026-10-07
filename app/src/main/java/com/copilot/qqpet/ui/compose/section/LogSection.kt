package com.copilot.qqpet.ui.compose.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsCard
import com.copilot.qqpet.ui.compose.SettingsState

private const val MAX_RENDER_LINES = 80
private const val PLACEHOLDER = "暂无日志"

@Composable
fun LogSection(state: SettingsState) {
    val lines = state.logLines
    val listState = rememberLazyListState()

    val lastLine = lines.lastOrNull()
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }
    LaunchedEffect(lastLine) {
        if (lastLine != null && atBottom) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) {
                runCatching {
                    listState.animateScrollToItem(count - 1)
                }
            }
        }
    }

    SectionHeader("运行日志")
    SettingsCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .padding(end = 36.dp),
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        itemsIndexed(visible) { _, entry ->
                            Text(
                                text = "${entry.time} ${entry.message}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = when (entry.level) {
                                    EngineLog.Level.ERROR -> MaterialTheme.colorScheme.error
                                    EngineLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = { state.clearLogs() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-6).dp)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "清空日志",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LogSectionPreview() {
    MaterialTheme {
        LogSection(state = SettingsState(LocalContext.current, null))
    }
}

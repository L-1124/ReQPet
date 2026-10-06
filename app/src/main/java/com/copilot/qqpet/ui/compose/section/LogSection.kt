package com.copilot.qqpet.ui.compose.section

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val scrollState = rememberScrollState()

    LaunchedEffect(lines.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    SectionHeader("运行日志")
    SettingsCard {
        Text(
            text = if (lines.isEmpty()) PLACEHOLDER else lines.takeLast(MAX_RENDER_LINES).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(132.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.medium
                )
                .verticalScroll(scrollState)
                .padding(10.dp)
        )
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
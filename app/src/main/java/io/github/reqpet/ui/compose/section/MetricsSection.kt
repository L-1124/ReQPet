package io.github.reqpet.ui.compose.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.reqpet.engine.metrics.EngineMetrics
import io.github.reqpet.engine.metrics.EngineMetricsImpl
import io.github.reqpet.protocol.channel.ProtocolBreakers
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SettingsCard
import io.github.reqpet.ui.compose.QPetExpressiveTheme

private val DOMAIN_LABELS = listOf(
    "care" to "照顾",
    "career" to "学业打工",
    "social" to "社交",
    "pk" to "PK",
    "bath" to "洗护"
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "${bytes / (1 shl 20)} MB"
    bytes >= 1 shl 10 -> "${bytes / (1 shl 10)} KB"
    else -> "$bytes B"
}

// 数据来自进程内快照，不触发额外请求。
@Composable
fun MetricsSection(metrics: EngineMetrics?) {
    SectionHeader("引擎指标")
    val snapshot = metrics?.exportSnapshot()
    val breakers = remember(snapshot?.timestamp) {
        if (snapshot == null) emptyMap() else ProtocolBreakers.stateSnapshot()
    }
    SettingsCard {
        if (snapshot == null) {
            Text(
                text = "指标未就绪（引擎尚未初始化）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            val memory = snapshot.memoryUsage
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricCell("内存", formatBytes(memory.usedBytes))
                    MetricCell("水位", "${memory.utilizationPercent.toInt()}%")
                    MetricCell("事件", "${snapshot.recentEvents.size}")
                }
                DOMAIN_LABELS.forEach { (domain, label) ->
                    val stats = breakers[domain]
                    val state = stats?.get("state")?.toString()?.substringAfterLast('.') ?: "无记录"
                    val failures = stats?.get("failureCount")?.toString() ?: "0"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$state · 失败 $failures",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (state == "Open") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun MetricsSectionPreview() {
    val context = LocalContext.current
    val metrics = remember(context) { EngineMetricsImpl.getInstance(context) }
    QPetExpressiveTheme(dark = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                MetricsSection(metrics = metrics)
            }
        }
    }
}

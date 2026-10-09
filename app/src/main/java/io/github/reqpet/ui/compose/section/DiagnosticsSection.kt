package io.github.reqpet.ui.compose.section

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.reqpet.ui.PreferencesHelper
import io.github.reqpet.ui.compose.ActionRow
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.InfoRow
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.compose.ToggleRow
import java.io.File

@Composable
fun DiagnosticsSection(state: SettingsState) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext ?: context }

    val externalDir = remember(appContext) {
        try {
            appContext.getExternalFilesDir("qpet-diagnostics")
        } catch (_: Throwable) {
            null
        }
    }
    val fallbackDir = remember(appContext) {
        File(appContext.filesDir, "qpet-diagnostics")
    }

    val externalPath = externalDir?.absolutePath
    val fallbackPath = fallbackDir.absolutePath

    val copyPath: (String, String) -> Unit = { path, label ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, path))
            Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "剪贴板不可用，未复制目录", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LogSection(state)

        Spacer(modifier = Modifier.height(16.dp))

        SectionHeader("长期日志目录")
        SettingsGroup {
            item {
                ActionRow(
                    title = "首选外置目录",
                    subtitle = if (externalPath != null) {
                        "$externalPath\n（文件系统长期存储，非内存日志；如写入异常将自动降级）"
                    } else {
                        "外置存储不可用，将自动使用内部兜底"
                    },
                    trailing = if (externalPath != null) "复制" else null,
                    onClick = {
                        if (externalPath != null) {
                            copyPath(externalPath, "首选外置日志目录")
                        }
                    }
                )
            }
            item {
                ActionRow(
                    title = "内部兜底目录",
                    subtitle = "$fallbackPath\n（外置目录不可用或无法写入时使用，读取通常需要 root 权限）",
                    trailing = "复制",
                    onClick = {
                        copyPath(fallbackPath, "内部兜底日志目录")
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        MetricsSection(state.engineMetrics)

        Spacer(modifier = Modifier.height(16.dp))

        SectionHeader("宿主业务入口")
        SettingsGroup {
            item {
                InfoRow(
                    title = "业务发包策略",
                    value = "当前业务路径：现有代理发包 · 宿主入口仅探测"
                )
            }
            item {
                val report = state.hostProbeReport
                val trailingText = if (report.isScanning) "探测中" else "重新探测"
                val subtitleText = buildString {
                    append("实际业务就绪未验证")
                    if (report.scanTimestamp > 0L) {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        append(" · ").append(sdf.format(java.util.Date(report.scanTimestamp)))
                        if (report.isFromCache) {
                            append(" (缓存命中)")
                        } else {
                            append(" (全新扫描)")
                        }
                    }
                    if (report.apkFingerprint.isNotEmpty()) {
                        append(" · 指纹: ").append(report.apkFingerprint)
                    }
                }
                ActionRow(
                    title = "重新探测",
                    subtitle = subtitleText,
                    trailing = trailingText,
                    onClick = {
                        if (!report.isScanning) {
                            state.reprobeHostBusiness()
                        }
                    }
                )
            }

            items(state.hostProbeReport.entries) { entry ->
                val subtitle = buildString {
                    append("状态: ").append(entry.status.label)
                    if (entry.descriptor != null) {
                        append("\n").append(entry.descriptor)
                    }
                    if (entry.adapterDescriptor != null) {
                        append("\nAdapter: ").append(entry.adapterDescriptor)
                    }
                    if (entry.notice != null) {
                        append("\n说明: ").append(entry.notice)
                    }
                }
                ActionRow(
                    title = entry.title,
                    subtitle = subtitle,
                    trailing = entry.status.label,
                    onClick = {}
                )
            }

            items(state.hostProbeReport.unmappedFeatures) { entry ->
                ActionRow(
                    title = entry.title,
                    subtitle = entry.notice ?: "宿主入口尚未确认",
                    trailing = entry.status.label,
                    onClick = {}
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("调试选项")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "调试详细日志",
                    checked = state.bool(PreferencesHelper.KEY_DEBUG_LOG, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_DEBUG_LOG, it) },
                    subtitle = "默认静默，开启后向 libxposed 与系统 logcat 打印详细业务日志"
                )
            }
            item {
                ToggleRow(
                    title = "持久化诊断详情",
                    checked = state.bool(PreferencesHelper.KEY_DIAGNOSTICS_DETAIL, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_DIAGNOSTICS_DETAIL, it) },
                    subtitle = "记录异常类和有限调用帧，不记录错误正文或原始响应；仅在排查时开启"
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DiagnosticsSectionPreview() {
    MaterialTheme {
        DiagnosticsSection(state = SettingsState(LocalContext.current, null, previewMode = true))
    }
}

package com.copilot.qqpet.ui.compose.section

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
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.compose.ActionRow
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsGroup
import com.copilot.qqpet.ui.compose.SettingsState
import com.copilot.qqpet.ui.compose.ToggleRow
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

        SectionHeader("调试选项")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "调试详细日志",
                    checked = state.bool(PreferencesHelper.KEY_DEBUG_LOG, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_DEBUG_LOG, it) },
                    subtitle = "默认静默，开启后向 libxposed 打印详细发包日志"
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

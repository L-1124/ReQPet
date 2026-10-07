package com.copilot.qqpet.ui.compose.section

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.copilot.qqpet.ui.compose.ActionRow
import com.copilot.qqpet.ui.compose.SectionHeader
import com.copilot.qqpet.ui.compose.SettingsGroup
import com.copilot.qqpet.ui.compose.SettingsState

private const val GROUP_UIN = "1087942084"
private const val NATIVE_URI =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$GROUP_UIN&card_type=group&source=qrcode"
private const val WEB_URL = "https://qm.qq.com/q/FY6w7PMH2c"

@Composable
fun MoreSection(state: SettingsState) {
    val context = LocalContext.current

    SectionHeader("更多")
    SettingsGroup {
        item {
            ActionRow(
                title = "进入官方反馈交流群",
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(NATIVE_URI)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MoreSectionPreview() {
    MaterialTheme {
        MoreSection(state = SettingsState(LocalContext.current, null))
    }
}
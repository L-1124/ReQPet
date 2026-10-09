package io.github.reqpet.ui.compose.section

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import io.github.reqpet.ui.compose.ActionRow
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.SettingsState

private const val GROUP_UIN = "1087942084"
private const val NATIVE_URI =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$GROUP_UIN&card_type=group&source=qrcode"
private const val WEB_URL = "https://qm.qq.com/q/FY6w7PMH2c"

fun openOfficialFeedbackGroup(context: Context) {
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

@Composable
fun MoreSection(state: SettingsState) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("反馈")
        SettingsGroup {
            item {
                ActionRow(
                    title = "进入官方反馈交流群",
                    subtitle = "官方交流群: $GROUP_UIN",
                    trailing = "加入",
                    onClick = { openOfficialFeedbackGroup(context) }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MoreSectionPreview() {
    MaterialTheme {
        MoreSection(state = SettingsState(LocalContext.current, null, previewMode = true))
    }
}

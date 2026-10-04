package com.copilot.qqpet.ui.section

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.CardUiBuilder
import com.copilot.qqpet.ui.util.UiAnimUtils

class SettingMoreCard(
    private val context: Context,
    private val colors: ThemeColors
) {

    fun build(container: LinearLayout): View {
        CardUiBuilder.addSectionHeader(container, "更多", colors)
        val moreCard = CardUiBuilder.createGroupCard(context, colors)

        val moreRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiAnimUtils.dp(context, 14), 0, UiAnimUtils.dp(context, 14))
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener {
                try {
                    val intent = Intent().apply {
                        setClassName(HookEntry.MODULE_PACKAGE, "com.copilot.qqpet.ui.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Throwable) {}
            }
        }
        moreRow.addView(TextView(context).apply {
            text = "进入伴侣独立 App 管理更多细节"
            textSize = 15f
            setTextColor(colors.primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        })
        moreRow.addView(TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(colors.chevronText)
        })
        moreCard.addView(moreRow)

        val moreDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = UiAnimUtils.dp(context, 16)
            }
            setBackgroundColor(colors.dividerColor)
        }
        moreCard.addView(moreDivider)

        val groupRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiAnimUtils.dp(context, 14), 0, UiAnimUtils.dp(context, 14))
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener {
                val groupUin = "1087942084"
                val nativeUri = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$groupUin&card_type=group&source=qrcode"
                val webUrl = "https://qm.qq.com/q/FY6w7PMH2c"
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(nativeUri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Throwable) {
                    try {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(webIntent)
                    } catch (_: Throwable) {}
                }
            }
        }
        groupRow.addView(TextView(context).apply {
            text = "进入官方反馈交流群"
            textSize = 15f
            setTextColor(colors.primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        })
        groupRow.addView(TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(colors.chevronText)
        })
        moreCard.addView(groupRow)

        container.addView(moreCard)
        return moreCard
    }
}

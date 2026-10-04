package com.copilot.qqpet.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import com.copilot.qqpet.ui.theme.ThemeColors
import com.copilot.qqpet.ui.util.UiAnimUtils

object SettingConfirmDialog {

    fun showConfirmDialog(
        context: Context,
        colors: ThemeColors,
        title: String,
        message: String,
        confirmText: String = "确认执行",
        isDestructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#252528") else Color.WHITE)
                cornerRadius = UiAnimUtils.dp(context, 14).toFloat()
                if (colors.isNight) {
                    setStroke(1, Color.parseColor("#26FFFFFF"))
                }
            }
            setPadding(0, UiAnimUtils.dp(context, 20), 0, 0)
        }

        val titleTv = TextView(context).apply {
            text = title
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.primaryText)
            gravity = Gravity.CENTER
            setPadding(UiAnimUtils.dp(context, 22), 0, UiAnimUtils.dp(context, 22), UiAnimUtils.dp(context, 8))
        }
        card.addView(titleTv)

        val msgTv = TextView(context).apply {
            text = message
            textSize = 13.5f
            setTextColor(if (colors.isNight) Color.parseColor("#AEAEB2") else Color.parseColor("#3C3C43"))
            gravity = Gravity.CENTER
            setPadding(UiAnimUtils.dp(context, 22), 0, UiAnimUtils.dp(context, 22), UiAnimUtils.dp(context, 18))
            setLineSpacing(UiAnimUtils.dp(context, 2).toFloat(), 1.15f)
        }
        card.addView(msgTv)

        card.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiAnimUtils.dp(context, 46))
        }

        val cancelTv = TextView(context).apply {
            text = "取消"
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (colors.isNight) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener {
                dialog.dismiss()
            }
        }
        btnRow.addView(cancelTv)

        btnRow.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
        })

        val confirmColor = if (isDestructive) colors.actionRedText else colors.actionBlueText
        val confirmTv = TextView(context).apply {
            text = confirmText
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(confirmColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            UiAnimUtils.applyTouchSpringEffect(this)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        btnRow.addView(confirmTv)
        card.addView(btnRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.42f)
        }
        dialog.show()
    }
}

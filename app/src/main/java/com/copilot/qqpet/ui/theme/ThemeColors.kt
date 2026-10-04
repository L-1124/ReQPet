package com.copilot.qqpet.ui.theme

import android.content.Context
import android.graphics.Color

data class ThemeColors(
    val isNight: Boolean,
    val pageBg: Int,
    val cardBg: Int,
    val cardBorder: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val sectionHeaderText: Int,
    val chevronText: Int,
    val dividerColor: Int,
    val badgeBg: Int,
    val badgeText: Int,
    val actionPrimaryText: Int,
    val actionBlueText: Int,
    val actionRedText: Int
) {
    companion object {
        fun get(isNight: Boolean): ThemeColors {
            return if (isNight) {
                ThemeColors(
                    isNight = true,
                    pageBg = Color.parseColor("#000000"),
                    cardBg = Color.parseColor("#1C1C1E"),
                    cardBorder = Color.parseColor("#26FFFFFF"),
                    primaryText = Color.parseColor("#FFFFFF"),
                    secondaryText = Color.parseColor("#8E8E93"),
                    sectionHeaderText = Color.parseColor("#8E8E93"),
                    chevronText = Color.parseColor("#545458"),
                    dividerColor = Color.parseColor("#2C2C2E"),
                    badgeBg = Color.parseColor("#173420"),
                    badgeText = Color.parseColor("#32D74B"),
                    actionPrimaryText = Color.parseColor("#FFFFFF"),
                    actionBlueText = Color.parseColor("#0A84FF"),
                    actionRedText = Color.parseColor("#FF453A")
                )
            } else {
                ThemeColors(
                    isNight = false,
                    pageBg = Color.parseColor("#F2F2F7"),
                    cardBg = Color.parseColor("#FFFFFF"),
                    cardBorder = Color.parseColor("#14000000"),
                    primaryText = Color.parseColor("#1C1C1E"),
                    secondaryText = Color.parseColor("#8E8E93"),
                    sectionHeaderText = Color.parseColor("#6C6C70"),
                    chevronText = Color.parseColor("#C7C7CC"),
                    dividerColor = Color.parseColor("#E5E5EA"),
                    badgeBg = Color.parseColor("#EBF9EE"),
                    badgeText = Color.parseColor("#34C759"),
                    actionPrimaryText = Color.parseColor("#1C1C1E"),
                    actionBlueText = Color.parseColor("#007AFF"),
                    actionRedText = Color.parseColor("#FF3B30")
                )
            }
        }

        fun isNightTheme(context: Context): Boolean {
            // 1. 优先尝试 QQ 官方全局 QQTheme.isNowThemeIsNight()
            try {
                val qqThemeClass = context.classLoader.loadClass("com.tencent.mobileqq.utils.QQTheme")
                val method = qqThemeClass.getMethod("isNowThemeIsNight")
                val res = method.invoke(null) as? Boolean
                if (res != null) return res
            } catch (_: Throwable) {}

            // 2. 备选尝试 ThemeUtil.isNowThemeIsNight
            try {
                val themeUtilClass = context.classLoader.loadClass("com.tencent.mobileqq.vas.theme.api.ThemeUtil")
                for (m in themeUtilClass.methods) {
                    if (m.name == "isNowThemeIsNight" && m.parameterTypes.isEmpty()) {
                        val res = m.invoke(null) as? Boolean
                        if (res != null) return res
                    }
                }
            } catch (_: Throwable) {}

            // 3. 兜底回退：跟随系统深色模式
            return try {
                (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
            } catch (_: Throwable) {
                false
            }
        }
    }
}

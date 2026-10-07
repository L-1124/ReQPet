package com.copilot.qqpet.ui.theme

import android.content.Context
import android.content.res.Configuration

/** 宿主（QQ）当前是否深色皮肤；配色本身交给 Material3 动态色。 */
object HostTheme {

    /**
     * 宿主品牌主题色（QUI brand_standard token）。
     * 走宿主 Resources 按名字解析，换肤引擎在 Resources 层替换，自动跟随当前皮肤与夜间模式。
     */
    fun brandColor(context: Context): Int? {
        return try {
            val res = context.resources
            val id = res.getIdentifier("qui_brand_standard", "color", context.packageName)
            if (id == 0) null else res.getColorStateList(id, null).defaultColor
        } catch (_: Throwable) {
            null
        }
    }

    fun isNight(context: Context): Boolean {
        try {
            val qqThemeClass = context.classLoader.loadClass("com.tencent.mobileqq.utils.QQTheme")
            val method = qqThemeClass.getMethod("isNowThemeIsNight")
            val res = method.invoke(null) as? Boolean
            if (res != null) return res
        } catch (_: Throwable) {
        }

        try {
            val themeUtilClass = context.classLoader.loadClass("com.tencent.mobileqq.vas.theme.api.ThemeUtil")
            for (m in themeUtilClass.methods) {
                if (m.name == "isNowThemeIsNight" && m.parameterTypes.isEmpty()) {
                    val res = m.invoke(null) as? Boolean
                    if (res != null) return res
                }
            }
        } catch (_: Throwable) {
        }

        return try {
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            false
        }
    }
}
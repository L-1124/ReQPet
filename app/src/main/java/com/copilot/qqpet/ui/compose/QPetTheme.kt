package com.copilot.qqpet.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.copilot.qqpet.ui.theme.HostTheme

/**
 * MD3 Expressive 主题：light 用官方 expressive 色板，dark 用标准基准色板；
 * shapes/typography 走 MaterialExpressiveTheme 默认 token。不用系统动态色——
 * 宿主进程里 dynamicColorScheme 解析出全 0（整页变黑）。
 * primary 系优先染上宿主品牌色（QUI brand_standard），解析失败回退官方色板。
 */
@Composable
fun QPetExpressiveTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val base = if (dark) darkColorScheme() else expressiveLightColorScheme()
    val colorScheme = HostTheme.brandColor(context)?.let { brand ->
        base.copy(
            primary = Color(brand),
            onPrimary = Color.White
        )
    } ?: base
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

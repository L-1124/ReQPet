package com.copilot.qqpet.ui.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.copilot.qqpet.ui.theme.HostTheme
import kotlin.math.max
import kotlin.math.min

private fun contrastRatio(l1: Float, l2: Float): Float =
    (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)

// 项目设计决策：15% 品牌色混色避免引入 MCU，非官方 HCT 色板；经相对亮度成对校验保证 WCAG > 4.5:1。
internal fun deriveBrandColorScheme(baseScheme: ColorScheme, brandColor: Color, dark: Boolean): ColorScheme {
    val opaqueBrand = brandColor.copy(alpha = 1.0f)
    val lBrand = opaqueBrand.luminance()
    val crWhitePrimary = contrastRatio(1.0f, lBrand)
    val crBlackPrimary = contrastRatio(lBrand, 0.0f)
    val onPrimary = if (crWhitePrimary >= crBlackPrimary) Color.White else Color.Black

    val containerColor = opaqueBrand.copy(alpha = 0.15f).compositeOver(baseScheme.surface)
    val lContainer = containerColor.luminance()

    val onContainerColor = if (dark) {
        val tinted = opaqueBrand.copy(alpha = 0.4f).compositeOver(Color.White)
        if (contrastRatio(tinted.luminance(), lContainer) >= 4.5f) tinted else Color.White
    } else {
        var factor = 0.6f
        var candidate = Color(opaqueBrand.red * factor, opaqueBrand.green * factor, opaqueBrand.blue * factor, 1.0f)
        while (factor > 0.1f && contrastRatio(candidate.luminance(), lContainer) < 4.5f) {
            factor -= 0.1f
            candidate = Color(opaqueBrand.red * factor, opaqueBrand.green * factor, opaqueBrand.blue * factor, 1.0f)
        }
        if (contrastRatio(candidate.luminance(), lContainer) >= 4.5f) candidate else Color.Black
    }

    return baseScheme.copy(
        primary = opaqueBrand,
        onPrimary = onPrimary,
        primaryContainer = containerColor,
        onPrimaryContainer = onContainerColor
    )
}

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
    val brand = HostTheme.brandColor(context)
    val colorScheme = remember(dark, brand) {
        brand?.let { deriveBrandColorScheme(base, Color(it), dark) } ?: base
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

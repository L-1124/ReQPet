package com.copilot.qqpet.ui.compose

import android.app.Activity
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.copilot.qqpet.ui.theme.HostTheme

/**
 * MD3 Expressive 主题：light 用官方 expressive 色板，dark 用标准基准色板；
 * shapes/typography 走 MaterialExpressiveTheme 默认 token。不用系统动态色——
 * 宿主进程里 dynamicColorScheme 解析出全 0（整页变黑）。
 * primary 系优先染上宿主品牌色（QUI brand_standard），解析失败回退官方色板。
 */
@Composable
fun QPetExpressiveTheme(dark: Boolean, content: @Composable () -> Unit) {
    SystemBarAppearanceEffect(dark)
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

/** 状态栏图标明暗跟随主题；系统在切前台后可能重置，故 ON_RESUME 时重新应用。 */
@Composable
private fun SystemBarAppearanceEffect(dark: Boolean) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(view, lifecycleOwner, dark) {
        val activity = view.context as? Activity ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(activity.window, view)
        fun apply() {
            controller.isAppearanceLightStatusBars = !dark
        }
        apply()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apply()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
package com.copilot.qqpet.ui.compose

import android.app.Activity
import android.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * MD3 Expressive 主题。配色不取系统动态色：注入在宿主进程里
 * dynamicLight/DarkColorScheme 会解析出全 0（整页变黑），故用固定种子配色。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QPetExpressiveTheme(dark: Boolean, content: @Composable () -> Unit) {
    SystemBarAppearanceEffect(dark)
    MaterialExpressiveTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

private val LightScheme = lightColorScheme(
    primary = ComposeColor(0xFF007AFF),
    onPrimary = ComposeColor(0xFFFFFFFF),
    primaryContainer = ComposeColor(0xFFD6E4FF),
    onPrimaryContainer = ComposeColor(0xFF001A41)
)

private val DarkScheme = darkColorScheme(
    primary = ComposeColor(0xFF0A84FF),
    onPrimary = ComposeColor(0xFF00305F),
    primaryContainer = ComposeColor(0xFF00458A),
    onPrimaryContainer = ComposeColor(0xFFD6E4FF)
)

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
    SideEffect {
        @Suppress("DEPRECATION")
        (view.context as? Activity)?.window?.statusBarColor = Color.TRANSPARENT
    }
}
package com.copilot.qqpet

/**
 * 调试总开关。
 *
 * [SAFE_MODE] = true 时，模块**只做界面注入与日志，不发任何业务包**：
 * - 兜底闸门在 [com.copilot.qqpet.protocol.channel.OidbChannel.sendOidb]，任何发包都会被拦截并回 -100；
 * - 主循环不会被启动 / 唤醒，避免空转与刷屏；
 * - 设置页、日志面板、开关读写等本地功能照常可用。
 *
 * 调试结束、准备恢复自动化时，把下面改回 false 即可。
 */
object DebugSwitches {
    const val SAFE_MODE = true
}

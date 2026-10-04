package com.copilot.qqpet.hook

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 统一日志输出控制器：默认对 LSPosed 框架日志和 logcat 保持完全静默，
 * 仅当用户主动在设置中开启「调试模式日志」时才向 XposedBridge / Logcat 打印。
 */
object HookLog {

    @Volatile
    var isDebugEnabled: Boolean = false

    fun log(tag: String, msg: String) {
        if (isDebugEnabled) {
            try {
                XposedBridge.log("[$tag] $msg")
            } catch (_: Throwable) {
                Log.i(tag, msg)
            }
        }
    }

    fun log(msg: String) {
        log("QQPetCopilot", msg)
    }

    fun d(tag: String, msg: String) = log(tag, msg)
    fun i(tag: String, msg: String) = log(tag, msg)
    fun w(tag: String, msg: String) = log(tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(tag, if (t != null) "$msg: ${t.message}" else msg)
}

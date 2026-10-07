package com.copilot.qqpet.hook

import android.util.Log

/**
 * 统一日志输出控制器：默认对框架日志和 logcat 保持完全静默，
 * 仅当用户主动在设置中开启「调试模式日志」时才向 libxposed 日志 / Logcat 打印。
 */
object HookLog {

    @Volatile
    var isDebugEnabled: Boolean = false

    fun log(tag: String, msg: String) {
        if (isDebugEnabled) {
            try {
                HookApi.log(Log.INFO, tag, msg)
            } catch (_: Throwable) {
                Log.i(tag, msg)
            }
        }
    }

    fun log(msg: String) {
        log("QQPetCopilot", msg)
    }

    /**
     * 注入链路诊断：**不受调试开关门控**，直接写 logcat。
     * 只用在"入口/钩子是否生效"这类极低频且无法靠界面观察的路径上。
     */
    fun trace(tag: String, msg: String, t: Throwable? = null) {
        try {
            val suffix = if (t != null) " -> ${t.javaClass.name}: ${t.message}\n${Log.getStackTraceString(t)}" else ""
            Log.i(TRACE_TAG, "[$tag] $msg$suffix")
        } catch (_: Throwable) {
        }
    }

    private const val TRACE_TAG = "QQPetTrace"

    fun d(tag: String, msg: String) = log(tag, msg)
    fun i(tag: String, msg: String) = log(tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (isDebugEnabled) {
            val trace = if (t != null) "\n" + Log.getStackTraceString(t) else ""
            log(tag, "$msg$trace")
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        val trace = if (t != null) "\n" + Log.getStackTraceString(t) else ""
        val fullMsg = "$msg$trace"
        try {
            HookApi.log(Log.ERROR, tag, fullMsg)
        } catch (_: Throwable) {
        }
        Log.e(tag, fullMsg)
    }
}
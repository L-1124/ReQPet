package com.copilot.qqpet.ui

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内部轻量平台 Trace 辅助工具：保证切片与跨度在异常与协程取消下严格平衡闭合，并适配纯 JVM 测试环境。
 */
internal object SettingsTrace {
    private val asyncCookieGen = AtomicInteger(1)

    fun nextAsyncCookie(): Int = asyncCookieGen.getAndIncrement()

    inline fun <T> trace(sectionName: String, block: () -> T): T {
        val started = begin(sectionName)
        try {
            return block()
        } finally {
            if (started) {
                end()
            }
        }
    }

    fun begin(sectionName: String): Boolean {
        return try {
            Trace.beginSection(sectionName)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun end() {
        try {
            Trace.endSection()
        } catch (_: Throwable) {}
    }

    fun instant(sectionName: String) {
        if (begin(sectionName)) {
            end()
        }
    }

    fun beginAsync(methodName: String, cookie: Int): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.beginAsyncSection(methodName, cookie)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun endAsync(methodName: String, cookie: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection(methodName, cookie)
            }
        } catch (_: Throwable) {}
    }
}

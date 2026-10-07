package com.copilot.qqpet.hook

import android.content.Context
import android.util.Log

object CrashInterceptor {

    private const val TAG = "QQPetFatal"
    private const val MODULE_PACKAGE_FRAGMENT = "copilot.qqpet"

    fun install(@Suppress("UNUSED_PARAMETER") context: Context? = null) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is StealthHandler) return
        Thread.setDefaultUncaughtExceptionHandler(StealthHandler(current))
    }

    private class StealthHandler(
        private val parent: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            val trace = Log.getStackTraceString(throwable)
            if (trace.contains(MODULE_PACKAGE_FRAGMENT)) {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}\n$trace")
            }
            parent?.uncaughtException(thread, throwable)
        }
    }
}

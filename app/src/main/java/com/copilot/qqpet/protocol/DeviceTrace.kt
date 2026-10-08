package com.copilot.qqpet.protocol

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 把少量协议字段日志写到 logcat 标签 QPetTrace，并追加到
 * Android/data/com.tencent.mobileqq/files/qpet-trace/trace.log，便于 adb 读取。
 */
object DeviceTrace {
    const val TAG = "QPetTrace"

    @Volatile
    var appContext: Context? = null

    fun bind(context: Context?) {
        appContext = context?.applicationContext ?: context
    }

    private val writeLock = Any()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "QPetTrace-IO").apply { isDaemon = true }
    }

    fun i(msg: String) {
        val text = msg.replace('\n', ' ')
        val chunks = if (text.length <= LINE_LIMIT) listOf(text) else text.chunked(LINE_LIMIT)
        chunks.forEachIndexed { index, chunk ->
            val line = if (chunks.size == 1) chunk else "(${index + 1}/${chunks.size})$chunk"
            Log.i(TAG, line.take(3500))
            dispatchAppend(line)
        }
    }

    private fun dispatchAppend(line: String) {
        try {
            ioExecutor.execute { appendLine(line) }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 线程池异常或关闭时兜底：非主线程才尝试同步追加，避免在主线程发生阻塞
            if (!isMainThread()) {
                appendLine(line)
            }
        }
    }

    private fun isMainThread(): Boolean {
        return try {
            Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()
        } catch (_: Throwable) {
            false
        }
    }

    private fun appendLine(line: String) {
        val ctx = appContext ?: return
        try {
            synchronized(writeLock) {
                val dir = ctx.getExternalFilesDir("qpet-trace") ?: return
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "trace.log")
                if (file.length() > FILE_LIMIT) file.writeText("")
                file.appendText(line + "\n")
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private const val LINE_LIMIT = 8000
    private const val FILE_LIMIT = 1_000_000L
}

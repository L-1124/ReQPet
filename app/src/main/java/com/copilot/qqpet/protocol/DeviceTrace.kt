package com.copilot.qqpet.protocol

import android.content.Context
import android.util.Log
import java.io.File

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

    fun i(msg: String) {
        val text = msg.replace('\n', ' ')
        val chunks = if (text.length <= LINE_LIMIT) listOf(text) else text.chunked(LINE_LIMIT)
        chunks.forEachIndexed { index, chunk ->
            val line = if (chunks.size == 1) chunk else "(${index + 1}/${chunks.size})$chunk"
            Log.i(TAG, line.take(3500))
            appendLine(line)
        }
    }

    private fun appendLine(line: String) {
        val ctx = appContext ?: return
        try {
            val dir = ctx.getExternalFilesDir("qpet-trace") ?: return
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "trace.log")
            if (file.length() > FILE_LIMIT) file.writeText("")
            file.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    private const val LINE_LIMIT = 8000
    private const val FILE_LIMIT = 1_000_000L
}

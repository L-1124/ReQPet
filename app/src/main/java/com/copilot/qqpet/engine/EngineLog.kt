package com.copilot.qqpet.engine

import com.copilot.qqpet.hook.HookLog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/** 模块统一日志出口：内存环形缓冲 + 监听器订阅，调试开关开启时同时写 Xposed 日志。 */
object EngineLog {

    private const val TAG = "QQPetCopilot"

    /** 缓冲上限 */
    const val CAPACITY = 200

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val buffer = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun i(message: String) = write(message)
    fun w(message: String) = write(message)
    fun e(message: String) = write(message)

    private fun write(message: String) {
        val line = synchronized(buffer) {
            val stamped = "[${LocalTime.now().format(timeFormatter)}] $message"
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(stamped)
            stamped
        }
        HookLog.log(TAG, message)
        for (listener in listeners) {
            try { listener(line) } catch (_: Throwable) {}
        }
    }

    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    fun addListener(listener: (String) -> Unit) { listeners.addIfAbsent(listener) }

    fun removeListener(listener: (String) -> Unit) { listeners.remove(listener) }
}

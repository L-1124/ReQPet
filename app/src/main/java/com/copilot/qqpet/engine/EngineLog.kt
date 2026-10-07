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
    private val buffer = ArrayDeque<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    fun i(message: String) = write(Level.INFO, TAG, message)
    fun w(message: String) = write(Level.WARN, TAG, message)
    fun e(message: String) = write(Level.ERROR, TAG, message)

    /** 带来源标签的输出：来源与级别随条目结构化保留 */
    fun d(tag: String, message: String) = write(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = write(Level.INFO, tag, message)
    fun w(tag: String, message: String) = write(Level.WARN, tag, message)
    fun e(tag: String, message: String) = write(Level.ERROR, tag, message)

    private fun write(level: Level, source: String, message: String) {
        val entry = LogEntry(
            time = LocalTime.now().format(timeFormatter),
            level = level,
            source = source,
            message = message
        )
        val stamped = synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            entry
        }
        HookLog.log(TAG, "[${stamped.source}] ${stamped.message}")
        for (listener in listeners) {
            try {
                listener(stamped)
            } catch (_: Throwable) {
            }
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }
}

/** 结构化日志条目：级别与来源供 UI 着色与过滤，time 为渲染用时分秒文本 */
data class LogEntry(
    val time: String,
    val level: EngineLog.Level,
    val source: String,
    val message: String
)

package com.copilot.qqpet.engine

import com.copilot.qqpet.hook.HookLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 模块统一日志出口：内存环形缓冲 + 监听器订阅，调试开关开启时同时写 Xposed 日志。 */
object EngineLog {

    const val TAG = "QQPetCopilot"

    /** 缓冲上限 */
    const val CAPACITY = 200

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val buffer = ArrayDeque<LogEntry>()
    private val _logFlow = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    fun i(message: String) = write(Level.INFO, TAG, message)
    fun w(message: String) = write(Level.WARN, TAG, message)
    fun e(message: String) = write(Level.ERROR, TAG, message)

    /** 带级别单参数出口：message 即正文，调用方不再用 emoji 表达级别 */
    fun write(level: Level, message: String) = write(level, TAG, message)

    /** 带来源标签的输出：来源与级别随条目结构化保留 */
    fun d(tag: String, message: String) {
        if (HookLog.isDebugEnabled) write(Level.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) = write(Level.INFO, tag, message)
    fun w(tag: String, message: String) = write(Level.WARN, tag, message)
    fun e(tag: String, message: String) = write(Level.ERROR, tag, message)

    inline fun d(tag: String = TAG, lazyMsg: () -> String) {
        if (HookLog.isDebugEnabled) write(Level.DEBUG, tag, lazyMsg())
    }

    inline fun i(tag: String = TAG, lazyMsg: () -> String) {
        write(Level.INFO, tag, lazyMsg())
    }

    inline fun w(tag: String = TAG, lazyMsg: () -> String) {
        write(Level.WARN, tag, lazyMsg())
    }

    inline fun e(tag: String = TAG, lazyMsg: () -> String) {
        write(Level.ERROR, tag, lazyMsg())
    }

    fun write(level: Level, source: String, message: String) {
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
        HookLog.log(TAG, "[${stamped.source}] ${level.tag} ${stamped.message}")
        _logFlow.tryEmit(stamped)
    }

    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }


    enum class Level(val tag: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }
}

/** 结构化日志条目：级别与来源供 UI 着色与过滤，time 为渲染用时分秒文本 */
data class LogEntry(
    val time: String,
    val level: EngineLog.Level,
    val source: String,
    val message: String
)

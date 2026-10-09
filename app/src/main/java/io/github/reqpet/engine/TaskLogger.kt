package io.github.reqpet.engine

/** 任务层日志出口：级别显式声明，调用点不再用 emoji 表达警告与错误 */
fun interface TaskLogger {
    operator fun invoke(message: String) = log(EngineLog.Level.INFO, message)

    fun log(level: EngineLog.Level, message: String)

    fun warn(message: String) = log(EngineLog.Level.WARN, message)

    fun error(message: String) = log(EngineLog.Level.ERROR, message)
}

package com.copilot.qqpet.hook

import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * libxposed 的 hook/log 都是 XposedModule 的实例方法，这里把入口注册进来的能力暴露给模块内的工具类。
 */
object HookApi {

    @Volatile
    private var hooker: ((Executable) -> XposedInterface.HookBuilder)? = null

    @Volatile
    private var deoptimizer: ((Executable) -> Boolean)? = null

    @Volatile
    private var logger: ((Int, String, String) -> Unit)? = null

    fun attach(
        hooker: (Executable) -> XposedInterface.HookBuilder,
        deoptimizer: ((Executable) -> Boolean)? = null,
        logger: (Int, String, String) -> Unit
    ) {
        this.hooker = hooker
        this.deoptimizer = deoptimizer
        this.logger = logger
    }

    fun deoptimize(executable: Executable): Boolean =
        deoptimizer?.invoke(executable) ?: false

    fun hook(
        executable: Executable,
        id: String? = null,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
        deoptimizeFirst: Boolean = true
    ): XposedInterface.HookBuilder {
        if (deoptimizeFirst) {
            deoptimize(executable)
        }
        val builder = requireNotNull(hooker) { "HookApi 未初始化：onModuleLoaded 尚未执行" }.invoke(executable)
        if (id != null) {
            builder.setId(id)
        }
        builder.setPriority(priority)
        builder.setExceptionMode(exceptionMode)
        return builder
    }

    fun log(priority: Int, tag: String, message: String) {
        logger?.invoke(priority, tag, message)
    }
}
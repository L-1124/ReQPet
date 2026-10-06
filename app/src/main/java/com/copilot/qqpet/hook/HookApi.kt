package com.copilot.qqpet.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * libxposed 的 hook/log 都是 XposedModule 的实例方法，这里把入口注册进来的能力暴露给模块内的工具类。
 */
object HookApi {

    @Volatile
    private var hooker: ((Executable) -> XposedInterface.HookBuilder)? = null

    @Volatile
    private var logger: ((Int, String, String) -> Unit)? = null

    fun attach(
        hooker: (Executable) -> XposedInterface.HookBuilder,
        logger: (Int, String, String) -> Unit
    ) {
        this.hooker = hooker
        this.logger = logger
    }

    fun hook(executable: Executable): XposedInterface.HookBuilder =
        requireNotNull(hooker) { "HookApi 未初始化：onModuleLoaded 尚未执行" }.invoke(executable)

    fun log(priority: Int, tag: String, message: String) {
        logger?.invoke(priority, tag, message)
    }
}
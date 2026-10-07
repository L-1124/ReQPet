package com.copilot.qqpet.hook

import android.content.Context
import android.content.Intent
import com.copilot.qqpet.ui.PreferencesHelper

object TinkerBlocker {

    private const val TAG = "TinkerBlocker"
    @Volatile
    private var isHooked = false

    fun isTinkerDisabled(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            prefs.getBoolean(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    fun install(classLoader: ClassLoader, context: Context? = null) {
        if (isHooked) return

        // 1. ShareTinkerInternals (核心状态与开关判断)
        try {
            val shareInternalsCls = Class.forName("com.tencent.tinker.loader.shareutil.ShareTinkerInternals", false, classLoader)
            for (method in shareInternalsCls.declaredMethods) {
                if (method.name == "isTinkerEnableWithSharedPreferences" &&
                    method.parameterTypes.size == 1 &&
                    Context::class.java.isAssignableFrom(method.parameterTypes[0])
                ) {
                    HookApi.hook(method).intercept { chain ->
                        val ctx = chain.getArg(0) as? Context
                        if (isTinkerDisabled(ctx ?: context)) {
                            HookLog.log(TAG, "[TinkerBlocker] isTinkerEnableWithSharedPreferences 被拦截，强制返回 false")
                            false
                        } else {
                            chain.proceed()
                        }
                    }
                } else if (method.name == "isTinkerEnabled" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
                ) {
                    HookApi.hook(method).intercept { chain ->
                        if (isTinkerDisabled(context)) false else chain.proceed()
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e}

        // 2. Tinker (上层单例与管理器)
        try {
            val tinkerCls = Class.forName("com.tencent.tinker.lib.tinker.Tinker", false, classLoader)
            for (method in tinkerCls.declaredMethods) {
                if (method.name == "isTinkerEnabled" && method.parameterTypes.isEmpty()) {
                    HookApi.hook(method).intercept { chain ->
                        val tinkerObj = chain.thisObject
                        val ctx = try {
                            tinkerCls.getMethod("getContext").invoke(tinkerObj) as? Context
                        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
                            null
                        }
                        if (isTinkerDisabled(ctx ?: context)) {
                            HookLog.log(TAG, "[TinkerBlocker] Tinker.isTinkerEnabled() 被拦截，强制返回 false")
                            false
                        } else {
                            chain.proceed()
                        }
                    }
                } else if (method.name == "isTinkerLoaded" && method.parameterTypes.isEmpty()) {
                    HookApi.hook(method).intercept { chain ->
                        if (isTinkerDisabled(context)) false else chain.proceed()
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e}

        // 3. TinkerInstaller (拦截外部补丁升级下发请求)
        try {
            val installerCls = Class.forName("com.tencent.tinker.lib.tinker.TinkerInstaller", false, classLoader)
            for (method in installerCls.declaredMethods) {
                if (method.name == "onReceiveUpgradePatch" &&
                    method.parameterTypes.size == 2 &&
                    Context::class.java.isAssignableFrom(method.parameterTypes[0])
                ) {
                    HookApi.hook(method).intercept { chain ->
                        val ctx = chain.getArg(0) as? Context
                        if (isTinkerDisabled(ctx ?: context)) {
                            HookLog.log(TAG, "[TinkerBlocker] 成功拦截云端下发的 onReceiveUpgradePatch 补丁升级请求！")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e}

        // 4. TinkerLoader (拦截冷启动时的补丁加载 tryLoad)
        try {
            val loaderCls = Class.forName("com.tencent.tinker.loader.TinkerLoader", false, classLoader)
            for (method in loaderCls.declaredMethods) {
                if (method.name == "tryLoad" && method.parameterTypes.size == 1) {
                    HookApi.hook(method).intercept { chain ->
                        val appObj = chain.getArg(0) as? Context
                        if (isTinkerDisabled(appObj ?: context)) {
                            HookLog.log(TAG, "[TinkerBlocker] TinkerLoader.tryLoad 被拦截，直接阻断补丁加载流程")
                            Intent().apply { putExtra("intent_return_code", -1) }
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e}

        isHooked = true
        HookLog.log(TAG, "TinkerBlocker 动态拦截器就绪 (libxposed)")
    }
}
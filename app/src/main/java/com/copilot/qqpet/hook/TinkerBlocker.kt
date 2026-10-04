package com.copilot.qqpet.hook

import android.content.Context
import android.content.Intent
import com.copilot.qqpet.ui.PreferencesHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object TinkerBlocker {

    private const val TAG = "TinkerBlocker"
    @Volatile
    private var isHooked = false

    fun isTinkerDisabled(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            prefs.getBoolean(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false)
        } catch (_: Throwable) {
            false
        }
    }

    fun install(classLoader: ClassLoader, context: Context? = null) {
        if (isHooked) return

        // 1. Hook ShareTinkerInternals (核心状态与开关判断)
        try {
            val shareInternalsCls = Class.forName("com.tencent.tinker.loader.shareutil.ShareTinkerInternals", false, classLoader)
            for (method in shareInternalsCls.declaredMethods) {
                if (method.name == "isTinkerEnableWithSharedPreferences" &&
                    method.parameterTypes.size == 1 &&
                    Context::class.java.isAssignableFrom(method.parameterTypes[0])
                ) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val ctx = param.args.getOrNull(0) as? Context
                            if (isTinkerDisabled(ctx ?: context)) {
                                HookLog.log(TAG, "🛡️ [TinkerBlocker] isTinkerEnableWithSharedPreferences 被拦截，强制返回 false")
                                param.result = false
                            }
                        }
                    })
                } else if (method.name == "isTinkerEnabled" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
                ) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isTinkerDisabled(context)) {
                                param.result = false
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 2. Hook Tinker (上层单例与管理器)
        try {
            val tinkerCls = Class.forName("com.tencent.tinker.lib.tinker.Tinker", false, classLoader)
            for (method in tinkerCls.declaredMethods) {
                if (method.name == "isTinkerEnabled" && method.parameterTypes.isEmpty()) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val tinkerObj = param.thisObject
                            val ctx = try {
                                val getContextMethod = tinkerCls.getMethod("getContext")
                                getContextMethod.invoke(tinkerObj) as? Context
                            } catch (_: Throwable) {
                                null
                            }
                            if (isTinkerDisabled(ctx ?: context)) {
                                HookLog.log(TAG, "🛡️ [TinkerBlocker] Tinker.isTinkerEnabled() 被拦截，强制返回 false")
                                param.result = false
                            }
                        }
                    })
                } else if (method.name == "isTinkerLoaded" && method.parameterTypes.isEmpty()) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isTinkerDisabled(context)) {
                                param.result = false
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 3. Hook TinkerInstaller (拦截外部补丁升级下发请求)
        try {
            val installerCls = Class.forName("com.tencent.tinker.lib.tinker.TinkerInstaller", false, classLoader)
            for (method in installerCls.declaredMethods) {
                if (method.name == "onReceiveUpgradePatch" &&
                    method.parameterTypes.size == 2 &&
                    Context::class.java.isAssignableFrom(method.parameterTypes[0])
                ) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val ctx = param.args.getOrNull(0) as? Context
                            if (isTinkerDisabled(ctx ?: context)) {
                                HookLog.log(TAG, "🛡️ [TinkerBlocker] 成功拦截云端下发的 onReceiveUpgradePatch 补丁升级请求！")
                                param.result = null
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 4. Hook TinkerLoader (拦截冷启动时的补丁加载 tryLoad)
        try {
            val loaderCls = Class.forName("com.tencent.tinker.loader.TinkerLoader", false, classLoader)
            for (method in loaderCls.declaredMethods) {
                if (method.name == "tryLoad" && method.parameterTypes.size == 1) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val appObj = param.args.getOrNull(0) as? Context
                            if (isTinkerDisabled(appObj ?: context)) {
                                HookLog.log(TAG, "🛡️ [TinkerBlocker] TinkerLoader.tryLoad 被拦截，直接阻断补丁加载流程")
                                val intent = Intent()
                                intent.putExtra("intent_return_code", -1)
                                param.result = intent
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        isHooked = true
        HookLog.log(TAG, "TinkerBlocker 动态拦截器就绪 (API 82)")
    }
}

package com.copilot.qqpet

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.hook.HookLog
import com.copilot.qqpet.hook.QQSettingInjector
import com.copilot.qqpet.hook.TinkerBlocker
import com.copilot.qqpet.protocol.PacketSniffer
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*

class HookEntry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "QQPetCopilot"
        const val TARGET_PACKAGE = "com.tencent.mobileqq"
        const val MODULE_PACKAGE = "io.github.congsmile.qqpet"

        @Volatile
        var instance: HookEntry? = null
            private set

        @Volatile
        private var isSplashHooked = false
        private var isReadySignalled = false
        @Volatile
        private var loginPollJob: Job? = null
        @Volatile
        var globalEngine: PetAdventureEngine? = null
        @Volatile
        var globalBridge: QQPetDirectBridge? = null
        @Volatile
        var latestClassLoader: ClassLoader? = null

        fun reconnectBridgeIfAvailable(context: Context): Boolean {
            val entry = instance ?: return false
            val loader = latestClassLoader ?: context.classLoader ?: return false
            return entry.initEngineAndReceiver(context, loader, "自愈重连")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 仅拦截目标应用 QQ 主进程，模块自身不启动任何界面或逻辑。
        if (lpparam.packageName == MODULE_PACKAGE) return
        if (lpparam.packageName != TARGET_PACKAGE) {
            return
        }
        if (lpparam.processName != TARGET_PACKAGE) {
            HookLog.log(TAG, "跳过 QQ 非主进程: ${lpparam.processName}")
            return
        }

        instance = this
        latestClassLoader = lpparam.classLoader
        HookLog.log(TAG, "成功注入 QQ 主进程: ${lpparam.processName}, PID=${android.os.Process.myPid()} (API 82 经典引擎)")
        TinkerBlocker.install(lpparam.classLoader)

        // 挂钩 1: BaseApplicationImpl.onCreate (获取真实分包完成后的 ClassLoader)
        try {
            val baseAppCls = lpparam.classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            XposedHelpers.findAndHookMethod(
                baseAppCls,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Context ?: return
                        val appLoader = app.classLoader
                        latestClassLoader = appLoader
                        HookLog.log(TAG, "BaseApplicationImpl.onCreate 触发, classLoader=$appLoader")
                        initEngineAndReceiver(app, appLoader, "BaseApplicationImpl.onCreate")
                        hookSplashActivity(appLoader)
                        QQSettingInjector.inject(appLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook BaseApplicationImpl 异常: ${t.message}")
        }

        // 挂钩 2: MobileQQ.onCreate
        try {
            XposedHelpers.findAndHookMethod(
                "mqq.app.MobileQQ",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context ?: return
                        latestClassLoader = context.classLoader
                        initEngineAndReceiver(context, context.classLoader, "MobileQQ.onCreate")
                        hookSplashActivity(context.classLoader)
                        QQSettingInjector.inject(context.classLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook MobileQQ.onCreate 异常: ${t.message}")
        }

        // 挂钩 3: 针对通用 Activity.onCreate 提供超轻量单次设置项保底注入
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName == TARGET_PACKAGE) {
                            latestClassLoader = activity.classLoader
                            if (!QQSettingInjector.isHooked) {
                                QQSettingInjector.inject(activity.classLoader)
                            }
                            if (globalBridge?.isReady != true) {
                                val appContext = activity.applicationContext ?: activity
                                initEngineAndReceiver(appContext, activity.classLoader, "Activity.onCreate[${activity.javaClass.simpleName}]")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook Activity.onCreate 设置保底异常: ${t.message}")
        }

        // 挂钩 4: 针对 QQ 主界面 SplashActivity 触发保活、设置注入与会话校准
        hookSplashActivity(lpparam.classLoader)
    }

    private fun hookSplashActivity(classLoader: ClassLoader) {
        if (isSplashHooked) return
        try {
            val splashCls = classLoader.loadClass("com.tencent.mobileqq.activity.SplashActivity")
            XposedHelpers.findAndHookMethod(
                splashCls,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName == TARGET_PACKAGE) {
                            val appContext = activity.applicationContext ?: activity
                            latestClassLoader = activity.classLoader
                            QQSettingInjector.inject(activity.classLoader)
                            if (globalBridge?.isReady != true) {
                                initEngineAndReceiver(appContext, activity.classLoader, "SplashActivity.onResume")
                            }
                            globalEngine?.verifyAndSyncAccountSession(appContext)
                            globalEngine?.startBackgroundLoop(appContext)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                splashCls,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName == TARGET_PACKAGE) {
                            val appContext = activity.applicationContext ?: activity
                            latestClassLoader = activity.classLoader
                            QQSettingInjector.inject(activity.classLoader)
                            if (globalBridge?.isReady != true) {
                                initEngineAndReceiver(appContext, activity.classLoader, "SplashActivity.onCreate")
                            }
                        }
                    }
                }
            )
            isSplashHooked = true
            HookLog.log(TAG, "已成功挂钩 SplashActivity 主界面保活与设置项注入 (API 82)")
        } catch (_: Throwable) {}
    }

    fun initEngineAndReceiver(context: Context, classLoader: ClassLoader, from: String): Boolean {
        val appContext = context.applicationContext ?: context

        try {
            val prefs = appContext.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            HookLog.isDebugEnabled = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
        } catch (_: Throwable) {}

        if (globalEngine == null || globalBridge?.isReady != true) {
            try {
                val bridge = QQPetDirectBridge(classLoader, appContext)
                if (bridge.isReady) {
                    globalBridge = bridge
                    if (globalEngine == null) {
                        globalEngine = PetAdventureEngine(bridge).apply {
                            reloadConfig(appContext)
                        }
                    } else {
                        globalEngine?.updateBridge(bridge)
                    }
                    HookLog.log(TAG, "冒险探索发包内核就绪 (来源: $from, 类: ${QQPetDirectBridge.resolvedDelegateClass?.name})")
                } else if (globalEngine == null) {
                    globalBridge = bridge
                    globalEngine = PetAdventureEngine(bridge).apply {
                        reloadConfig(appContext)
                    }
                    HookLog.log(TAG, "发包内核暂未就绪，等待后续分包触发 (来源: $from)")
                }
            } catch (t: Throwable) {
                HookLog.log(TAG, "初始化发包内核失败: ${t.message}")
            }
        }

        try {
            PacketSniffer.install(classLoader, appContext)
        } catch (t: Throwable) {
            HookLog.log(TAG, "启动 PacketSniffer 异常: ${t.message}")
        }

        TinkerBlocker.install(classLoader, appContext)

        if (!isReadySignalled) {
            isReadySignalled = true
            globalEngine?.sendReadySignal(appContext)
        }

        checkLoginAndStartLoop(appContext, classLoader, from)
        return globalBridge?.isReady == true
    }

    private fun checkLoginAndStartLoop(appContext: Context, classLoader: ClassLoader, from: String) {
        if (tryStartLoopIfLoggedIn(appContext, classLoader, from)) {
            return
        }

        if (loginPollJob?.isActive == true) return
        loginPollJob = CoroutineScope(Dispatchers.IO).launch {
            val retryDelays = longArrayOf(1500L, 3000L, 5000L, 8000L, 12000L, 20000L, 30000L)
            for (delayMs in retryDelays) {
                delay(delayMs)
                if (PetAdventureEngine.isLoopRunning) break
                val started = tryStartLoopIfLoggedIn(appContext, classLoader, "异步复检:$from")
                if (started) break
            }
        }
    }

    private fun tryStartLoopIfLoggedIn(appContext: Context, classLoader: ClassLoader, from: String): Boolean {
        try {
            val mobileQQClass = classLoader.loadClass("mqq.app.MobileQQ")
            val sMobileQQField = mobileQQClass.getDeclaredField("sMobileQQ").apply { isAccessible = true }
            val sMobileQQ = sMobileQQField.get(null) ?: return false
            val peekMethod = sMobileQQ.javaClass.getMethod("peekAppRuntime")
            val runtime = peekMethod.invoke(sMobileQQ) ?: return false
            val isLoginMethod = runtime.javaClass.getMethod("isLogin")
            val isLogin = isLoginMethod.invoke(runtime) as? Boolean ?: false
            if (isLogin) {
                val getUinMethod = runtime.javaClass.getMethod("getCurrentAccountUin")
                val uin = getUinMethod.invoke(runtime) as? String
                HookLog.log(TAG, "QQ 账号已登录: UIN=$uin (来源: $from)，自动启动后台常驻探险轮询！")
                globalEngine?.startBackgroundLoop(appContext)
                return true
            }
        } catch (t: Throwable) {
            HookLog.log(TAG, "检查登录状态异常: ${t.message}")
        }
        return false
    }
}

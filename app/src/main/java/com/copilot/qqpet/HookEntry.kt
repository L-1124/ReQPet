package com.copilot.qqpet

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.hook.HookApi
import com.copilot.qqpet.hook.HookLog
import com.copilot.qqpet.hook.HostClassLoaderBridge
import com.copilot.qqpet.hook.QQSettingInjector
import com.copilot.qqpet.hook.TinkerBlocker
import com.copilot.qqpet.protocol.PacketSniffer
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlinx.coroutines.*

class HookEntry : XposedModule() {

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

        @Volatile
        var processName: String = ""
            private set

        fun reconnectBridgeIfAvailable(context: Context): Boolean {
            val entry = instance ?: return false
            val loader = latestClassLoader ?: context.classLoader ?: return false
            return entry.initEngineAndReceiver(context, loader, "自愈重连")
        }

        /** 沿继承链查找方法（宿主的 onCreate 常声明在父类上） */
        fun findMethodInHierarchy(start: Class<*>, name: String, vararg params: Class<*>): java.lang.reflect.Method? {
            var current: Class<*>? = start
            while (current != null) {
                try {
                    return current.getDeclaredMethod(name, *params)
                } catch (_: Throwable) {
                    current = current.superclass
                }
            }
            return null
        }
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        instance = this
        processName = param.processName
        HookApi.attach(
            hooker = { executable -> hook(executable) },
            logger = { priority, tag, message -> log(priority, tag, message) }
        )
        HookLog.trace(TAG, "模块已载入进程 ${param.processName} (api=$apiVersion, $frameworkName $frameworkVersion)")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // 模块自身不再被注入，这里的判断只是兜底；真正的目标是 QQ 主进程
        if (param.packageName == MODULE_PACKAGE) return
        if (param.packageName != TARGET_PACKAGE) return
        if (processName.isNotEmpty() && processName != TARGET_PACKAGE) {
            HookLog.log(TAG, "跳过 QQ 非主进程: $processName")
            return
        }

        instance = this
        val classLoader = param.classLoader
        latestClassLoader = classLoader
        // 必须在任何"模块类继承宿主类"的解析发生之前完成，否则 NoClassDefFoundError 会被缓存
        val hostResolvable = HostClassLoaderBridge.install(javaClass.classLoader, classLoader)
        HookLog.trace(TAG, "已注入 QQ 主进程 pid=${android.os.Process.myPid()} 宿主类解析=${if (hostResolvable) "OK" else "失败"}")
        HookLog.log(TAG, "成功注入 QQ 主进程: $processName, PID=${android.os.Process.myPid()} (libxposed api=$apiVersion)")
        TinkerBlocker.install(classLoader)

        // 挂钩 1: BaseApplicationImpl.onCreate (获取真实分包完成后的 ClassLoader)
        try {
            val baseAppCls = classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            findMethodInHierarchy(baseAppCls, "onCreate")?.let { method ->
                HookApi.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val app = chain.thisObject as? Context
                    if (app != null) {
                        val appLoader = app.classLoader
                        latestClassLoader = appLoader
                        HookLog.log(TAG, "BaseApplicationImpl.onCreate 触发, classLoader=$appLoader")
                        initEngineAndReceiver(app, appLoader, "BaseApplicationImpl.onCreate")
                        hookSplashActivity(appLoader)
                        QQSettingInjector.inject(appLoader)
                    }
                    result
                }
            }
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook BaseApplicationImpl 异常: ${t.message}")
        }

        // 挂钩 2: MobileQQ.onCreate
        try {
            val mobileQQCls = Class.forName("mqq.app.MobileQQ", false, classLoader)
            findMethodInHierarchy(mobileQQCls, "onCreate")?.let { method ->
                HookApi.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null) {
                        latestClassLoader = context.classLoader
                        initEngineAndReceiver(context, context.classLoader, "MobileQQ.onCreate")
                        hookSplashActivity(context.classLoader)
                        QQSettingInjector.inject(context.classLoader)
                    }
                    result
                }
            }
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook MobileQQ.onCreate 异常: ${t.message}")
        }

        // 挂钩 3: 针对通用 Activity.onCreate 提供超轻量单次设置项保底注入
        try {
            val onCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            HookApi.hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && activity.packageName == TARGET_PACKAGE) {
                    latestClassLoader = activity.classLoader
                    if (!QQSettingInjector.isHooked) {
                        QQSettingInjector.inject(activity.classLoader)
                    }
                    if (globalBridge?.isReady != true) {
                        val appContext = activity.applicationContext ?: activity
                        initEngineAndReceiver(appContext, activity.classLoader, "Activity.onCreate[${activity.javaClass.simpleName}]")
                    }
                }
                result
            }
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook Activity.onCreate 设置保底异常: ${t.message}")
        }

        // 挂钩 4: 针对 QQ 主界面 SplashActivity 触发保活、设置注入与会话校准
        hookSplashActivity(classLoader)
    }

    private fun hookSplashActivity(classLoader: ClassLoader) {
        if (isSplashHooked) return
        try {
            val splashCls = classLoader.loadClass("com.tencent.mobileqq.activity.SplashActivity")

            findMethodInHierarchy(splashCls, "onResume")?.let { method ->
                HookApi.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == TARGET_PACKAGE) {
                        val appContext = activity.applicationContext ?: activity
                        latestClassLoader = activity.classLoader
                        QQSettingInjector.inject(activity.classLoader)
                        if (globalBridge?.isReady != true) {
                            initEngineAndReceiver(appContext, activity.classLoader, "SplashActivity.onResume")
                        }
                        globalEngine?.verifyAndSyncAccountSession(appContext)
                        globalEngine?.startBackgroundLoop(appContext)
                    }
                    result
                }
            }

            findMethodInHierarchy(splashCls, "onCreate", Bundle::class.java)?.let { method ->
                HookApi.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == TARGET_PACKAGE) {
                        val appContext = activity.applicationContext ?: activity
                        latestClassLoader = activity.classLoader
                        QQSettingInjector.inject(activity.classLoader)
                        if (globalBridge?.isReady != true) {
                            initEngineAndReceiver(appContext, activity.classLoader, "SplashActivity.onCreate")
                        }
                    }
                    result
                }
            }

            isSplashHooked = true
            HookLog.log(TAG, "已成功挂钩 SplashActivity 主界面保活与设置项注入 (libxposed)")
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
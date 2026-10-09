package com.copilot.qqpet

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.hook.CrashInterceptor
import com.copilot.qqpet.hook.HookApi
import com.copilot.qqpet.hook.HookLog
import com.copilot.qqpet.hook.QQSettingInjector
import com.copilot.qqpet.hook.TinkerBlocker
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.SettingsDialogController
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

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

        @Volatile
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

        fun getActualProcessName(): String {
            if (processName.isNotEmpty()) return processName
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val name = android.app.Application.getProcessName()
                if (!name.isNullOrEmpty()) return name
            }
            return try {
                java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ""
            }
        }

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
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
            deoptimizer = { executable -> deoptimize(executable) },
            invokerFactory = { executable ->
                when (executable) {
                    is java.lang.reflect.Method -> getInvoker(executable)
                    is java.lang.reflect.Constructor<*> -> getInvoker(executable)
                    else -> null
                }
            },
            logger = { priority, tag, message -> log(priority, tag, message) }
        )
        val props = frameworkProperties
        val hasRemote = (props and XposedInterface.PROP_CAP_REMOTE) != 0L
        val hasSystem = (props and XposedInterface.PROP_CAP_SYSTEM) != 0L
        val hasRtProt = (props and XposedInterface.PROP_RT_API_PROTECTION) != 0L
        HookLog.trace(
            TAG,
            "模块已载入进程 ${param.processName} (api=$apiVersion, $frameworkName $frameworkVersion, remote=$hasRemote, sys=$hasSystem, prot=$hasRtProt)"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName == MODULE_PACKAGE) return
        if (param.packageName != TARGET_PACKAGE) return
        val actualProcess = getActualProcessName()
        if (actualProcess.isNotEmpty() && actualProcess != TARGET_PACKAGE) {
            HookLog.trace(TAG, "跳过 QQ 非主进程: $actualProcess")
            return
        }

        instance = this
        val classLoader = param.classLoader
        latestClassLoader = classLoader

        // 尽早注入全局未捕获异常监控，防止宿主 Crash SDK 静默强杀
        CrashInterceptor.install()

        HookLog.trace(TAG, "已注入 QQ 主进程 pid=${android.os.Process.myPid()}")
        HookLog.log(
            TAG,
            "成功注入 QQ 主进程: $actualProcess, PID=${android.os.Process.myPid()} (libxposed api=$apiVersion)"
        )
        TinkerBlocker.install(classLoader)

        // 挂钩 1: BaseApplicationImpl.onCreate (获取真实分包完成后的 ClassLoader)
        try {
            val baseAppCls = classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            findMethodInHierarchy(baseAppCls, "onCreate")?.let { method ->
                HookApi.hook(method, id = "qq_base_app_create").intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val app = chain.thisObject as? Context
                        if (app != null) {
                            synchronized(this@HookEntry) {
                                val appLoader = app.classLoader
                                latestClassLoader = appLoader
                                CrashInterceptor.install(app)
                                HookLog.log(TAG, "BaseApplicationImpl.onCreate 触发, classLoader=$appLoader")
                                initEngineAndReceiver(app, appLoader, "BaseApplicationImpl.onCreate")
                                hookSplashActivity(appLoader)
                                QQSettingInjector.inject(appLoader)
                            }
                        }
                    }.onFailure { t ->
                        HookLog.e(TAG, "BaseApplicationImpl.onCreate 注入执行异常", t)
                    }
                    result
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HookLog.e(TAG, "Hook BaseApplicationImpl 异常", t)
        }

        // 挂钩 2: MobileQQ.onCreate
        try {
            val mobileQQCls = Class.forName("mqq.app.MobileQQ", false, classLoader)
            findMethodInHierarchy(mobileQQCls, "onCreate")?.let { method ->
                HookApi.hook(method, id = "qq_mobile_qq_create").intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val context = chain.thisObject as? Context
                        if (context != null) {
                            synchronized(this@HookEntry) {
                                val appLoader = context.classLoader
                                latestClassLoader = appLoader
                                CrashInterceptor.install(context)
                                initEngineAndReceiver(context, appLoader, "MobileQQ.onCreate")
                                hookSplashActivity(appLoader)
                                QQSettingInjector.inject(appLoader)
                            }
                        }
                    }.onFailure { t ->
                        HookLog.e(TAG, "MobileQQ.onCreate 注入执行异常", t)
                    }
                    result
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HookLog.e(TAG, "Hook MobileQQ.onCreate 异常", t)
        }

        // 挂钩 4: 针对 QQ 主界面 SplashActivity 触发保活、设置注入与会话校准
        hookSplashActivity(classLoader)
    }

    private fun hookSplashActivity(classLoader: ClassLoader) {
        if (isSplashHooked) return
        synchronized(this) {
            if (isSplashHooked) return
            try {
                val splashCls = classLoader.loadClass("com.tencent.mobileqq.activity.SplashActivity")

                findMethodInHierarchy(splashCls, "onResume")?.let { method ->
                    HookApi.hook(method, id = "qq_splash_resume").intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val activity = chain.thisObject as? Activity
                            if (activity != null && activity.packageName == TARGET_PACKAGE) {
                                val appContext = activity.applicationContext ?: activity
                                latestClassLoader = activity.classLoader
                                QQSettingInjector.inject(activity.classLoader)
                                if (globalBridge?.isReady != true) {
                                    initEngineAndReceiver(
                                        appContext,
                                        activity.classLoader,
                                        "SplashActivity.onResume"
                                    )
                                }
                                globalEngine?.resumeBackgroundLoop(appContext)
                            }
                        }.onFailure { t ->
                            HookLog.e(TAG, "SplashActivity.onResume 执行异常", t)
                        }
                        result
                    }
                }

                findMethodInHierarchy(splashCls, "onCreate", Bundle::class.java)?.let { method ->
                    HookApi.hook(method, id = "qq_splash_create").intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val activity = chain.thisObject as? Activity
                            if (activity != null && activity.packageName == TARGET_PACKAGE) {
                                val appContext = activity.applicationContext ?: activity
                                latestClassLoader = activity.classLoader
                                QQSettingInjector.inject(activity.classLoader)
                                if (globalBridge?.isReady != true) {
                                    initEngineAndReceiver(
                                        appContext,
                                        activity.classLoader,
                                        "SplashActivity.onCreate"
                                    )
                                }
                            }
                        }.onFailure { t ->
                            HookLog.e(TAG, "SplashActivity.onCreate 执行异常", t)
                        }
                        result
                    }
                }

                isSplashHooked = true
                HookLog.log(TAG, "已成功挂钩 SplashActivity 主界面保活与设置项注入 (libxposed)")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                HookLog.e(TAG, "Hook SplashActivity 异常", t)
            }
        }
    }

    @Synchronized
    fun initEngineAndReceiver(context: Context, classLoader: ClassLoader, from: String): Boolean {
        val appContext = context.applicationContext ?: context
        com.copilot.qqpet.engine.RuntimeDiagnostics.bind(appContext)
        SettingsDialogController.install(appContext)

        try {
            val prefs = appContext.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            HookLog.isDebugEnabled = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }

        // 协议熔断器事件统一落入引擎指标
        runCatching {
            com.copilot.qqpet.protocol.channel.ProtocolBreakers.metrics =
                com.copilot.qqpet.engine.metrics.EngineMetricsImpl.getInstance(appContext)
        }

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
                    HookLog.log(
                        TAG,
                        "冒险探索发包内核就绪 (来源: $from, 类: ${QQPetDirectBridge.resolvedDelegateClass?.name})"
                    )
                } else if (globalEngine == null) {
                    globalBridge = bridge
                    globalEngine = PetAdventureEngine(bridge).apply {
                        reloadConfig(appContext)
                    }
                    HookLog.log(TAG, "发包内核暂未就绪，等待后续分包触发 (来源: $from)")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                HookLog.e(TAG, "初始化发包内核失败", t)
            }
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
                delay(delayMs.milliseconds)
                if (PetAdventureEngine.isLoopRunning) break
                val started = tryStartLoopIfLoggedIn(appContext, classLoader, "异步复检:$from")
                if (started) break
            }
        }
    }

    private fun tryStartLoopIfLoggedIn(
        appContext: Context,
        classLoader: ClassLoader,
        from: String
    ): Boolean {
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
            if (t is kotlinx.coroutines.CancellationException) throw t
            HookLog.e(TAG, "检查登录状态异常", t)
        }
        return false
    }
}
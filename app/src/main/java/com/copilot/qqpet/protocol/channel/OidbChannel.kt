package com.copilot.qqpet.protocol.channel

import com.copilot.qqpet.engine.EngineLog
import android.content.Context
import android.util.Base64
import com.copilot.qqpet.RuntimeSwitches
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.AccountSessionGuard
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets

/**
 * 底层 OIDB / SSO 反射通道与 ClassLoader 发现管道
 */
class OidbChannel(
    val classLoader: ClassLoader,
    val context: Context? = null
) {
    companion object {
        private const val TAG = "OidbChannel"
        private const val INTERFACE_CLASS = "com.tencent.ergo.hostdelegate.pb.PetPbDelegate"
        private const val OBSERVER_CLASS = "com.tencent.ergo.hostdelegate.pb.PetPbDelegate\$a"
        private const val DELEGATE_PKG = "com.tencent.mobileqq.qqpet.delegate."

        const val MASTER_OFF_CODE = -101

        @Volatile
        var resolvedDelegateClass: Class<*>? = null
            internal set

        @Volatile
        var resolvedSendMethodName: String = "c"
            internal set

        @Volatile
        var cachedDelegateClassName: String? = null
            internal set

        fun getCandidateClassLoaders(primaryLoader: ClassLoader, context: Context?): List<ClassLoader> {
            val loaders = mutableListOf<ClassLoader>()
            loaders.add(primaryLoader)
            context?.classLoader?.let { if (!loaders.contains(it)) loaders.add(it) }
            try {
                Thread.currentThread().contextClassLoader?.let { if (!loaders.contains(it)) loaders.add(it) }
            } catch (_: Throwable) {
            }
            try {
                HookEntry.latestClassLoader?.let { if (!loaders.contains(it)) loaders.add(it) }
            } catch (_: Throwable) {
            }
            appendMobileQQLoaders(primaryLoader, context, loaders)
            return loaders
        }

        private fun appendMobileQQLoaders(
            primaryLoader: ClassLoader,
            context: Context?,
            loaders: MutableList<ClassLoader>
        ) {
            try {
                val candidateLoaders = listOfNotNull(primaryLoader, context?.classLoader, HookEntry.latestClassLoader)
                for (l in candidateLoaders) {
                    try {
                        val mobileQQCls = Class.forName("mqq.app.MobileQQ", false, l)
                        val sMobileQQField = mobileQQCls.getDeclaredField("sMobileQQ").apply { isAccessible = true }
                        val sMobileQQ = sMobileQQField.get(null) ?: continue
                        val ml = sMobileQQ.javaClass.classLoader
                        if (ml != null && !loaders.contains(ml)) loaders.add(ml)
                        if (sMobileQQ is Context) {
                            val cl = sMobileQQ.classLoader
                            if (cl != null && !loaders.contains(cl)) loaders.add(cl)
                        }
                        break
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        fun tryLoadClass(name: String, loader: ClassLoader): Class<*>? {
            return try {
                Class.forName(name, false, loader)
            } catch (_: Throwable) {
                null
            }
        }

        fun buildCandidateClassNames(): List<String> {
            val names = linkedSetOf<String>()
            val commonSingle = listOf('m', 'l', 'n', 'k', 'o', 'p', 'j', 'i', 'h', 'g', 'f', 'e', 'd', 'c', 'b', 'a')
            for (ch in commonSingle) names.add("$DELEGATE_PKG$ch")
            for (ch in 'q'..'z') names.add("$DELEGATE_PKG$ch")
            for (ch in 'A'..'Z') names.add("$DELEGATE_PKG$ch")
            for (c1 in 'a'..'z') {
                for (c2 in 'a'..'z') {
                    names.add("$DELEGATE_PKG$c1$c2")
                }
            }
            return names.toList()
        }

        fun findDelegateClass(classLoader: ClassLoader): Pair<Class<*>?, Method?> {
            val (cls, method, _) = findDelegateClass(listOf(classLoader))
            return Pair(cls, method)
        }

        fun findDelegateClass(loaders: List<ClassLoader>): Triple<Class<*>?, Method?, Class<*>?> {
            resolvedDelegateClass?.let { cls ->
                val targetMethod = findOidbSendMethod(cls, null)
                if (targetMethod != null) {
                    val obs = targetMethod.parameterTypes[4]
                    return Triple(cls, targetMethod, obs)
                }
            }

            val cachedName = cachedDelegateClassName
            val candidateNames = if (!cachedName.isNullOrEmpty()) {
                listOf(cachedName) + buildCandidateClassNames().filter { it != cachedName }
            } else {
                buildCandidateClassNames()
            }

            for (loader in loaders) {
                var observerCls = tryFindObserverClass(loaders, loader)
                val interfaceCls = tryLoadClass(INTERFACE_CLASS, loader)
                for (className in candidateNames) {
                    val cls = tryLoadClass(className, loader) ?: continue
                    if (cls.isInterface) continue
                    val isInterfaceMatch = interfaceCls != null && interfaceCls.isAssignableFrom(cls)
                    val targetMethod = findOidbSendMethod(cls, observerCls)
                    if (targetMethod != null && (isInterfaceMatch || interfaceCls == null)) {
                        resolvedDelegateClass = cls
                        resolvedSendMethodName = targetMethod.name
                        cachedDelegateClassName = className
                        if (observerCls == null) {
                            observerCls = targetMethod.parameterTypes[4]
                        }
                        EngineLog.i(
                            "OidbChannel",
                            "动态多源自适应命中 QQ 宠物原生发包代理类: $className, 发包方法: ${targetMethod.name}"
                        )
                        return Triple(cls, targetMethod, observerCls)
                    }
                }
            }
            return Triple(null, null, null)
        }

        private fun tryFindObserverClass(loaders: List<ClassLoader>, currentLoader: ClassLoader): Class<*>? {
            val direct = tryLoadClass(OBSERVER_CLASS, currentLoader)
            if (direct != null) return direct
            for (other in loaders) {
                val cls = tryLoadClass(OBSERVER_CLASS, other)
                if (cls != null) return cls
            }
            return null
        }

        private fun findOidbSendMethod(cls: Class<*>, observerCls: Class<*>?): Method? {
            var targetMethod: Method? = null
            val allMethods = cls.methods + cls.declaredMethods
            for (m in allMethods) {
                val params = m.parameterTypes
                if (params.size == 5 &&
                    params[0] == ByteArray::class.java &&
                    params[1] == String::class.java &&
                    (params[2] == Int::class.javaPrimitiveType || params[2] == Integer::class.java) &&
                    (params[3] == Int::class.javaPrimitiveType || params[3] == Integer::class.java) &&
                    (observerCls == null || observerCls.isAssignableFrom(params[4]) || params[4].isInterface || params[4] == Any::class.java)
                ) {
                    targetMethod = m
                    m.isAccessible = true
                    if (m.name == "c") break
                }
            }
            return targetMethod
        }
    }

    private var delegateInstance: Any? = null
    private var sendOidbMethod: Method? = null
    private var observerClass: Class<*>? = null
    private val requestTracker = RequestTracker()

    @Volatile
    var isInternalSending = false

    var isReady: Boolean = false
        private set

    init {
        tryInitDelegate()
    }

    private fun tryInitDelegate() {
        try {
            if (cachedDelegateClassName == null && context != null) {
                try {
                    val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                    cachedDelegateClassName = prefs.getString("cached_delegate_class", null)
                } catch (_: Throwable) {
                }
            }
            val loaders = getCandidateClassLoaders(classLoader, context)
            val (cls, method, obsCls) = findDelegateClass(loaders)
            if (cls != null && method != null && obsCls != null) {
                observerClass = obsCls
                val inst = createDelegateInstance(cls, context)
                if (inst != null) {
                    delegateInstance = inst
                    sendOidbMethod = method
                    isReady = true
                    if (context != null) {
                        try {
                            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("cached_delegate_class", cls.name).apply()
                        } catch (_: Throwable) {
                        }
                    }
                    com.copilot.qqpet.protocol.DeviceTrace.bind(context)
                    EngineLog.d("OidbChannel", "成功反射挂载 QQ 宠物原生发包代理: ${cls.name}")
                } else {
                    EngineLog.e("OidbChannel", "实例化 QQ 宠物发包代理类失败: ${cls.name}")
                }
            } else {
                EngineLog.e("OidbChannel", "未能在任何可用 ClassLoader 中动态发现实现 PetPbDelegate 的发包代理类")
            }
        } catch (t: Throwable) {
            EngineLog.e("OidbChannel", "反射 QQ 发包代理失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun createDelegateInstance(cls: Class<*>, context: Context?): Any? {
        for (field in cls.declaredFields) {
            if (Modifier.isStatic(field.modifiers) && cls.isAssignableFrom(field.type)) {
                try {
                    field.isAccessible = true
                    val inst = field.get(null)
                    if (inst != null) return inst
                } catch (_: Throwable) {
                }
            }
        }
        try {
            val noArg = cls.getDeclaredConstructor().apply { isAccessible = true }
            return noArg.newInstance()
        } catch (_: Throwable) {
        }
        for (cons in cls.declaredConstructors) {
            try {
                cons.isAccessible = true
                val paramTypes = cons.parameterTypes
                val args = arrayOfNulls<Any>(paramTypes.size)
                for (i in paramTypes.indices) {
                    if (context != null && Context::class.java.isAssignableFrom(paramTypes[i])) {
                        args[i] = context
                    }
                }
                val inst = cons.newInstance(*args)
                if (inst != null) return inst
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun getCurrentRuntimeUin(): String {
        val loaders = getCandidateClassLoaders(classLoader, context)
        for (loader in loaders) {
            try {
                val mobileQQClass = Class.forName("mqq.app.MobileQQ", false, loader)
                val sMobileQQField = mobileQQClass.getDeclaredField("sMobileQQ").apply { isAccessible = true }
                val sMobileQQ = sMobileQQField.get(null)
                if (sMobileQQ != null) {
                    val peekMethod = sMobileQQ.javaClass.getMethod("peekAppRuntime")
                    val runtime = peekMethod.invoke(sMobileQQ)
                    if (runtime != null) {
                        val uinMethod = runtime.javaClass.getMethod("getCurrentAccountUin")
                        val uin = (uinMethod.invoke(runtime) as? String)?.trim()
                        if (AccountSessionGuard.isValidUin(uin)) {
                            return uin!!
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return ""
    }

    fun resolveUin(petId: String): String {
        val fromPetId = AccountSessionGuard.extractOwnerUinFromPetId(petId)
        if (fromPetId.isNotEmpty()) return fromPetId
        val runtimeUin = getCurrentRuntimeUin()
        if (runtimeUin.isNotEmpty()) return runtimeUin
        try {
            val decoded = String(Base64.decode(petId, Base64.DEFAULT), StandardCharsets.UTF_8)
            val uinPart = decoded.substringBefore("-")
            if (uinPart.isNotEmpty() && uinPart.all { it.isDigit() }) {
                return uinPart
            }
        } catch (_: Throwable) {
        }
        return ""
    }

    fun sendOidb(
        commandName: String,
        command: Int,
        subCommand: Int,
        request: ByteArray,
        callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit
    ): Int {
        val requestId = requestTracker.register(commandName)
        requestTracker.sweepExpired().forEach {
            EngineLog.d("OidbChannel", "请求 #${it.id} ${it.command} 超时未回包，配对已释放")
        }
        if (!RuntimeSwitches.masterEnabled) {
            EngineLog.w("OidbChannel", "总开关未开启：拦截发包 $commandName")
            deliverOnce(requestId, commandName, callback, MASTER_OFF_CODE, null, "总开关未开启")
            return requestId
        }

        // 熔断器打开时快速失败，不发反射包，避免持续冲击故障域
        if (!ProtocolBreakers.allowSend(commandName)) {
            EngineLog.w("OidbChannel", "熔断器打开：快速失败 $commandName")
            deliverOnce(requestId, commandName, callback, ProtocolBreakers.FAST_FAIL_CODE, null, "协议域熔断中")
            return requestId
        }

        val obsCls = observerClass
        val method = sendOidbMethod
        val instance = delegateInstance
        if (!isReady || instance == null || method == null || obsCls == null) {
            deliverOnce(requestId, commandName, callback, -1, null, "发包代理未就绪")
            return requestId
        }
        try {
            isInternalSending = true
            val proxyLoader = obsCls.classLoader ?: classLoader
            val observer = Proxy.newProxyInstance(
                proxyLoader,
                arrayOf(obsCls)
            ) { proxy, invokedMethod, args ->
                if (invokedMethod.name == "toString") return@newProxyInstance "PetPbDelegateObserverProxy"
                if (invokedMethod.name == "hashCode") return@newProxyInstance System.identityHashCode(proxy)
                if (invokedMethod.name == "equals") return@newProxyInstance args?.getOrNull(0) === proxy
                if (args != null && args.isNotEmpty()) {
                    if (!requestTracker.tryDeliver(requestId)) {
                        EngineLog.w("OidbChannel", "丢弃重复或迟到的回包 #$requestId $commandName")
                        return@newProxyInstance null
                    }
                    val code = (args[0] as? Number)?.toInt() ?: -1
                    val data = args.getOrNull(1) as? ByteArray
                    val bundle = args.getOrNull(2) as? android.os.Bundle
                    val errorMsg = bundle?.getString("data_error_msg") ?: bundle?.getString("error_msg")
                    invokeCallback(commandName, requestId, callback, code, data, errorMsg)
                }
                null
            }
            method.invoke(instance, request, commandName, command, subCommand, observer)
        } catch (t: Throwable) {
            EngineLog.e("OidbChannel", "sendOidb 执行反射调用异常: ${t.javaClass.simpleName}: ${t.message}")
            deliverOnce(requestId, commandName, callback, -2, null, t.message)
        } finally {
            isInternalSending = false
        }
        return requestId
    }

    /** 未进入回调阶段时的单次投递（代理未就绪 / 反射异常） */
    private fun deliverOnce(
        requestId: Int,
        commandName: String,
        callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit,
        code: Int,
        data: ByteArray?,
        errorMsg: String?
    ) {
        if (!requestTracker.tryDeliver(requestId)) {
            EngineLog.w("OidbChannel", "丢弃重复或迟到的结果 #$requestId $commandName")
            return
        }
        invokeCallback(commandName, requestId, callback, code, data, errorMsg)
    }

    /** 回调执行边界：异常不得冒泡到宿主线程 */
    private fun invokeCallback(
        commandName: String,
        requestId: Int,
        callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit,
        code: Int,
        data: ByteArray?,
        errorMsg: String?
    ) {
        // 真实回包才参与熔断统计；本地快速失败码不计入
        if (code != MASTER_OFF_CODE && code != ProtocolBreakers.FAST_FAIL_CODE) {
            ProtocolBreakers.recordOutcome(commandName, code)
            ProtocolBreakers.recordRequest(commandName, code)
        }
        try {
            callback(code, data, errorMsg)
        } catch (t: Throwable) {
            EngineLog.e("OidbChannel", "回包处理异常 #$requestId $commandName: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

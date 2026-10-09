package io.github.reqpet.protocol.channel

import io.github.reqpet.engine.EngineLog
import io.github.reqpet.engine.PetAdventureEngine
import android.content.Context
import java.util.Base64
import io.github.reqpet.RuntimeSwitches
import io.github.reqpet.HookEntry
import io.github.reqpet.engine.AccountSessionGuard
import io.github.reqpet.engine.RuntimeDiagnostics
import io.github.reqpet.hook.HookApi
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

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
        private const val OBSERVER_CLASS = $$"com.tencent.ergo.hostdelegate.pb.PetPbDelegate$a"
        private const val DELEGATE_PKG = "com.tencent.mobileqq.qqpet.delegate."
        private val channelSequence = AtomicLong()

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
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            try {
                HookEntry.latestClassLoader?.let { if (!loaders.contains(it)) loaders.add(it) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }

        fun tryLoadClass(name: String, loader: ClassLoader): Class<*>? {
            return try {
                Class.forName(name, false, loader)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        }

        fun buildCandidateClassNames(): List<String> {
            val names = linkedSetOf<String>()
            for (ch in 'a'..'z') names.add("$DELEGATE_PKG$ch")
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

        fun findDelegateClass(
            loaders: List<ClassLoader>,
            context: Context? = null
        ): Triple<Class<*>?, Method?, Class<*>?> {
            resolvedDelegateClass?.let { cls ->
                val targetMethod = findOidbSendMethod(cls, null)
                if (targetMethod != null) {
                    val obs = targetMethod.parameterTypes[4]
                    return Triple(cls, targetMethod, obs)
                }
            }

            // 1. 优先读取 SharedPreferences 缓存并校验可用性 (<1ms)
            val cachedName = cachedDelegateClassName
            if (!cachedName.isNullOrEmpty()) {
                for (loader in loaders) {
                    val cls = tryLoadClass(cachedName, loader) ?: continue
                    if (cls.isInterface) continue
                    val observerCls = tryFindObserverClass(loaders, loader)
                    val targetMethod = findOidbSendMethod(cls, observerCls)
                    if (targetMethod != null) {
                        resolvedDelegateClass = cls
                        resolvedSendMethodName = targetMethod.name
                        EngineLog.i("OidbChannel", "命中本地缓存发包代理类: $cachedName")
                        return Triple(cls, targetMethod, observerCls ?: targetMethod.parameterTypes[4])
                    }
                }
            }

            // 2. 缓存未命中或失效时，使用纯 Kotlin DexParser 从 APK 提取真实实现类
            val apkPath = context?.applicationInfo?.sourceDir
            if (!apkPath.isNullOrEmpty()) {
                val dexClassName = DexParser.findImplementingClass(apkPath)
                if (!dexClassName.isNullOrEmpty()) {
                    for (loader in loaders) {
                        val cls = tryLoadClass(dexClassName, loader) ?: continue
                        if (cls.isInterface) continue
                        val observerCls = tryFindObserverClass(loaders, loader)
                        val targetMethod = findOidbSendMethod(cls, observerCls)
                        if (targetMethod != null) {
                            resolvedDelegateClass = cls
                            resolvedSendMethodName = targetMethod.name
                            cachedDelegateClassName = dexClassName
                            EngineLog.i("OidbChannel", "DexParser 精确提取发包代理类: $dexClassName")
                            return Triple(cls, targetMethod, observerCls ?: targetMethod.parameterTypes[4])
                        }
                    }
                }
            }

            // 3. 兜底回退：若 DexParser 未能执行或未命中，沿用平权穷举探测
            val candidateNames = buildCandidateClassNames()
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
                            "穷举兜底命中发包代理类: $className, 发包方法: ${targetMethod.name}"
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
                    (params[2] == Int::class.javaPrimitiveType || params[2] == Int::class.javaObjectType) &&
                    (params[3] == Int::class.javaPrimitiveType || params[3] == Int::class.javaObjectType) &&
                    (observerCls == null || observerCls.isAssignableFrom(params[4]) || params[4].isInterface || params[4] == Any::class.java)
                ) {
                    targetMethod = m
                    m.isAccessible = true
                    break
                }
            }
            return targetMethod
        }
    }

    private var delegateInstance: Any? = null
    private var sendOidbMethod: Method? = null

    @Volatile
    private var sendOidbInvoker: XposedInterface.Invoker<*, Method>? = null
    private var observerClass: Class<*>? = null
    private val requestTracker = RequestTracker()
    private val channelId = channelSequence.incrementAndGet()

    private class RequestAttempt(
        val id: Int,
        val command: String,
        val generation: Long,
        val account: String,
        val transaction: Long,
        val cycle: Long,
        val startedAtMs: Long
    ) {
        @Volatile
        var sentAtMs: Long = -1L
    }

    private fun registerAttempt(command: String, generation: Long, accountUin: String): RequestAttempt {
        val startedAtMs = RuntimeDiagnostics.nowMs()
        val attempt = RequestAttempt(
            requestTracker.register(command, generation, accountUin),
            command,
            generation,
            RuntimeDiagnostics.id(accountUin),
            PetAdventureEngine.currentTransactionId,
            PetAdventureEngine.currentCycleId,
            startedAtMs
        )
        requestEvent("request_attempt", attempt)
        return attempt
    }

    private fun requestEvent(
        name: String,
        attempt: RequestAttempt,
        reason: String? = null,
        code: Int? = null,
        source: String? = null,
        currentGeneration: Long? = null,
        currentAccount: String? = null,
        atMs: Long = RuntimeDiagnostics.nowMs()
    ) {
        RuntimeDiagnostics.event(
            name,
            "channel" to channelId,
            "request" to attempt.id,
            "command" to attempt.command,
            "generation" to attempt.generation,
            "account" to attempt.account,
            "transaction" to attempt.transaction,
            "cycle" to attempt.cycle,
            "reason" to reason,
            "code" to code,
            "source" to source,
            "current_generation" to currentGeneration,
            "current_account" to currentAccount,
            "elapsed_ms" to (atMs - attempt.startedAtMs).coerceAtLeast(0L),
            "transport_ms" to attempt.sentAtMs.takeIf { it >= 0L }?.let { (atMs - it).coerceAtLeast(0L) }
        )
    }

    private fun removedRequestEvent(name: String, pending: RequestTracker.Pending, reason: String) {
        RuntimeDiagnostics.event(
            name,
            "channel" to channelId,
            "request" to pending.id,
            "command" to pending.command,
            "generation" to pending.sessionGeneration,
            "account" to RuntimeDiagnostics.id(pending.accountUin),
            "reason" to reason,
            "age_wall_ms" to (System.currentTimeMillis() - pending.createdAt).coerceAtLeast(0L)
        )
    }


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
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            val loaders = getCandidateClassLoaders(classLoader, context)
            val (cls, method, obsCls) = findDelegateClass(loaders, context)
            if (cls != null && method != null && obsCls != null) {
                observerClass = obsCls
                val inst = createDelegateInstance(cls, context)
                if (inst != null) {
                    delegateInstance = inst
                    sendOidbMethod = method
                    sendOidbInvoker = HookApi.getMethodInvoker(method, XposedInterface.Invoker.Type.ORIGIN)
                    isReady = true
                    if (context != null) {
                        try {
                            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("cached_delegate_class", cls.name).apply()
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                    }
                    io.github.reqpet.protocol.DeviceTrace.bind(context)
                    EngineLog.d("OidbChannel", "成功反射挂载 QQ 宠物原生发包代理: ${cls.name}")
                } else {
                    EngineLog.e("OidbChannel", "实例化 QQ 宠物发包代理类失败: ${cls.name}")
                }
            } else {
                EngineLog.e("OidbChannel", "未能在任何可用 ClassLoader 中动态发现实现 PetPbDelegate 的发包代理类")
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
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
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
        try {
            val noArg = cls.getDeclaredConstructor().apply { isAccessible = true }
            return noArg.newInstance()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
            val decoded = String(Base64.getDecoder().decode(petId.trim()), StandardCharsets.UTF_8)
            val uinPart = decoded.substringBefore("-")
            if (uinPart.isNotEmpty() && uinPart.all { it.isDigit() }) {
                return uinPart
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        val liveRuntimeUin = getCurrentRuntimeUin()
        if (!AccountSessionGuard.isValidUin(liveRuntimeUin)) {
            EngineLog.w("OidbChannel", "实时登录态无效或尚未就绪 (UIN='$liveRuntimeUin')：拒绝发包 $commandName")
            val attempt = registerAttempt(commandName, PetAdventureEngine.sessionGeneration, liveRuntimeUin)
            completeLocalOnce(attempt, callback, -1, null, "实时登录态无效或尚未就绪", "account_unavailable")
            return attempt.id
        }
        if (PetAdventureEngine.currentActiveUin.isNotEmpty() && liveRuntimeUin != PetAdventureEngine.currentActiveUin) {
            EngineLog.w(
                "OidbChannel",
                "检测到账号切换 (live=$liveRuntimeUin != active=${PetAdventureEngine.currentActiveUin})：拦截旧会话发包 $commandName"
            )
            val attempt = registerAttempt(commandName, PetAdventureEngine.sessionGeneration, liveRuntimeUin)
            completeLocalOnce(attempt, callback, -1, null, "检测到账号切换，拦截旧会话发包", "account_changed")
            return attempt.id
        }
        val currentGen = PetAdventureEngine.sessionGeneration
        val currentUin = liveRuntimeUin
        val attempt = registerAttempt(commandName, currentGen, currentUin)
        val requestId = attempt.id
        requestTracker.sweepExpired().forEach {
            EngineLog.d("OidbChannel", "请求 #${it.id} ${it.command} 超时未回包，配对已释放")
            removedRequestEvent("request_expired", it, "timeout")
        }
        if (!RuntimeSwitches.masterEnabled) {
            EngineLog.w("OidbChannel", "总开关未开启：拦截发包 $commandName")
            completeLocalOnce(attempt, callback, MASTER_OFF_CODE, null, "总开关未开启", "master_off")
            return requestId
        }

        // 熔断器打开时快速失败，不发反射包，避免持续冲击故障域
        var sendRejection = ProtocolBreakers.SendRejectionReason.CIRCUIT_OPEN
        if (!ProtocolBreakers.allowSend(commandName) { sendRejection = it }) {
            EngineLog.w("OidbChannel", "协议发送被拒绝：${sendRejection.diagnosticName} $commandName")
            completeLocalOnce(
                attempt,
                callback,
                ProtocolBreakers.FAST_FAIL_CODE,
                null,
                "协议域熔断中",
                sendRejection.diagnosticName
            )
            return requestId
        }

        val obsCls = observerClass
        val method = sendOidbMethod
        val instance = delegateInstance
        if (!isReady || instance == null || method == null || obsCls == null) {
            completeLocalOnce(
                attempt,
                callback,
                -1,
                null,
                "发包代理未就绪",
                "proxy_unready",
                recordTransportOutcome = true
            )
            return requestId
        }
        try {
            val proxyLoader = obsCls.classLoader ?: classLoader
            val observer = Proxy.newProxyInstance(
                proxyLoader,
                arrayOf(obsCls)
            ) { proxy, invokedMethod, args ->
                if (invokedMethod.name == "toString") return@newProxyInstance "PetPbDelegateObserverProxy"
                if (invokedMethod.name == "hashCode") return@newProxyInstance System.identityHashCode(proxy)
                if (invokedMethod.name == "equals") return@newProxyInstance args?.getOrNull(0) === proxy
                if (args != null && args.isNotEmpty()) {
                    val responseAtMs = RuntimeDiagnostics.nowMs()
                    val liveGen = PetAdventureEngine.sessionGeneration
                    val liveUin = getCurrentRuntimeUin()
                    if (!requestTracker.tryDeliver(
                            requestId,
                            currentGeneration = liveGen,
                            currentUin = liveUin,
                            onRejected = {
                                val dropCode = runCatching { (args[0] as? Number)?.toInt() ?: -1 }.getOrNull()
                                requestEvent(
                                    "request_drop", attempt, it.diagnosticName, dropCode, "response",
                                    liveGen, RuntimeDiagnostics.id(liveUin), responseAtMs
                                )
                            }
                        )
                    ) {
                        EngineLog.w(
                            "OidbChannel",
                            "丢弃重复或迟到的回包 #$requestId $commandName (gen=$liveGen, uin=$liveUin)"
                        )
                        return@newProxyInstance null
                    }
                    val code = (args[0] as? Number)?.toInt() ?: -1
                    val data = args.getOrNull(1) as? ByteArray
                    val bundle = args.getOrNull(2) as? android.os.Bundle
                    val errorMsg = bundle?.getString("data_error_msg") ?: bundle?.getString("error_msg")
                    invokeCallback(attempt, callback, code, data, errorMsg, "response", responseAtMs)
                }
                null
            }
            val invoker = sendOidbInvoker
            requestEvent("request_send", attempt)
            // 捕获实际代理调用边界，包含同步回包，不把代理发现或本地拒绝计为网络耗时。
            attempt.sentAtMs = RuntimeDiagnostics.nowMs()
            if (invoker != null) {
                invoker.invoke(instance, request, commandName, command, subCommand, observer)
            } else {
                method.invoke(instance, request, commandName, command, subCommand, observer)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val failureAtMs = RuntimeDiagnostics.nowMs()
            EngineLog.e("OidbChannel", "sendOidb 执行反射调用异常: ${t.javaClass.simpleName}: ${t.message}")
            RuntimeDiagnostics.error(
                "request_send_error", t, "channel" to channelId, "request" to attempt.id,
                "command" to attempt.command
            )
            completeLocalOnce(
                attempt, callback, -2, null, t.message, "send_exception",
                recordTransportOutcome = true, completedAtMs = failureAtMs
            )
        }
        return requestId
    }

    /** 本地完成不伪装成回包；保留原有熔断处理，但未触达发包边界的不计网络样本。 */
    private fun completeLocalOnce(
        attempt: RequestAttempt,
        callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit,
        code: Int,
        data: ByteArray?,
        errorMsg: String?,
        reason: String,
        recordTransportOutcome: Boolean = false,
        completedAtMs: Long = RuntimeDiagnostics.nowMs()
    ) {
        requestEvent("request_local_rejection", attempt, reason, code, "local", atMs = completedAtMs)
        val liveGen = PetAdventureEngine.sessionGeneration
        if (!requestTracker.tryCompleteLocal(
                attempt.id,
                currentGeneration = liveGen,
                onRejected = {
                    requestEvent(
                        "request_drop",
                        attempt,
                        it.diagnosticName,
                        code,
                        "local",
                        liveGen,
                        atMs = completedAtMs
                    )
                }
            )
        ) {
            EngineLog.w("OidbChannel", "丢弃重复或迟到的本地结果 #${attempt.id} ${attempt.command} (gen=$liveGen)")
            return
        }
        invokeCallback(attempt, callback, code, data, errorMsg, reason, completedAtMs, recordTransportOutcome)
    }

    /** 回调执行边界：异常不得冒泡到宿主线程 */
    private fun invokeCallback(
        attempt: RequestAttempt,
        callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit,
        code: Int,
        data: ByteArray?,
        errorMsg: String?,
        reason: String,
        completedAtMs: Long,
        recordTransportOutcome: Boolean = true
    ) {
        requestEvent(
            "request_finish",
            attempt,
            reason,
            code,
            if (reason == "response") "response" else "local",
            atMs = completedAtMs
        )
        // 本地账号、开关、限流/熔断拒绝不属于网络请求或传输故障。
        if (recordTransportOutcome && code != MASTER_OFF_CODE && code != ProtocolBreakers.FAST_FAIL_CODE) {
            ProtocolBreakers.recordOutcome(attempt.command, code)
            if (attempt.sentAtMs >= 0L) {
                ProtocolBreakers.recordRequest(
                    attempt.command,
                    code,
                    (completedAtMs - attempt.sentAtMs).coerceAtLeast(0L)
                )
            }
        }
        try {
            callback(code, data, errorMsg)
        } catch (t: Throwable) {
            RuntimeDiagnostics.error(
                "request_callback_error", t, "channel" to channelId, "request" to attempt.id,
                "command" to attempt.command
            )
            EngineLog.e(
                "OidbChannel",
                "回包处理异常 #${attempt.id} ${attempt.command}: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    fun invalidateSession(generation: Long, reason: String = "session_changed"): List<RequestTracker.Pending> {
        val removed = requestTracker.invalidateSession(generation)
        RuntimeDiagnostics.event(
            "request_session_invalidation",
            "channel" to channelId,
            "generation" to generation,
            "reason" to reason,
            "removed" to removed.size
        )
        removed.forEach { removedRequestEvent("request_invalidated", it, reason) }
        return removed
    }

    fun clearPendingRequests(): List<RequestTracker.Pending> {
        val removed = requestTracker.clear()
        removed.forEach { removedRequestEvent("request_invalidated", it, "clear_pending") }
        return removed
    }
}

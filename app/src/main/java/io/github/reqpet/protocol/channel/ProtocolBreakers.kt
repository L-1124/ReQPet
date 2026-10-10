package io.github.reqpet.protocol.channel

import io.github.reqpet.engine.EngineLog
import io.github.reqpet.engine.metrics.EngineMetrics
import io.github.reqpet.engine.resilience.CircuitBreaker
import io.github.reqpet.engine.resilience.CircuitBreakerConfig
import io.github.reqpet.engine.resilience.CircuitBreakerManager
import io.github.reqpet.engine.resilience.CircuitState

/**
 * 协议层熔断器门面：按业务域维护独立熔断器，供 OidbChannel 出口统一挂接
 *
 * 分域依据命令前缀（0x975a=Career 等）而非每个接口单独熔断，
 * 避免单个接口偶发失败拖垮同域其它接口的可用性判断粒度过细
 */
object ProtocolBreakers {

    const val FAST_FAIL_CODE = -102

    enum class SendRejectionReason(val diagnosticName: String) {
        QUERY_RATE_LIMIT("query_rate_limit"),
        CIRCUIT_OPEN("circuit_open")
    }

    // 业务域常量：命令表见 OidbCommands 与各 ProtocolClient
    const val DOMAIN_CARE = "care"       // 喂食/洗澡/属性
    const val DOMAIN_CAREER = "career"   // 学业/打工/冒险/地图
    const val DOMAIN_SOCIAL = "social"   // 好友/福袋/点赞
    const val DOMAIN_PK = "pk"           // PK 竞技
    const val DOMAIN_BATH = "bath"       // 洗护库存/购买
    const val DOMAIN_QUERY = "query"     // 只读状态查询（免熔断域）

    const val MIN_QUERY_INTERVAL_MS = 15_000L

    @Volatile
    private var lastQueryTimestamp: Long = 0L

    @Volatile
    var metrics: EngineMetrics? = null

    private val manager = CircuitBreakerManager()

    private val domainConfig = CircuitBreakerConfig(
        failureThreshold = 5,
        successThreshold = 3,
        timeoutMs = 60_000L
    )

    // 命令前缀 -> 业务域（0x 后 4 位 hex 前 3 位足够区分现有命令）
    private val commandDomains: Map<String, String> = buildMap {
        put("0x95e1", DOMAIN_CARE)
        put("0x96f2", DOMAIN_CARE)
        put("0x992d", DOMAIN_CARE)
        put("0x9949", DOMAIN_CARE)
        put("0x99f2", DOMAIN_CARE)
        put("0x9c44", DOMAIN_CARE)
        put("0x975a", DOMAIN_QUERY)
        put("0x975e", DOMAIN_CAREER)
        put("0x975f", DOMAIN_CAREER)
        put("0x9760", DOMAIN_CAREER)
        put("0x9b60", DOMAIN_CAREER)
        put("0x9ab2", DOMAIN_CAREER)
        put("0x985b", DOMAIN_SOCIAL)
        put("0x985c", DOMAIN_SOCIAL)
        put("0x985d", DOMAIN_SOCIAL)
        put("0x985e", DOMAIN_SOCIAL)
        put("0x9acb", DOMAIN_SOCIAL)
        put("0x9d71", DOMAIN_SOCIAL)
        put("0x9875", DOMAIN_PK)
        put("0x96a6", DOMAIN_BATH)
        put("0x9bf1", DOMAIN_BATH)
        put("0x9bf2", DOMAIN_BATH)
        put("0x9bf3", DOMAIN_BATH)
        put("0x9bd0", DOMAIN_BATH)
    }

    fun resolveDomain(commandName: String): String {
        val prefix = commandName.substringAfter("OidbSvcTrpcTcp.").take(6)
        return commandDomains[prefix] ?: DOMAIN_CARE
    }

    /**
     * 检查是否满足只读状态查询最小请求间隔频控限制 (>= 15s)
     */
    fun canQueryStoryStatus(now: Long = System.currentTimeMillis()): Boolean {
        return (now - lastQueryTimestamp) >= MIN_QUERY_INTERVAL_MS
    }

    fun millisUntilNextQueryAllowed(now: Long = System.currentTimeMillis()): Long {
        val elapsed = now - lastQueryTimestamp
        return (MIN_QUERY_INTERVAL_MS - elapsed).coerceAtLeast(0L)
    }

    /**
     * 记录只读状态查询发送时间戳
     */
    fun markQueryStoryStatus(now: Long = System.currentTimeMillis()) {
        lastQueryTimestamp = now
    }

    /**
     * 是否允许发送该命令；熔断器打开时快速失败，不发反射包。
     * 只读状态查询 (DOMAIN_QUERY) 免受断路器影响，但受最小 15 秒限流保护。
     */
    fun allowSend(commandName: String, onRejected: ((SendRejectionReason) -> Unit)? = null): Boolean {
        val domain = resolveDomain(commandName)
        if (domain == DOMAIN_QUERY) {
            val now = System.currentTimeMillis()
            if (!canQueryStoryStatus(now)) {
                EngineLog.w(
                    "ProtocolBreakers",
                    "状态查询触发最小 15 秒限流保护 (还需等待 ${millisUntilNextQueryAllowed(now)}ms)"
                )
                onRejected?.invoke(SendRejectionReason.QUERY_RATE_LIMIT)
                return false
            }
            markQueryStoryStatus(now)
            return true
        }
        val breaker = manager.getOrCreate(domain, domainConfig)
        val allowed = breaker.allowRequest()
        if (!allowed) onRejected?.invoke(SendRejectionReason.CIRCUIT_OPEN)
        return allowed
    }

    /**
     * 记录一次网络请求（回包阶段调用）：code==0 视为成功
     */
    fun recordRequest(commandName: String, code: Int, latencyMs: Long) {
        metrics?.recordNetworkRequest(
            endpoint = commandName,
            latencyMs = latencyMs,
            success = code == 0,
            errorCode = code.takeIf { it != 0 }
        )
    }

    /**
     * 回包出口统一记录：
     * 仅网络/协议传输层故障（如 code < 0）计入 recordFailure；
     * 正常业务返回码（如今日已赞 136202、福袋已空 135091、体力/饼干不足等业务码）视为业务成功，不触发断路器熔断。
     * 只读状态查询域 (DOMAIN_QUERY) 免熔断，更新时间戳并恒定返回 Closed。
     * 返回记录后的断路器状态供日志输出。
     */
    fun recordOutcome(commandName: String, code: Int): CircuitState {
        val domain = resolveDomain(commandName)
        if (domain == DOMAIN_QUERY) {
            markQueryStoryStatus()
            return CircuitState.Closed
        }
        val breaker = manager.getOrCreate(domain, domainConfig)
        val before = breaker.getState()
        if (isTransportFailure(code)) {
            breaker.recordFailure(java.lang.RuntimeException("transport failure: code=$code"))
        } else {
            breaker.recordSuccess()
        }
        val after = breaker.getState()
        notifyTransition(breaker, before, after)
        return after
    }

    /**
     * 判断是否为网络/协议传输层故障：
     * code < 0 为底层传输错误（连接中断、超时、代理未就绪等）；
     * 业务返回码（code >= 0）无论成功或业务拒绝均说明网络传输正常。
     */
    fun isTransportFailure(code: Int): Boolean = code < 0

    private fun notifyTransition(breaker: CircuitBreaker, before: CircuitState, after: CircuitState) {
        if (before == after) return
        when (after) {
            is CircuitState.Open -> {
                EngineLog.w("[ProtocolBreakers] ${breaker.getName()} 域熔断器打开，60 秒后半开试探")
                metrics?.recordCircuitBreakerTripped(breaker.getName())
            }

            is CircuitState.Closed -> {
                metrics?.recordCircuitBreakerRecovery(breaker.getName())
            }

            else -> {}
        }
    }

    fun stateSnapshot(): Map<String, Map<String, Any>> = manager.getStateSnapshot()
}

package com.copilot.qqpet.protocol.channel

import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.engine.metrics.EngineMetrics
import com.copilot.qqpet.engine.resilience.CircuitBreaker
import com.copilot.qqpet.engine.resilience.CircuitBreakerConfig
import com.copilot.qqpet.engine.resilience.CircuitBreakerManager
import com.copilot.qqpet.engine.resilience.CircuitState

/**
 * 协议层熔断器门面：按业务域维护独立熔断器，供 OidbChannel 出口统一挂接
 *
 * 分域依据命令前缀（0x975a=Career 等）而非每个接口单独熔断，
 * 避免单个接口偶发失败拖垮同域其它接口的可用性判断粒度过细
 */
object ProtocolBreakers {

    const val FAST_FAIL_CODE = -102

    // 业务域常量：命令表见 OidbCommands 与各 ProtocolClient
    const val DOMAIN_CARE = "care"       // 喂食/洗澡/属性
    const val DOMAIN_CAREER = "career"   // 学业/打工/冒险/地图
    const val DOMAIN_SOCIAL = "social"   // 好友/福袋/点赞
    const val DOMAIN_PK = "pk"           // PK 竞技
    const val DOMAIN_BATH = "bath"       // 洗护库存/购买

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
        put("0x99df", DOMAIN_CARE)
        put("0x99f2", DOMAIN_CARE)
        put("0x9c44", DOMAIN_CARE)
        put("0x975a", DOMAIN_CAREER)
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
     * 是否允许发送该命令；熔断器打开时快速失败，不发反射包
     */
    fun allowSend(commandName: String): Boolean {
        val breaker = manager.getOrCreate(resolveDomain(commandName), domainConfig)
        return breaker.allowRequest()
    }

    /**
     * 记录一次网络请求（回包阶段调用）：code==0 视为成功
     */
    fun recordRequest(commandName: String, code: Int) {
        metrics?.recordNetworkRequest(
            endpoint = commandName,
            latencyMs = 0L,
            success = code == 0,
            errorCode = code.takeIf { it != 0 }
        )
    }

    /**
     * 回包出口统一记录：code==0 成功，其余失败
     * 返回记录后的断路器状态供日志输出
     */
    fun recordOutcome(commandName: String, code: Int): CircuitState {
        val breaker = manager.getOrCreate(resolveDomain(commandName), domainConfig)
        val before = breaker.getState()
        if (code == 0) {
            breaker.recordSuccess()
        } else {
            breaker.recordFailure(java.lang.RuntimeException("code=$code"))
        }
        val after = breaker.getState()
        notifyTransition(breaker, before, after)
        return after
    }

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

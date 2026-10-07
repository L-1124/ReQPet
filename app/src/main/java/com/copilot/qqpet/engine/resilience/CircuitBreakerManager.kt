package com.copilot.qqpet.engine.resilience

import java.util.concurrent.ConcurrentHashMap

/**
 * 熔断器管理器 - 集中管理多个熔断器实例
 */
class CircuitBreakerManager {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()

    /**
     * 获取或创建熔断器实例
     */
    fun getOrCreate(name: String, config: CircuitBreakerConfig = CircuitBreakerConfig()): CircuitBreaker {
        return breakers.getOrPut(name) {
            CircuitBreaker(name, config)
        }
    }

    /**
     * 获取已存在的熔断器
     */
    fun get(name: String): CircuitBreaker? = breakers[name]

    /**
     * 移除熔断器
     */
    fun remove(name: String) {
        breakers.remove(name)
    }

    /**
     * 清空所有熔断器
     */
    fun clear() {
        breakers.clear()
    }

    /**
     * 获取所有熔断器的状态快照
     */
    fun getStateSnapshot(): Map<String, Map<String, Any>> {
        return breakers.mapValues { (_, breaker) -> breaker.getStats() }
    }

    /**
     * 执行带熔断器的操作（便捷函数）
     */
    suspend fun <T> execute(
        breakerName: String,
        config: CircuitBreakerConfig = CircuitBreakerConfig(),
        fallback: () -> T,
        block: suspend () -> T
    ): T {
        val breaker = getOrCreate(breakerName, config)
        return breaker.execute(fallback, block)
    }

    /**
     * 执行带熔断器和重试的操作
     */
    suspend fun <T> executeWithRetry(
        breakerName: String,
        config: CircuitBreakerConfig = CircuitBreakerConfig(),
        fallback: () -> T,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        maxRetries: Int = 3,
        block: suspend () -> T
    ): T {
        val breaker = getOrCreate(breakerName, config)
        return breaker.executeWithRetry(
            fallback = fallback,
            initialDelayMs = initialDelayMs,
            maxDelayMs = maxDelayMs,
            maxRetries = maxRetries,
            block = block
        )
    }
}

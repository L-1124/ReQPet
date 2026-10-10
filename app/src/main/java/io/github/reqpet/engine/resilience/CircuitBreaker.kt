package io.github.reqpet.engine.resilience

import io.github.reqpet.engine.EngineLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/**
 * 熔断器状态机 - 三态模式
 */
sealed class CircuitState {
    /**
     * 关闭状态 - 正常运作，允许所有请求通过
     */
    object Closed : CircuitState() {
        override fun toString() = "CLOSED"
    }

    /**
     * 打开状态 - 拒绝所有请求，直接返回 fallback
     */
    object Open : CircuitState() {
        override fun toString() = "OPEN"
    }

    /**
     * 半开状态 - 允许探路请求通过，根据结果决定恢复或重新打开
     */
    object HalfOpen : CircuitState() {
        override fun toString() = "HALF_OPEN"
    }
}

/**
 * 熔断器配置参数
 */
data class CircuitBreakerConfig(
    /** 触发熔断的失败次数阈值 */
    val failureThreshold: Int = 5,

    /** 半开状态下需要连续成功多少次才能恢复为关闭状态 */
    val successThreshold: Int = 3,

    /** 打开状态后等待多久才进入半开状态 (毫秒) */
    val timeoutMs: Long = 60_000L, // 60 秒

    /** 重试时的初始延迟 (毫秒) */
    val retryDelayMs: Long = 1_000L,

    /** 重试时的最大延迟 (毫秒) */
    val maxRetryDelayMs: Long = 10_000L,

    /** 指数退避的倍数 */
    val backoffMultiplier: Double = 2.0,

    /** Jitter 因子 (±10%) */
    val jitterFactor: Double = 0.1
)

/**
 * 熔断器异常包装
 */
class CircuitOpenException(message: String) : RuntimeException(message)

/**
 * 速率限制异常
 */
class RateLimitExceededException(message: String) : RuntimeException(message)

/**
 * 指数退避重试策略
 */
object ExponentialBackoffRetryPolicy {
    /**
     * 计算下次重试延迟时间（带 jitter）
     */
    fun computeDelay(
        initialDelayMs: Long,
        maxDelayMs: Long,
        multiplier: Double,
        jitterFactor: Double,
        attempt: Int
    ): Long {
        val baseDelay = (initialDelayMs * multiplier.pow(attempt.toDouble())).toLong()
        val cappedDelay = minOf(baseDelay, maxDelayMs)

        // 添加 jitter: ±jitterFactor * delay
        val jitterRange = jitterFactor * cappedDelay
        val randomOffset = (Math.random() * jitterRange * 2) - jitterRange
        return maxOf(1L, (cappedDelay + randomOffset).toLong())
    }
}

/**
 * 单一操作的熔断器实现
 *
 * 使用滑动窗口算法跟踪最近 N 次失败，避免单次偶发故障导致误熔断
 */
class CircuitBreaker(
    private val name: String,
    private val config: CircuitBreakerConfig = CircuitBreakerConfig()
) {
    private val stateRef = AtomicReference<CircuitState>(CircuitState.Closed)

    // 滑动窗口记录最近失败的时间戳
    private val recentFailures = ConcurrentLinkedDeque<Long>()
    private val recentSuccesses = AtomicInteger(0)

    // 最后一次故障发生时间
    @Volatile
    private var lastFailureTime: Long = 0

    // 当前连续失败次数（用于计数）
    private val failureCount = AtomicInteger(0)

    /**
     * 获取当前状态
     */
    fun getState(): CircuitState = checkNotNull(stateRef.get())

    /**
     * 熔断器名称
     */
    fun getName(): String = name

    /**
     * 是否允许请求通过
     */
    fun allowRequest(): Boolean {
        val currentState = getState()

        return when (currentState) {
            is CircuitState.Closed -> true
            is CircuitState.Open -> {
                // 检查是否需要进入半开状态
                if (shouldTransitionToHalfOpen()) {
                    transitionTo(CircuitState.HalfOpen)
                    true
                } else {
                    false
                }
            }

            is CircuitState.HalfOpen -> true
        }
    }

    /**
     * 记录成功请求
     */
    fun recordSuccess() {
        when (getState()) {
            is CircuitState.Closed -> {
                // 清除失败计数
                resetFailureWindow()
            }

            is CircuitState.HalfOpen -> {
                // 增加连续成功计数
                val successes = recentSuccesses.incrementAndGet()

                if (successes >= config.successThreshold) {
                    transitionTo(CircuitState.Closed)
                    resetFailureWindow()
                }
            }

            is CircuitState.Open -> {
                // 不应该在打开状态下收到成功
            }
        }
    }

    /**
     * 记录失败请求，如果应该继续抛出异常则抛出
     * @return true 如果应该继续执行，false 如果应该停止
     */
    fun recordFailure(e: Throwable): Boolean {
        val currentState = getState()

        when (currentState) {
            is CircuitState.Closed -> {
                val now = System.currentTimeMillis()
                // 添加失败到滑动窗口
                addFailureTimestamp(now)

                // 滑动窗口内真实失败次数（避免单调递增累加误熔断）
                val count = recentFailures.size
                failureCount.set(count)

                EngineLog.w(
                    "CircuitBreaker",
                    "[$name] Failure recorded: windowCount=$count (threshold=${config.failureThreshold}): ${e.message}"
                )

                // 检查是否达到熔断阈值（按滑动窗口内真实失败次数判断）
                if (count >= config.failureThreshold) {
                    EngineLog.w(
                        "CircuitBreaker",
                        "[$name] Triggering circuit breaker OPEN after $count failures in sliding window"
                    )
                    lastFailureTime = now
                    transitionTo(CircuitState.Open)
                }
            }

            is CircuitState.HalfOpen -> {
                // 在半开状态下失败，立即回到打开状态
                EngineLog.w("CircuitBreaker", "[$name] Failure in HALF_OPEN state, transitioning to OPEN")
                lastFailureTime = System.currentTimeMillis()
                transitionTo(CircuitState.Open)
            }

            is CircuitState.Open -> {
                // 已经在打开状态，记录但不影响状态
                EngineLog.w("CircuitBreaker", "[$name] Failure while OPEN (suppressed)")
            }
        }

        return when (getState()) {
            is CircuitState.Open -> false
            else -> true
        }
    }

    /**
     * 执行受保护的函数调用
     */
    suspend fun <T> execute(
        fallback: () -> T,
        block: suspend () -> T
    ): T {
        if (!allowRequest()) {
            EngineLog.w("CircuitBreaker", "[$name] Circuit breaker is OPEN, using fallback")
            throw CircuitOpenException("Circuit breaker for $name is open")
        }

        try {
            val result = block()
            recordSuccess()
            return result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val shouldContinue = recordFailure(e)

            if (!shouldContinue) {
                EngineLog.w("CircuitBreaker", "[$name] Circuit breaker tripped, using fallback")
                return fallback()
            }

            throw e
        }
    }

    /**
     * 带重试执行的函数调用
     */
    suspend fun <T> executeWithRetry(
        fallback: () -> T,
        initialDelayMs: Long = config.retryDelayMs,
        maxDelayMs: Long = config.maxRetryDelayMs,
        multiplier: Double = config.backoffMultiplier,
        maxRetries: Int = 3,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null

        for (attempt in 0..maxRetries) {
            if (!allowRequest()) {
                EngineLog.w("CircuitBreaker", "[$name] Circuit breaker is OPEN, skipping retry #$attempt")
                return fallback()
            }

            try {
                val result = block()
                recordSuccess()
                return result
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e

                // 记录失败并检查是否应该继续重试
                val shouldContinue = recordFailure(e)

                if (!shouldContinue || attempt == maxRetries) {
                    EngineLog.w("CircuitBreaker", "[$name] All retries exhausted or circuit opened, using fallback")
                    return fallback()
                }

                // 计算下次重试延迟
                val delayMs = ExponentialBackoffRetryPolicy.computeDelay(
                    initialDelayMs = initialDelayMs,
                    maxDelayMs = maxDelayMs,
                    multiplier = multiplier,
                    jitterFactor = config.jitterFactor,
                    attempt = attempt
                )

                EngineLog.d("CircuitBreaker", "[$name] Retrying after ${delayMs}ms due to: ${e.message}")
                delay(delayMs)
            }
        }

        EngineLog.e("CircuitBreaker", "[$name] Loop completed unexpectedly, using fallback")
        return fallback()
    }

    /**
     * 重置熔断器状态
     */
    fun reset() {
        transitionTo(CircuitState.Closed)
        resetFailureWindow()
    }

    /**
     * 获取失败统计信息
     */
    fun getStats(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000L
        while (recentFailures.peekFirst()?.let { it < cutoff } == true) {
            recentFailures.pollFirst()
        }
        val windowCount = recentFailures.size
        return mapOf(
            "name" to name,
            "state" to stateRef.get().toString(),
            "failureCount" to windowCount,
            "recentFailureCount" to windowCount,
            "lastFailureTime" to lastFailureTime,
            "config_failureThreshold" to config.failureThreshold,
            "config_successThreshold" to config.successThreshold,
            "config_timeoutMs" to config.timeoutMs
        )
    }

    private fun shouldTransitionToHalfOpen(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastFailure = now - lastFailureTime
        return timeSinceLastFailure >= config.timeoutMs
    }

    private fun transitionTo(newState: CircuitState) {
        val oldState = stateRef.getAndSet(newState)
        if (oldState != newState) {
            EngineLog.i("CircuitBreaker", "[$name] State transition: $oldState → $newState")
            if (newState is CircuitState.Open || newState is CircuitState.HalfOpen) {
                recentSuccesses.set(0)
            }
        }
    }

    private fun addFailureTimestamp(timestamp: Long) {
        // 移除过期的失败记录（超过 1 分钟前的）
        val cutoff = timestamp - 60_000L
        while (recentFailures.peekFirst()?.let { it < cutoff } == true) {
            recentFailures.pollFirst()
        }

        // 添加新的失败记录
        recentFailures.addLast(timestamp)
    }

    private fun resetFailureWindow() {
        recentFailures.clear()
        failureCount.set(0)
        recentSuccesses.set(0)
    }

}

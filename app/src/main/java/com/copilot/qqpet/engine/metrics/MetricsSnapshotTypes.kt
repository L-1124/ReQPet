package com.copilot.qqpet.engine.metrics

/**
 * 指标快照数据结构：exportSnapshot() 的返回值与各维度统计类型
 */
data class MetricsSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    // 任务统计
    val taskStats: Map<String, TaskStats>,
    // 熔断器状态
    val breakerStates: Map<String, BreakerState>,
    // 缓存统计
    val cacheStats: Map<String, CacheStats>,
    // 速率限制状态
    val rateLimitStatus: Map<String, RateLimitStatus>,
    // 系统资源
    val memoryUsage: MemoryUsage,
    // 最近的事件日志
    val recentEvents: List<EventLog>
)

data class TaskStats(
    val totalAttempts: Int = 0,
    val successfulCompletions: Int = 0,
    val avgDurationMs: Double = 0.0,
    val maxDurationMs: Long = 0L,
    val minDurationMs: Long = Long.MAX_VALUE
)

data class BreakerState(
    val name: String,
    val currentState: String,
    val failureCount: Int,
    val consecutiveSuccesses: Int,
    val lastFailureTime: Long?
)

data class CacheStats(
    val name: String,
    val currentSize: Int,
    val maxSize: Int,
    val hitRate: Double,
    val hitCount: Long,
    val missCount: Long
)

data class RateLimitStatus(
    val name: String,
    val dailyLimit: Int,
    val currentCount: Int,
    val isThrottled: Boolean,
    val remainingAttempts: Int
)

data class MemoryUsage(
    val usedBytes: Long,
    val maxBytes: Long,
    val utilizationPercent: Double
)

data class EventLog(
    val timestamp: Long,
    val level: String, // INFO, WARN, ERROR
    val category: String,
    val message: String,
    val metadata: Map<String, Any>? = null
)

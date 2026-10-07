package com.copilot.qqpet.engine.metrics

import com.copilot.qqpet.engine.EngineLog
import android.content.Context
import com.copilot.qqpet.protocol.channel.ProtocolBreakers
import java.util.concurrent.ConcurrentHashMap

/**
 * 引擎指标与可观测性 - 全面的性能监控和故障分析
 *
 * 核心职责：
 * 1. 记录关键路径的性能指标（任务完成时间、成功率）
 * 2. 追踪熔断器状态变化
 * 3. 缓存命中率统计
 * 4. 速率限制事件跟踪
 * 5. 导出快照用于调试和分析
 */
interface EngineMetrics {

    fun recordTaskCompletion(
        taskType: String,
        durationMs: Long,
        success: Boolean,
        retryCount: Int = 0
    )

    fun recordTaskDispatchAttempt(
        taskType: String,
        targetUin: Long? = null,
        isSelectedFriend: Boolean = false
    )

    fun recordCircuitBreakerTripped(breakerName: String)

    fun recordCircuitBreakerRecovery(breakerName: String)

    fun recordRetryAttempt(breakerName: String, attemptNumber: Int)

    fun recordCacheHit(cacheName: String)

    fun recordCacheMiss(cacheName: String)

    fun recordCacheEviction(cacheName: String)

    fun recordRateLimitViolation(limitName: String, errorCode: Int)

    fun recordNetworkRequest(
        endpoint: String,
        latencyMs: Long,
        success: Boolean,
        errorCode: Int? = null
    )

    fun recordMemoryUsage(bytesUsed: Long, maxSize: Long)

    fun recordCoroutineScopeCreated(scopeName: String)

    fun recordCoroutineCancelled(scopeName: String, reason: String)

    fun exportSnapshot(): MetricsSnapshot

    fun exportJSON(): String

    companion object {
        const val TASK_TYPE_STUDY = "study"
        const val TASK_TYPE_WORK = "work"
        const val TASK_TYPE_ADVENTURE = "adventure"
        const val TASK_TYPE_SOCIAL = "social"
        const val TASK_TYPE_PING = "ping"

        const val CACHE_FRIEND_LIST = "friend_list"
        const val CACHE_STORY_INFO = "story_info"
        const val CACHE_PET_DETAILS = "pet_details"

        const val LIMITER_COIN_BAG = "coin_bag"
        const val LIMITER_LIKE = "like"
        const val LIMITER_PK = "pk"
    }
}

/**
 * 实现类
 */
class EngineMetricsImpl private constructor(
    private val context: Context
) : EngineMetrics {

    private val taskHistory = ConcurrentHashMap<String, MutableList<TaskRecord>>()
    private val eventLog = ConcurrentHashMap<String, ArrayDeque<EventLog>>()
    private val metricsCounter = MetricsCounter

    init {
        EngineLog.i("EngineMetrics", "EngineMetrics initialized")
    }

    override fun recordTaskCompletion(
        taskType: String,
        durationMs: Long,
        success: Boolean,
        retryCount: Int
    ) {
        val key = "${taskType}_completion"
        val record = TaskRecord(
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs,
            success = success,
            retryCount = retryCount
        )

        taskHistory.getOrPut(key) { mutableListOf() }.add(record)

        // 只保留最近的 100 条记录
        taskHistory[key]?.let { history ->
            if (history.size > 100) history.removeAt(0)
        }

        EngineLog.d("EngineMetrics", "[$taskType] Completed: ${durationMs}ms, success=$success, retries=$retryCount")
    }

    override fun recordCircuitBreakerTripped(breakerName: String) {
        logEvent("WARN", "circuit_breaker", "Circuit breaker tripped: $breakerName")
    }

    override fun recordCircuitBreakerRecovery(breakerName: String) {
        logEvent("INFO", "circuit_breaker", "Circuit breaker recovered: $breakerName")
    }

    override fun recordRetryAttempt(breakerName: String, attemptNumber: Int) {
        logEvent("INFO", "circuit_breaker", "Retry #$attemptNumber on breaker: $breakerName")
    }

    override fun recordTaskDispatchAttempt(taskType: String, targetUin: Long?, isSelectedFriend: Boolean) {
        logEvent("INFO", "task", "Dispatch attempt: $taskType target=$targetUin selectedFriend=$isSelectedFriend")
    }

    override fun recordCacheEviction(cacheName: String) {
        logEvent("INFO", "cache", "Cache eviction: $cacheName")
    }

    override fun recordNetworkRequest(endpoint: String, latencyMs: Long, success: Boolean, errorCode: Int?) {
        logEvent(
            if (success) "INFO" else "WARN", "network",
            "Request $endpoint ${latencyMs}ms success=$success code=$errorCode"
        )
    }

    override fun recordMemoryUsage(bytesUsed: Long, maxSize: Long) {
        logEvent("INFO", "memory", "Memory used=$bytesUsed max=$maxSize")
    }

    override fun recordCoroutineScopeCreated(scopeName: String) {
        logEvent("INFO", "coroutine", "Scope created: $scopeName")
    }

    override fun recordCoroutineCancelled(scopeName: String, reason: String) {
        logEvent("INFO", "coroutine", "Scope cancelled: $scopeName reason=$reason")
    }

    override fun recordCacheHit(cacheName: String) {
        metricsCounter.incrementHit(cacheName)
    }

    override fun recordCacheMiss(cacheName: String) {
        metricsCounter.incrementMiss(cacheName)
    }

    override fun recordRateLimitViolation(limitName: String, errorCode: Int) {
        metricsCounter.recordRateLimit(limitName)
        logEvent("WARN", "rate_limit", "Rate limit exceeded: $limitName (code=$errorCode)")
    }

    override fun exportSnapshot(): MetricsSnapshot {
        return MetricsSnapshot(
            timestamp = System.currentTimeMillis(),
            taskStats = computeTaskStats(),
            breakerStates = ProtocolBreakers.stateSnapshot().mapValues { (name, stats) ->
                @Suppress("UNCHECKED_CAST")
                BreakerState(
                    name = name,
                    currentState = stats["state"]?.toString().orEmpty(),
                    failureCount = (stats["failureCount"] as? Int) ?: 0,
                    consecutiveSuccesses = 0,
                    lastFailureTime = (stats["lastFailureTime"] as? Long)?.takeIf { it > 0L }
                )
            },
            cacheStats = computeCacheStats(),
            rateLimitStatus = metricsCounter.getRateLimitStatus(),
            memoryUsage = computeMemoryUsage(),
            recentEvents = getRecentEvents(20)
        )
    }

    override fun exportJSON(): String {
        val snapshot = exportSnapshot()
        return buildString {
            append("{")
            append("\"timestamp\":${snapshot.timestamp},")
            append("\"memoryUsage\":{\"usedBytes\":${snapshot.memoryUsage.usedBytes},\"maxBytes\":${snapshot.memoryUsage.maxBytes}},")
            append("\"taskStats\":{")
            append(snapshot.taskStats.entries.joinToString(",") { (name, s) ->
                "\"$name\":{\"total\":${s.totalAttempts},\"success\":${s.successfulCompletions},\"avgMs\":${s.avgDurationMs}}"
            })
            append("},")
            append("\"cacheStats\":{")
            append(snapshot.cacheStats.entries.joinToString(",") { (name, s) ->
                "\"$name\":{\"hitRate\":${s.hitRate},\"hits\":${s.hitCount},\"misses\":${s.missCount}}"
            })
            append("},")
            append("\"recentEvents\":[")
            append(snapshot.recentEvents.joinToString(",") { e ->
                "{\"ts\":${e.timestamp},\"level\":\"${e.level}\",\"category\":\"${e.category}\",\"message\":\"${
                    e.message.replace(
                        "\"",
                        "'"
                    )
                }\"}"
            })
            append("]")
            append("}")
        }
    }

    private fun computeTaskStats(): Map<String, TaskStats> {
        return taskHistory.mapValues { (_, history) ->
            if (history.isEmpty()) {
                TaskStats()
            } else {
                val successful = history.filter { it.success }.size
                val durations = history.map { it.durationMs }

                TaskStats(
                    totalAttempts = history.size,
                    successfulCompletions = successful,
                    avgDurationMs = durations.average(),
                    maxDurationMs = durations.maxOrNull() ?: 0L,
                    minDurationMs = durations.minOrNull() ?: 0L
                )
            }
        }
    }

    private fun computeCacheStats(): Map<String, CacheStats> {
        return metricsCounter.getCacheStats().mapValues { (name, stats) ->
            CacheStats(
                name = name,
                currentSize = 0,
                maxSize = 0,
                hitRate = calculateHitRate(stats.hitCount, stats.missCount),
                hitCount = stats.hitCount,
                missCount = stats.missCount
            )
        }
    }

    private fun computeMemoryUsage(): MemoryUsage {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()

        return MemoryUsage(
            usedBytes = used,
            maxBytes = max,
            utilizationPercent = ((used.toDouble() / max) * 100).toDouble()
        )
    }

    private fun calculateHitRate(hitCount: Long, missCount: Long): Double {
        val total = hitCount + missCount
        return if (total > 0) (hitCount.toDouble() / total * 100) else 100.0
    }

    private fun logEvent(level: String, category: String, message: String, metadata: Map<String, Any>? = null) {
        val event = EventLog(
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            message = message,
            metadata = metadata
        )

        val key = "${category}_events"
        val buf = eventLog.getOrPut(key) { ArrayDeque(50) }
        buf.add(event)

        // Limit buffer size
        while (buf.size > 50) {
            buf.removeFirst()
        }
    }

    private fun getRecentEvents(limit: Int): List<EventLog> {
        return eventLog.values.flatten()
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    companion object {

        // Singleton instance (lazy initialized)
        @Volatile
        private var instance: EngineMetrics? = null

        fun getInstance(context: Context): EngineMetrics {
            return instance ?: synchronized(this) {
                instance ?: EngineMetricsImpl(context).also { instance = it }
            }
        }
    }
}

package com.copilot.qqpet.engine.metrics

import java.util.concurrent.ConcurrentHashMap

data class TaskRecord(
    val timestamp: Long,
    val durationMs: Long,
    val success: Boolean,
    val retryCount: Int
)

data class CounterStats(
    var hitCount: Long = 0,
    var missCount: Long = 0
)

object MetricsCounter {
    private val cacheStats = ConcurrentHashMap<String, CounterStats>()
    private val rateLimits = ConcurrentHashMap<String, RateLimitTracker>()

    fun incrementHit(name: String) {
        cacheStats.getOrPut(name) { CounterStats() }.hitCount++
    }

    fun incrementMiss(name: String) {
        cacheStats.getOrPut(name) { CounterStats() }.missCount++
    }

    fun recordRateLimit(name: String) {
        rateLimits.getOrPut(name) { RateLimitTracker(name) }.increment()
    }

    fun getCacheStats(): Map<String, CounterStats> = cacheStats.toMap()

    fun getRateLimitStatus(): Map<String, RateLimitStatus> {
        return rateLimits.mapValues { (_, tracker) ->
            RateLimitStatus(
                name = tracker.name,
                dailyLimit = tracker.dailyLimit,
                currentCount = tracker.currentCount,
                isThrottled = tracker.isThrottled,
                remainingAttempts = maxOf(0, tracker.dailyLimit - tracker.currentCount)
            )
        }
    }
}

class RateLimitTracker(
    val name: String,
    val dailyLimit: Int = 50,
    private var count: Int = 0
) {
    var isThrottled: Boolean = false
        private set

    val currentCount: Int get() = count

    fun increment() {
        count++
        isThrottled = count >= dailyLimit
    }
}

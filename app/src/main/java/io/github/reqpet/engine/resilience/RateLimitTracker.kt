package io.github.reqpet.engine.resilience

import java.util.concurrent.ConcurrentHashMap

/**
 * 速率限制跟踪器 - 按天统计请求次数并检测是否达到上限
 */
class RateLimitTracker {
    private val recentRequests = ConcurrentHashMap<String, MutableList<Long>>()

    // 每日限额配置
    private val dailyLimits = mapOf(
        RATE_LIMITER_FRIEND_LIKE to 20,      // 每日点赞/串门上限 20 次
        RATE_LIMITER_COIN_BAG to 50          // 每日福袋领取上限 50 次
    )

    /**
     * 记录某个限流器的违规事件（收到服务器返回的限速码）
     */
    fun trackViolation(rateLimiterKey: String) {
        val now = System.currentTimeMillis()

        recentRequests.getOrPut(rateLimiterKey) { mutableListOf() }
            .apply {
                add(now)
                // 清理超过 24 小时前的记录
                removeAll { timestamp -> now - timestamp > 24 * 60 * 60 * 1000L }
            }
    }

    /**
     * 检查某个限流器是否已达到每日上限
     */
    fun isThrottled(rateLimiterKey: String): Boolean {
        val limit = dailyLimits[rateLimiterKey] ?: Int.MAX_VALUE
        val requests = recentRequests[rateLimiterKey] ?: return false

        // 统计过去 24 小时内的请求数
        val now = System.currentTimeMillis()
        val recentCount = requests.count { now - it <= 24 * 60 * 60 * 1000L }

        return recentCount >= limit
    }

    /**
     * 检查所有限流器是否有任何一个达到上限
     */
    fun anyThrottled(): Boolean {
        return dailyLimits.keys.any { isThrottled(it) }
    }

    /**
     * 重置所有计数（用于日切时调用）
     */
    fun resetDaily() {
        val now = System.currentTimeMillis()
        recentRequests.values.forEach { list ->
            list.removeAll { now - it > 24 * 60 * 60 * 1000L }
        }
    }

    /**
     * 获取某个限流器的当前状态
     */
    fun getStatus(rateLimiterKey: String): Map<String, Any> {
        val limit = dailyLimits[rateLimiterKey] ?: return emptyMap()
        val requests = recentRequests[rateLimiterKey] ?: return emptyMap()

        val now = System.currentTimeMillis()
        val recentCount = requests.count { now - it <= 24 * 60 * 60 * 1000L }

        return mapOf(
            "key" to rateLimiterKey,
            "dailyLimit" to limit,
            "currentCount" to recentCount,
            "isThrottled" to (recentCount >= limit),
            "remainingAttempts" to maxOf(0, limit - recentCount)
        )
    }

    /**
     * 获取所有限流器的状态快照
     */
    fun getStatusSnapshot(): Map<String, Map<String, Any>> {
        return dailyLimits.keys.associateWith { getStatus(it) }
    }

    companion object {
        const val RATE_LIMITER_FRIEND_LIKE = "friend_like"
        const val RATE_LIMITER_COIN_BAG = "coin_bag"
        const val TAG = "RateLimitTracker"
    }
}

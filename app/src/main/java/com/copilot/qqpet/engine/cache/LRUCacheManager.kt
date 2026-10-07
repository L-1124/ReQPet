package com.copilot.qqpet.engine.cache

import com.copilot.qqpet.engine.EngineLog
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * LRU (Least Recently Used) 缓存管理器 - 解决 @Volatile 全局变量导致的内存泄漏和数据污染
 *
 * 核心特性：
 * 1. 基于 LinkedHashMap 的自动淘汰算法
 * 2. TTL (Time-To-Live) 过期机制
 * 3. 线程安全的并发访问
 * 4. 可配置的最大容量
 * 5. 支持模式匹配的反向清理
 */
class LRUCacheManager(
    @PublishedApi
    internal val maxSize: Int = DEFAULT_MAX_SIZE,
    private val context: Context? = null
) {
    companion object {
        const val DEFAULT_MAX_SIZE = 500
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L // 默认 5 分钟 TTL
    }

    // 内部缓存容器（线程安全）
    @PublishedApi
    internal val cache = object : LinkedHashMap<String, CachedEntry<*>>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEntry<*>>): Boolean =
            size > maxSize
    }

    /**
     * 缓存条目封装
     */
    data class CachedEntry<T>(
        val value: T,
        val timestamp: Long,
        val ttlMillis: Long
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
            return (now - timestamp) > ttlMillis
        }
    }

    /**
     * 获取缓存值（带 TTL 检查）
     */
    inline fun <reified T> get(
        key: String,
        ttlMillis: Long = DEFAULT_TTL_MS
    ): T? {
        val now = System.currentTimeMillis()
        val entry = cache[key] ?: return null

        // 检查是否过期
        if (entry.isExpired(now)) {
            cache.remove(key)
            recordMiss(key)
            return null
        }

        // 命中！由于访问顺序更新（true 在构造函数中启用）， LinkedHashMap 会自动调整
        recordHit(key)
        return entry.value as? T
    }

    /**
     * 设置缓存值
     */
    inline fun <reified T> put(
        key: String,
        value: T,
        ttlMillis: Long = DEFAULT_TTL_MS
    ) {
        require(key.isNotBlank()) { "Cache key cannot be blank" }
        require(value != null) { "Cache value cannot be null" }

        cache[key] = CachedEntry(value, System.currentTimeMillis(), ttlMillis)
        EngineLog.d("LRUCacheManager", "LRU put: $key (TTL=${ttlMillis / 1000}s)")

        if (cache.size > maxSize) {
            EngineLog.w("LRUCacheManager", "Cache size exceeded max ($maxSize), triggering eviction")
        }
    }

    /**
     * 移除指定键的缓存
     */
    fun remove(key: String): Boolean {
        val removed = cache.remove(key) != null
        if (removed) {
            EngineLog.d("LRUCacheManager", "LRU remove: $key")
        }
        return removed
    }

    /**
     * 模式匹配反向前缀匹配清除
     *
     * 示例：invalidate("friend_") 会清除所有以 "friend_" 开头的缓存
     */
    fun invalidate(pattern: String) {
        val keysToRemove = cache.keys.filter { it.startsWith(pattern) }
        if (keysToRemove.isNotEmpty()) {
            keysToRemove.forEach { key ->
                cache.remove(key)
                EngineLog.d("LRUCacheManager", "LRU invalidate pattern [$pattern]: $key")
            }
        }
    }

    /**
     * 清空所有缓存
     */
    fun clear() {
        cache.clear()
        EngineLog.i("LRUCacheManager", "LRU cache cleared completely")
    }

    /**
     * 获取当前缓存大小
     */
    fun size(): Int = cache.size

    /**
     * 获取所有键列表
     */
    fun keys(): Set<String> = cache.keys.toSet()

    /**
     * 获取缓存统计信息
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "currentSize" to cache.size,
            "maxSize" to maxSize,
            "utilizationPercent" to ((cache.size.toDouble() / maxSize) * 100).toInt(),
            "entries" to cache.keys.joinToString(", ")
        )
    }

    /**
     * 预填充缓存（用于批量初始化）
     */
    fun bulkPut(items: Map<String, Any>, defaultTtlMs: Long = DEFAULT_TTL_MS) {
        items.forEach { (key, value) ->
            put(key, value, defaultTtlMs)
        }
    }

    private val hitCount = ConcurrentHashMap<String, Long>()
    private val missCount = ConcurrentHashMap<String, Long>()

    @PublishedApi
    internal fun recordHit(key: String) {
        hitCount.merge(key, 1L, Long::plus)
    }

    @PublishedApi
    internal fun recordMiss(key: String) {
        missCount.merge(key, 1L, Long::plus)
    }

    fun getHitRate(cacheName: String): Double {
        val hits = hitCount[cacheName] ?: 0L
        val misses = missCount[cacheName] ?: 0L
        val total = hits + misses
        return if (total > 0) (hits.toDouble() / total * 100) else 100.0
    }

    fun exportMetrics(): Map<String, Any> {
        return mapOf(
            "size" to cache.size,
            "max_size" to maxSize,
            "hit_rates" to hitCount.mapKeys { "cache_${it.key}" },
            "miss_rates" to missCount.mapKeys { "cache_${it.key}_miss" }
        )
    }
}

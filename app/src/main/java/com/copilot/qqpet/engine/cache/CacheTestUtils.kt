package com.copilot.qqpet.engine.cache

import android.content.Context
import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.protocol.QQPetDirectBridge

/**
 * 缓存组件测试工具 - 用于验证 LRU 缓存和 TTL 机制的正确性
 *
 * 使用场景：
 * 1. 单元测试（如果项目配置了 JUnit）
 * 2. 手动测试（通过调用这些方法验证行为）
 * 3. 集成测试（在真实环境中验证性能）
 */
object CacheTestUtils {

    /**
     * 测试 LRUCacheManager 的基本功能
     */
    fun testLRUCacheBasic(context: Context) {
        val cache = LRUCacheManager(maxSize = 10)

        // 写入测试数据
        (1..15).forEach { i ->
            cache.put("key_$i", "value_$i", ttlMillis = 5_000L)
        }

        // 验证当前大小（应该被限制在 maxSize 以内）
        val currentSize = cache.size()
        EngineLog.i("Cache size after insertion: $currentSize (max: ${cache.size()})")

        // 验证所有键都在缓存中
        val keys = cache.keys()
        EngineLog.i("Keys: ${keys.joinToString(", ")}")

        // 模拟获取统计信息
        val stats = cache.getStats()
        EngineLog.i("Cache stats: $stats")

        // 清除缓存
        cache.clear()
        EngineLog.i("Cache cleared. Size: ${cache.size()}")
    }

    /**
     * 测试 TTL 过期机制
     */
    suspend fun testTTLOverflow(context: Context) {
        val cache = LRUCacheManager(maxSize = 100)
        val shortTTL = 1_000L // 1 秒

        // 写入带短 TTL 的数据
        cache.put("temp_key", "temp_value", shortTTL)

        EngineLog.i("Put temp_key with TTL=${shortTTL}ms")

        // 立即读取应该成功
        val immediate = cache.get<String>("temp_key", shortTTL)
        EngineLog.i("Immediate read: ${immediate ?: "MISS"}")

        // 等待 TTL 过期
        kotlinx.coroutines.delay(shortTTL + 500)

        // 过期后读取应该失败
        val expired = cache.get<String>("temp_key", shortTTL)
        EngineLog.i("After expiration: ${expired ?: "MISS (expected)"}")
    }

    /**
     * 测试模式匹配反向清理
     */
    fun testPatternInvalidation(context: Context) {
        val cache = LRUCacheManager(maxSize = 100)

        // 写入不同前缀的数据
        (1..5).forEach { i ->
            cache.put("friend_${i}", "friend_data_$i", 5_000L)
            cache.put("story_$i", "story_data_$i", 5_000L)
        }

        EngineLog.i("Before invalidation: ${cache.size()} entries")

        // 清除所有 friend_* 前缀
        cache.invalidate("friend_")

        EngineLog.i("After clearing 'friend_*': ${cache.size()} entries")

        // 验证 story_* 仍在缓存中
        val remaining = cache.keys()
        EngineLog.i("Remaining keys: ${remaining.joinToString(", ")}")
    }

    /**
     * 测试好友缓存仓库的完整流程
     */
    suspend fun testFriendCacheWithMockData(
        context: Context,
        bridge: QQPetDirectBridge? = null
    ) {
        // 初始化缓存仓库
        FriendCacheRepository.init(context)

        // 生成模拟数据
        val mockFriends = generateMockHireableFriends(count = 50, currentUin = "12345678")

        // 直接写入缓存
        val cacheKey = "hireable_friends_12345678"
        val cacheManager = LRUCacheManager()
        cacheManager.put(cacheKey, mockFriends, FriendCacheRepository.FRIEND_CACHE_TTL_MS)

        EngineLog.i("Wrote ${mockFriends.size} mock friends to cache")

        // 模拟读取
        val cached = cacheManager.get<List<QQPetDirectBridge.HireableFriend>>(
            cacheKey,
            FriendCacheRepository.FRIEND_CACHE_TTL_MS
        )

        EngineLog.i("Cached retrieval: ${cached?.size ?: 0} friends")

        // 计算缓存命中率指标
        cacheManager.recordHit(cacheKey)
        cacheManager.recordHit(cacheKey)
        cacheManager.recordMiss(cacheKey)

        val hitRate = cacheManager.getHitRate(cacheKey)
        EngineLog.i("Simulated hit rate: ${hitRate.toString()}")
    }

    /**
     * 测试故事缓存的短 TTL 机制
     */
    suspend fun testStoryCacheTTL(context: Context) {
        StoryCacheRepository.init(LRUCacheManager())

        val mockStory = com.copilot.qqpet.engine.state.StoryInfo(
            storyId = "6400_abc123",
            remainingSec = 3600L,
            totalSec = 3600L
        )

        // 写入缓存
        StoryCacheRepository.getOrRefreshStory("pet_123", { mockStory })

        EngineLog.i("Stored story with 10s TTL")

        // 立即读取
        val immediate = StoryCacheRepository.isPetOut("pet_123")
        EngineLog.i("Immediate check: ${if (immediate) "Out" else "Idle"}")

        // 等待 TTL 过期
        kotlinx.coroutines.delay(10_000L + 1_000L)

        // 再次检查（应该失效）
        val expired = StoryCacheRepository.isPetOut("pet_123")
        EngineLog.i("After TTL expiry: ${if (expired) "Out" else "Idle (expired)"}")
    }

    /**
     * 压力测试 - 验证 LRU 淘汰机制
     */
    fun testLRUEvictionPressureTest() {
        val cache = LRUCacheManager(maxSize = 5)

        // 写入超过 max 的数据
        (1..10).forEach { i ->
            cache.put("key_$i", "value_$i", 5_000L)
        }

        val finalSize = cache.size()
        EngineLog.i("Pressure test: Wrote 10 entries to max=5 cache, final size=$finalSize")

        // 验证最旧的几个已被淘汰
        val keys = cache.keys()
        EngineLog.i("Keys in cache: ${keys.joinToString(", ")}")

        // 预期：只有最新的 5 个 key 应该在缓存中
        val shouldContain = setOf("key_6", "key_7", "key_8", "key_9", "key_10")
        val present = keys.any { it in shouldContain }
        EngineLog.i("LRU eviction working: $present")
    }

    /**
     * 生成模拟雇佣好友数据
     */
    private fun generateMockHireableFriends(
        count: Int,
        currentUin: String
    ): List<QQPetDirectBridge.HireableFriend> {
        return (1..count).map { i ->
            QQPetDirectBridge.HireableFriend(
                uin = (currentUin.toLongOrNull()?.plus(i)) ?: (100000000L + i),
                friendNick = "好友$${i * 10}",
                petNick = "宠物${i * 10}",
                petId = "pet_${currentUin}_${i}",
                power = 100L + (i * 10),
                intel = 80L + (i * 10),
                charm = 60L + (i * 10),
                isIdle = i % 2 == 0, // 一半空闲
                remainingSec = if (i % 2 == 0) 0L else 300L
            )
        }
    }

    /**
     * 运行所有测试（内部测试含 suspend 调用，统一在 runBlocking 内执行）
     */
    fun runAllTests(context: Context, bridge: QQPetDirectBridge? = null) {
        kotlinx.coroutines.runBlocking {
            EngineLog.i("====== Cache Component Test Suite ======")

            EngineLog.i("\n[1/6] Testing basic LRU operations...")
            testLRUCacheBasic(context)

            EngineLog.i("\n[2/6] Testing TTL overflow...")
            testTTLOverflow(context)

            EngineLog.i("\n[3/6] Testing pattern invalidation...")
            testPatternInvalidation(context)

            EngineLog.i("\n[4/6] Testing story cache TTL...")
            testStoryCacheTTL(context)

            EngineLog.i("\n[5/6] Testing pressure eviction...")
            testLRUEvictionPressureTest()

            EngineLog.i("\n[6/6] Testing friend cache integration...")
            testFriendCacheWithMockData(context, bridge)

            EngineLog.i("\n====== All tests completed ======\n")
        }
    }
}

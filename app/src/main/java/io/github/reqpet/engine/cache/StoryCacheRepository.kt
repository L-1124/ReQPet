package io.github.reqpet.engine.cache

import io.github.reqpet.engine.EngineLog
import io.github.reqpet.engine.state.StoryInfo

/**
 * 外出故事缓存仓库 - 使用 LRU + TTL 管理外出状态，消除竞态条件
 *
 * 核心职责：
 * 1. 提供单一的数据源（Single Source of Truth）避免多次查询导致的冲突
 * 2. 通过 TTL 确保数据的时效性（10 秒短 TTL）
 * 3. 支持模式匹配的反向清理（用户切换时清除相关缓存）
 */
object StoryCacheRepository {

    private const val STORY_CACHE_TTL_MS = 10_000L // 10 秒 TTL，外出状态变化频繁

    private var cacheManager: LRUCacheManager? = null

    internal fun init(cacheManager: LRUCacheManager) {
        this.cacheManager = cacheManager
    }

    /**
     * 获取或刷新故事信息
     */
    suspend fun getOrRefreshStory(
        petId: String,
        queryFunction: suspend () -> StoryInfo?,
        cacheKey: String = "story_$petId"
    ): StoryInfo? {
        val cm = cacheManager ?: return null

        // 检查缓存（TTL=10 秒，因为外出状态变化较快）
        val cached = cm.get<StoryInfo>(cacheKey, STORY_CACHE_TTL_MS)
        if (cached != null) {
            EngineLog.d("StoryCacheRepo", "Story cache HIT: $petId (${formatRemainingTime(cached.remainingSec)})")
            return cached
        }

        // 缓存未命中，执行查询并刷新缓存
        EngineLog.d("StoryCacheRepo", "Story cache MISS: fetching fresh data for $petId...")

        try {
            val freshStory = queryFunction()

            if (freshStory != null) {
                // 写入缓存
                cm.put(cacheKey, freshStory, STORY_CACHE_TTL_MS)
                EngineLog.d("StoryCacheRepo", "Cached story: ${formatRemainingTime(freshStory.remainingSec)}")
            }

            return freshStory
        } catch (e: Exception) {
            EngineLog.w("StoryCacheRepo", "Failed to refresh story info: ${e.message}")
            return null
        }
    }

    /**
     * 检查宠物是否正在外出中
     */
    fun isPetOut(petId: String): Boolean {
        val cacheKey = "story_$petId"
        return cacheManager?.get<StoryInfo>(cacheKey, STORY_CACHE_TTL_MS)?.let { story ->
            story.remainingSec > 0
        } ?: false
    }

    /**
     * 获取当前剩余时间（如果正在外出）
     */
    fun getCurrentRemainingSec(petId: String): Long? {
        val cacheKey = "story_$petId"
        return cacheManager?.get<StoryInfo>(cacheKey, STORY_CACHE_TTL_MS)?.remainingSec
    }

    /**
     * 清除特定宠物的故事缓存
     */
    fun clearForPet(petId: String) {
        val cacheKey = "story_$petId"
        cacheManager?.remove(cacheKey)
        EngineLog.d("StoryCacheRepo", "Cleared story cache for pet: $petId")
    }

    /**
     * 清除所有故事缓存（用于全局重置）
     */
    fun clearAll() {
        cacheManager?.invalidate("story_")
        EngineLog.i("StoryCacheRepo", "Cleared all story caches")
    }

    /**
     * 批量清除多个宠物的缓存
     */
    fun clearForPets(petIds: Collection<String>) {
        petIds.forEach { clearForPet(it) }
        EngineLog.d("StoryCacheRepo", "Cleared story cache for ${petIds.size} pets")
    }

    /**
     * 获取缓存统计信息
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "size" to (cacheManager?.size() ?: 0),
            "ttl_ms" to STORY_CACHE_TTL_MS,
            "entries" to (cacheManager?.keys()
                ?.filter { it.startsWith("story_") }?.joinToString(", ") ?: "")
        )
    }
}

/**
 * 格式化剩余秒数为可读文本（object 内方法与顶层工具共用）
 */
private fun formatRemainingTime(sec: Long): String {
    return when {
        sec < 60 -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分钟"
        else -> "${sec / 3600}小时${(sec % 3600) / 60}分钟"
    }
}

/**
 * 基于缓存判断是否可以派遣新任务
 */
fun canDispatchNewTask(petId: String): Boolean {
    val remaining = StoryCacheRepository.getCurrentRemainingSec(petId)
    return remaining == null || remaining <= 0
}

/**
 * 获取外出状态文本（用于日志显示）
 */
fun getStoryStatusText(petId: String): String {
    val remaining = StoryCacheRepository.getCurrentRemainingSec(petId)
    return when {
        remaining == null || remaining <= 0 -> "未外出"
        else -> "外出中 (${formatRemainingTime(remaining)})"
    }
}

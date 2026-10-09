package io.github.reqpet.engine.cache

import io.github.reqpet.engine.EngineLog
import android.content.Context
import io.github.reqpet.engine.AccountSessionGuard
import io.github.reqpet.engine.task.PetWorkTask
import io.github.reqpet.engine.state.AccountSessionStore
import io.github.reqpet.engine.state.RosterStore
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay

/**
 * 好友缓存仓库 - 使用 LRU + TTL 管理雇佣好友列表
 *
 * 替代原有的 @Volatile cachedHireableFriends，避免：
 * 1. 内存泄漏（无限增长）
 * 2. 数据污染（旧值覆盖新值）
 * 3. 无法自动失效（需要手动清理）
 */
object FriendCacheRepository {

    private var cacheManager: LRUCacheManager? = null

    internal fun init(context: Context) {
        cacheManager = LRUCacheManager(maxSize = 500, context = context)
    }

    /**
     * 获取或刷新好友列表
     */
    suspend fun getOrRefreshHireableFriends(
        context: Context,
        bridge: QQPetDirectBridge,
        currentUin: String,
        forceRefresh: Boolean = false
    ): List<QQPetDirectBridge.HireableFriend> {
        val cm = cacheManager ?: return emptyList()
        val cacheKey = "hireable_friends_$currentUin"

        // 检查缓存（除非强制刷新）
        if (!forceRefresh) {
            val cached = cm.get<List<QQPetDirectBridge.HireableFriend>>(cacheKey, FRIEND_CACHE_TTL_MS)
            if (cached != null && cached.isNotEmpty()) {
                EngineLog.d("FriendCacheRepo", "Friend cache HIT: $currentUin (${cached.size} friends)")
                return cached
            }
        }

        // 缓存未命中或强制刷新，从服务器拉取新鲜数据
        EngineLog.d("FriendCacheRepo", "Friend cache MISS or FORCE_REFRESH: fetching fresh data...")

        try {
            val freshList = RosterStore.loadCachedHireableFriends(context, currentUin)

            if (freshList.isEmpty()) {
                // 如果本地缓存为空，首次拉取
                val initialFetch = PetWorkTask.fetchAllHireableFriendsAwait(
                    context,
                    bridge,
                    currentUin = currentUin,
                    enrichSelectedAndTop = true
                )

                if (initialFetch.isNotEmpty()) {
                    // 更新到 SharedPreferences（双重保障）
                    RosterStore.saveCachedHireableFriends(context, currentUin, initialFetch)

                    // 也写入 LRU 缓存
                    cm.put(cacheKey, initialFetch, FRIEND_CACHE_TTL_MS)
                    return initialFetch
                }
            } else {
                // 有本地缓存数据，写入 LRU
                cm.put(cacheKey, freshList, FRIEND_CACHE_TTL_MS)
                return freshList
            }
        } catch (e: Exception) {
            EngineLog.w("FriendCacheRepo", "Failed to fetch hireable friends: ${e.message}")
        }

        return emptyList()
    }

    /**
     * 获取白名单中指定的好友（带详情 enrichment）
     */
    suspend fun getHireCandidatesWithDetails(
        context: Context,
        bridge: QQPetDirectBridge,
        currentUin: String,
        selectedUins: Set<Long>,
        maxEnrichCount: Int = 5
    ): List<QQPetDirectBridge.HireableFriend> {
        val allFriends = getOrRefreshHireableFriends(context, bridge, currentUin, forceRefresh = false)

        // 过滤白名单中的好友
        val matched = allFriends.filter { it.uin in selectedUins && it.petId.isNotBlank() }.toMutableList()

        if (matched.isEmpty()) {
            EngineLog.d("FriendCacheRepo", "No matching whitelist friends found")
            return emptyList()
        }

        // 按总资质降序排序
        matched.sortByDescending { it.totalAttr }

        // 对前 N 个好友进行详情刷新（功率、智力、魅力等）
        val toEnrich = matched.take(minOf(maxEnrichCount, matched.size))
        for (friend in toEnrich) {
            if (friend.petId.isNotBlank()) {
                try {
                    val enriched = PetWorkTask.enrichFriendDetailsAwait(bridge, friend)
                    val index = matched.indexOfFirst { it.uin == friend.uin }
                    if (index >= 0) {
                        matched[index] = enriched
                    }
                    delay(1000L) // 避免过快请求
                } catch (e: Exception) {
                    EngineLog.w("FriendCacheRepo", "Failed to enrich friend details: ${e.message}")
                }
            }
        }

        return matched
    }

    /**
     * 清除特定用户的好友缓存
     */
    fun clearForUser(context: Context, currentUin: String) {
        cacheManager?.invalidate("hireable_friends_$currentUin")
        RosterStore.saveCachedHireableFriends(context, currentUin, emptyList())
        EngineLog.d("FriendCacheRepo", "Cleared friend cache for user: $currentUin")
    }

    /**
     * 批量清除缓存（用于账号切换或全局清理）
     */
    fun clearAll() {
        cacheManager?.clear()
        EngineLog.i("FriendCacheRepo", "Cleared all friend caches")
    }

    /**
     * 获取缓存统计信息
     */
    fun getStats(): Map<String, Any> {
        val cm = cacheManager ?: return emptyMap()
        return mapOf(
            "friend_cache" to cm.getStats(),
            "export" to cm.exportMetrics()
        )
    }

    const val FRIEND_CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟
}

/**
 * 检查好友是否可用（空闲且有宠物 ID）
 */
fun List<QQPetDirectBridge.HireableFriend>.isFriendAvailable(uin: Long): Boolean {
    return firstOrNull { it.uin == uin }?.let { friend ->
        friend.isIdle && friend.petId.isNotBlank() && friend.totalAttr > 0
    } ?: false
}

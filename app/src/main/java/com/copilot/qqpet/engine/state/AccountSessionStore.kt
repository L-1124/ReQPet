package com.copilot.qqpet.engine.state

import com.copilot.qqpet.engine.EngineLog
import android.content.Context
import com.copilot.qqpet.engine.AccountSessionGuard
import com.copilot.qqpet.engine.cache.LRUCacheManager
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import java.time.LocalDate

/**
 * 账号绑定数据与每日额度持久化仓储
 */
object AccountSessionStore {


    @Volatile
    private var cacheManager: LRUCacheManager? = null

    private fun ensureCache(context: Context): LRUCacheManager {
        if (cacheManager == null) {
            synchronized(this) {
                if (cacheManager == null) {
                    cacheManager = LRUCacheManager(maxSize = 500, context = context)
                }
            }
        }
        return cacheManager!!
    }

    fun currentDayKey(): String {
        val today = LocalDate.now()
        return "${today.year}-${today.dayOfYear}"
    }

    fun getDailyPkCount(context: Context, uin: String): Int {
        val todayKey = currentDayKey()
        val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
        val dateKey = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_DAILY_DATE, uin)
        val countKey = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_DAILY_COUNT, uin)
        val savedDate = prefs.getString(dateKey, "") ?: ""
        return if (savedDate == todayKey) {
            prefs.getInt(countKey, 0)
        } else {
            prefs.edit().putString(dateKey, todayKey).putInt(countKey, 0).apply()
            0
        }
    }

    fun incrementDailyPkCount(context: Context, uin: String): Int {
        val todayKey = currentDayKey()
        val current = getDailyPkCount(context, uin)
        val next = current + 1
        val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_DAILY_DATE, uin), todayKey)
            .putInt(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_DAILY_COUNT, uin), next)
            .apply()
        return next
    }

    /**
     * 同步今日已点赞好友 UINs 到缓存
     */
    fun syncTodayLikedUins(context: Context, uin: String) {
        val todayKey = currentDayKey()
        val cacheKey = "liked_uins_${todayKey}_$uin"

        ensureCache(context)

        // 优先从缓存读取
        val cached = cacheManager?.get<Set<Long>>(cacheKey, 86400000L) // 24 小时 TTL
        if (!cached.isNullOrEmpty()) {
            return
        }

        // 缓存未命中或为空，从 SharedPreferences 加载
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val dateKey = AccountSessionGuard.scopedKey("key_liked_uins_date", uin)
            val csvKey = AccountSessionGuard.scopedKey("key_liked_uins_csv", uin)

            val savedDay = prefs.getString(dateKey, "") ?: ""
            val csv = prefs.getString(csvKey, "") ?: ""

            when {
                savedDay == todayKey && csv.isNotEmpty() -> {
                    // 当天数据存在且非空，加载到缓存
                    val loaded = csv.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
                    cacheManager?.put(cacheKey, loaded, 86400000L)
                }

                savedDay.isNotEmpty() -> {
                    // 日期已变更或无 CSV，清理数据和缓存
                    prefs.edit().putString(dateKey, todayKey).putString(csvKey, "").apply()
                    cacheManager?.remove(cacheKey)
                }

                else -> {
                    // 首次访问或数据已清空
                    cacheManager?.put(cacheKey, emptySet<Long>().toMutableSet(), 86400000L)
                }
            }

        } catch (e: Exception) {
            EngineLog.w("AccountSessionStore", "Failed to sync liked uins: ${e.message}")
        }
    }

    /**
     * 检查好友今天是否已点赞
     */
    fun isFriendLikedToday(context: Context, uin: String, friendUin: Long): Boolean {
        syncTodayLikedUins(context, uin)
        val todayKey = currentDayKey()
        val cacheKey = "liked_uins_${todayKey}_$uin"
        return cacheManager?.get<Set<Long>>(cacheKey, 86400000L)?.contains(friendUin) ?: false
    }

    /**
     * 获取今日所有已点赞的 UINs 集合
     */
    fun getTodayLikedUins(context: Context, uin: String): Set<Long> {
        syncTodayLikedUins(context, uin)
        val todayKey = currentDayKey()
        val cacheKey = "liked_uins_${todayKey}_$uin"
        return cacheManager?.get<Set<Long>>(cacheKey, 86400000L) ?: emptySet()
    }

    /**
     * 标记某个好友为今日已点赞
     */
    fun markFriendLikedToday(context: Context, uin: String, friendUin: Long) {
        val todayKey = currentDayKey()
        val cacheKey = "liked_uins_${todayKey}_$uin"

        ensureCache(context)

        // 从缓存获取现有集合并添加新 UIN
        val currentSet = getTodayLikedUins(context, uin).toMutableSet()
        currentSet.add(friendUin)

        // 更新缓存（24 小时 TTL）
        cacheManager?.put(cacheKey, currentSet, 86400000L)

        // 双重持久化到 SharedPreferences（容灾备份）
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val dateKey = AccountSessionGuard.scopedKey("key_liked_uins_date", uin)
            val csvKey = AccountSessionGuard.scopedKey("key_liked_uins_csv", uin)
            val csv = currentSet.joinToString(",")

            prefs.edit()
                .putString(dateKey, todayKey)
                .putString(csvKey, csv)
                .apply()
        } catch (e: Exception) {
            EngineLog.w("AccountSessionStore", "Failed to persist liked uin: ${e.message}")
        }
    }

    /**
     * 同步今日已领取福袋 ID 到缓存
     */
    fun syncTodayClaimedBags(context: Context, uin: String) {
        val todayKey = currentDayKey()
        val bagCacheKey = "claimed_bags_${todayKey}_$uin"
        val limitCacheKey = "coin_bag_limit_$uin"

        ensureCache(context)

        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val dateKey = AccountSessionGuard.scopedKey("key_coinbag_date", uin)
            val csvKey = AccountSessionGuard.scopedKey("key_coinbag_csv", uin)
            val limitKey = AccountSessionGuard.scopedKey("key_coinbag_limit", uin)

            val savedDay = prefs.getString(dateKey, "") ?: ""
            val csv = prefs.getString(csvKey, "") ?: ""

            // 检查缓存是否有效
            val cachedBags = cacheManager?.get<Set<String>>(bagCacheKey, 86400000L)
            val cachedLimit = cacheManager?.get<Boolean>(limitCacheKey, 86400000L)

            when {
                savedDay == todayKey -> {
                    // 当天数据
                    if (csv.isNotEmpty() && cachedBags == null) {
                        // 有 CSV 但未在缓存中，加载到缓存
                        val loaded = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                        cacheManager?.put(bagCacheKey, loaded, 86400000L)
                    }

                    if (cachedLimit == null) {
                        // 限额状态未在缓存中，读取并缓存
                        val limitReached = prefs.getBoolean(limitKey, false)
                        cacheManager?.put(limitCacheKey, limitReached, 86400000L)
                    }
                }

                savedDay.isNotEmpty() -> {
                    // 日期已变更，清理数据和缓存
                    cacheManager?.remove(bagCacheKey)
                    cacheManager?.remove(limitCacheKey)
                    prefs.edit()
                        .putString(dateKey, todayKey)
                        .putString(csvKey, "")
                        .putBoolean(limitKey, false)
                        .apply()
                }

                else -> {
                    // 首次访问或数据已清空
                    cacheManager?.put(bagCacheKey, emptySet<String>().toMutableSet(), 86400000L)
                    cacheManager?.put(limitCacheKey, false, 86400000L)
                }
            }

        } catch (e: Exception) {
            EngineLog.w("AccountSessionStore", "Failed to sync claimed bags: ${e.message}")
        }
    }

    /**
     * 检查某个福袋 ID 今日是否已领取
     */
    fun isCoinBagClaimedToday(context: Context, uin: String, bagId: String): Boolean {
        syncTodayClaimedBags(context, uin)
        val todayKey = currentDayKey()
        val cacheKey = "claimed_bags_${todayKey}_$uin"
        return cacheManager?.get<Set<String>>(cacheKey, 86400000L)?.contains(bagId) ?: false
    }

    /**
     * 获取今日所有已领取的福袋 ID 集合
     */
    fun getTodayClaimedBagIds(context: Context, uin: String): Set<String> {
        syncTodayClaimedBags(context, uin)
        val todayKey = currentDayKey()
        val cacheKey = "claimed_bags_${todayKey}_$uin"
        return cacheManager?.get<Set<String>>(cacheKey, 86400000L) ?: emptySet()
    }

    /**
     * 检查今日福袋领取是否已达上限
     */
    fun isCoinBagLimitReachedToday(context: Context, uin: String): Boolean {
        syncTodayClaimedBags(context, uin)
        val cacheKey = "coin_bag_limit_$uin"
        return cacheManager?.get<Boolean>(cacheKey, 86400000L) ?: false
    }

    /**
     * 标记今日福袋领取已达上限
     */
    fun markCoinBagDailyLimitReached(context: Context, uin: String) {
        ensureCache(context)
        val cacheKey = "coin_bag_limit_$uin"
        cacheManager?.put(cacheKey, true, 86400000L)

        // 同时持久化到 SharedPreferences
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(AccountSessionGuard.scopedKey("key_coinbag_limit", uin), true)
                .apply()
        } catch (e: Exception) {
            EngineLog.w("AccountSessionStore", "Failed to persist coin bag limit: ${e.message}")
        }
    }

    /**
     * 标记某个福袋今日已领取
     */
    fun markCoinBagHandledToday(
        context: Context,
        uin: String,
        bagId: String,
        limitReached: Boolean = false
    ) {
        val todayKey = currentDayKey()
        val bagCacheKey = "claimed_bags_${todayKey}_$uin"

        ensureCache(context)

        // 从缓存获取现有集合并添加新 ID
        val currentSet = getTodayClaimedBagIds(context, uin).toMutableSet()
        if (bagId.isNotEmpty()) {
            currentSet.add(bagId)
            cacheManager?.put(bagCacheKey, currentSet, 86400000L)
        }

        // 如果达到限额，更新限额状态
        if (limitReached) {
            markCoinBagDailyLimitReached(context, uin)
        }

        // 双重持久化到 SharedPreferences
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val dateKey = AccountSessionGuard.scopedKey("key_coinbag_date", uin)
            val csvKey = AccountSessionGuard.scopedKey("key_coinbag_csv", uin)
            val limitKey = AccountSessionGuard.scopedKey("key_coinbag_limit", uin)
            val csv = currentSet.joinToString(",")

            prefs.edit()
                .putString(dateKey, todayKey)
                .putString(csvKey, csv)
                .putBoolean(limitKey, limitReached || isCoinBagLimitReachedToday(context, uin))
                .apply()
        } catch (e: Exception) {
            EngineLog.w("AccountSessionStore", "Failed to persist coin bag handled: ${e.message}")
        }
    }

    fun clearAccountBoundMemoryCache(context: Context) {
        ensureCache(context)

        // 通过模式匹配清除所有用户特定缓存
        cacheManager?.invalidate("liked_uins_")
        cacheManager?.invalidate("claimed_bags_")
        cacheManager?.invalidate("coin_bag_limit_")

        EngineLog.d("AccountSessionStore", "Cleared account-bound memory cache for all users")
    }

    fun saveScopedPetId(context: Context, petId: String, runtimeUin: String? = null) {
        RosterStore.saveScopedPetId(context, petId, runtimeUin)
    }
}

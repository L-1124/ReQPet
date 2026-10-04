package com.copilot.qqpet.engine.state

import com.copilot.qqpet.protocol.QQPetDirectBridge

import android.content.Context
import com.copilot.qqpet.engine.AccountSessionGuard
import com.copilot.qqpet.ui.PreferencesHelper
import java.util.Calendar
import java.util.Collections
import java.util.HashSet

/**
 * 账号绑定数据与每日额度持久化仓储，管理日切、点赞、福袋及名单持久化
 */
object AccountSessionStore {

    private val todayLikedUins = Collections.synchronizedSet(HashSet<Long>())
    @Volatile private var lastLikeDayKey = ""
    private val todayClaimedBagIds = Collections.synchronizedSet(HashSet<String>())
    @Volatile private var lastCoinBagDayKey = ""
    @Volatile var coinBagDailyLimitReached = false

    fun currentDayKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
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

    fun syncTodayLikedUins(context: Context, uin: String) {
        val todayKey = currentDayKey()
        if (lastLikeDayKey != todayKey) {
            todayLikedUins.clear()
            lastLikeDayKey = todayKey
        }
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val dateKey = AccountSessionGuard.scopedKey("key_liked_uins_date", uin)
            val csvKey = AccountSessionGuard.scopedKey("key_liked_uins_csv", uin)
            val savedDay = prefs.getString(dateKey, "") ?: ""
            if (savedDay == todayKey) {
                val csv = prefs.getString(csvKey, "") ?: ""
                if (csv.isNotEmpty()) {
                    csv.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { todayLikedUins.add(it) }
                }
            } else if (savedDay.isNotEmpty()) {
                prefs.edit().putString(dateKey, todayKey).putString(csvKey, "").apply()
            }
        } catch (_: Throwable) {}
    }

    fun isFriendLikedToday(context: Context, uin: String, friendUin: Long): Boolean {
        syncTodayLikedUins(context, uin)
        return todayLikedUins.contains(friendUin)
    }

    fun getTodayLikedUins(context: Context, uin: String): Set<Long> {
        syncTodayLikedUins(context, uin)
        return synchronized(todayLikedUins) { HashSet(todayLikedUins) }
    }

    fun markFriendLikedToday(context: Context, uin: String, friendUin: Long) {
        val todayKey = currentDayKey()
        if (lastLikeDayKey != todayKey) {
            todayLikedUins.clear()
            lastLikeDayKey = todayKey
        }
        todayLikedUins.add(friendUin)
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val dateKey = AccountSessionGuard.scopedKey("key_liked_uins_date", uin)
            val csvKey = AccountSessionGuard.scopedKey("key_liked_uins_csv", uin)
            val csv = synchronized(todayLikedUins) { todayLikedUins.joinToString(",") }
            prefs.edit().putString(dateKey, todayKey).putString(csvKey, csv).apply()
        } catch (_: Throwable) {}
    }

    fun syncTodayClaimedBags(context: Context, uin: String) {
        val todayKey = currentDayKey()
        if (lastCoinBagDayKey != todayKey) {
            todayClaimedBagIds.clear()
            coinBagDailyLimitReached = false
            lastCoinBagDayKey = todayKey
        }
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val dateKey = AccountSessionGuard.scopedKey("key_coinbag_date", uin)
            val csvKey = AccountSessionGuard.scopedKey("key_coinbag_csv", uin)
            val savedDay = prefs.getString(dateKey, "") ?: ""
            if (savedDay == todayKey) {
                val csv = prefs.getString(csvKey, "") ?: ""
                if (csv.isNotEmpty()) {
                    csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { todayClaimedBagIds.add(it) }
                }
                coinBagDailyLimitReached = prefs.getBoolean(AccountSessionGuard.scopedKey("key_coinbag_limit", uin), false)
            } else if (savedDay.isNotEmpty()) {
                prefs.edit().putString(dateKey, todayKey).putString(csvKey, "").putBoolean(AccountSessionGuard.scopedKey("key_coinbag_limit", uin), false).apply()
            }
        } catch (_: Throwable) {}
    }

    fun isCoinBagClaimedToday(context: Context, uin: String, bagId: String): Boolean {
        syncTodayClaimedBags(context, uin)
        return todayClaimedBagIds.contains(bagId)
    }

    fun getTodayClaimedBagIds(context: Context, uin: String): Set<String> {
        syncTodayClaimedBags(context, uin)
        return synchronized(todayClaimedBagIds) { HashSet(todayClaimedBagIds) }
    }

    fun isCoinBagLimitReachedToday(context: Context, uin: String): Boolean {
        syncTodayClaimedBags(context, uin)
        return coinBagDailyLimitReached
    }

    fun markCoinBagDailyLimitReached(context: Context, uin: String) {
        coinBagDailyLimitReached = true
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(AccountSessionGuard.scopedKey("key_coinbag_limit", uin), true).apply()
        } catch (_: Throwable) {}
    }

    fun markCoinBagHandledToday(context: Context, uin: String, bagId: String, limitReached: Boolean = false) {
        val todayKey = currentDayKey()
        if (lastCoinBagDayKey != todayKey) {
            todayClaimedBagIds.clear()
            lastCoinBagDayKey = todayKey
        }
        if (bagId.isNotEmpty()) todayClaimedBagIds.add(bagId)
        if (limitReached) coinBagDailyLimitReached = true
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val dateKey = AccountSessionGuard.scopedKey("key_coinbag_date", uin)
            val csvKey = AccountSessionGuard.scopedKey("key_coinbag_csv", uin)
            val csv = synchronized(todayClaimedBagIds) { todayClaimedBagIds.joinToString(",") }
            prefs.edit().putString(dateKey, todayKey).putString(csvKey, csv).putBoolean(AccountSessionGuard.scopedKey("key_coinbag_limit", uin), coinBagDailyLimitReached).apply()
        } catch (_: Throwable) {}
    }

    fun loadSavedHireFriendUins(context: Context, uin: String): Set<Long> {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_UINS, uin)
            val csv = prefs.getString(key, "") ?: ""
            csv.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun saveHireFriendUins(context: Context, uin: String, uins: Collection<Long>) {
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_UINS, uin)
            prefs.edit().putString(key, uins.joinToString(",")).apply()
        } catch (_: Throwable) {}
    }

    fun loadSavedPkBlacklistUins(context: Context, uin: String): Set<Long> {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_BLACKLIST_UINS, uin)
            val csv = prefs.getString(key, "") ?: ""
            csv.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun savePkBlacklistUins(context: Context, uin: String, uins: Collection<Long>) {
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_BLACKLIST_UINS, uin)
            prefs.edit().putString(key, uins.joinToString(",")).apply()
        } catch (_: Throwable) {}
    }

    fun loadStrangerUinPool(context: Context, uin: String): List<Long> {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_STRANGER_UIN_POOL, uin)
            val csv = prefs.getString(key, "") ?: ""
            csv.split(",").mapNotNull { it.trim().toLongOrNull() }.distinct()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun recordStrangersToPool(context: Context, uin: String, newUins: Collection<Long>) {
        if (newUins.isEmpty()) return
        try {
            val existing = loadStrangerUinPool(context, uin).toMutableList()
            for (u in newUins) {
                if (u > 10000L && !existing.contains(u)) existing.add(u)
            }
            val trimmed = if (existing.size > 200) existing.takeLast(200) else existing
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_STRANGER_UIN_POOL, uin)
            prefs.edit().putString(key, trimmed.joinToString(",")).apply()
        } catch (_: Throwable) {}
    }

    fun clearAccountBoundMemoryCache() {
        todayLikedUins.clear()
        todayClaimedBagIds.clear()
        coinBagDailyLimitReached = false
        lastLikeDayKey = ""
        lastCoinBagDayKey = ""
    }

    fun loadCachedHireableFriends(context: Context, uin: String): List<QQPetDirectBridge.HireableFriend> {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val raw = prefs.getString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_CACHE, uin), "") ?: ""
            raw.lines().mapNotNull { line ->
                val p = line.split("\t")
                val u = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                QQPetDirectBridge.HireableFriend(
                    u, p.getOrNull(1).orEmpty(), p.getOrNull(2).orEmpty(), p.getOrNull(3).orEmpty(),
                    p.getOrNull(4)?.toLongOrNull() ?: 0L, p.getOrNull(5)?.toLongOrNull() ?: 0L, p.getOrNull(6)?.toLongOrNull() ?: 0L,
                    p.getOrNull(7)?.toBooleanStrictOrNull() ?: true, p.getOrNull(8)?.toLongOrNull() ?: 0L
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    fun saveCachedHireableFriends(context: Context, uin: String, list: List<QQPetDirectBridge.HireableFriend>) {
        try {
            val raw = list.joinToString("\n") { "${it.uin}\t${it.friendNick}\t${it.petNick}\t${it.petId}\t${it.power}\t${it.intel}\t${it.charm}\t${it.isIdle}\t${it.remainingSec}" }
            context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE).edit()
                .putString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_CACHE, uin), raw).apply()
        } catch (_: Throwable) {}
    }

    fun saveScopedPetId(context: Context, petId: String, runtimeUin: String? = null) {
        if (petId.isBlank()) return
        val owner = AccountSessionGuard.extractOwnerUinFromPetId(petId).ifEmpty { runtimeUin?.trim().orEmpty() }
        context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE).edit()
            .putString("key_cached_pet_id", petId)
            .putString(AccountSessionGuard.scopedKey("key_cached_pet_id", owner), petId)
            .apply()
    }

    fun saveSelfDispatchedWorkStoryId(context: Context, uin: String, storyId: String?) {
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val scopedKey = AccountSessionGuard.scopedKey("key_self_dispatched_work_story_id", uin)
            if (storyId.isNullOrEmpty()) {
                editor.remove("key_self_dispatched_work_story_id").remove(scopedKey)
            } else {
                editor.putString("key_self_dispatched_work_story_id", storyId)
                if (AccountSessionGuard.isValidUin(uin)) editor.putString(scopedKey, storyId)
            }
            editor.apply()
        } catch (_: Throwable) {}
    }

    fun loadSelfDispatchedWorkStoryId(context: Context, uin: String): String? {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val scopedKey = AccountSessionGuard.scopedKey("key_self_dispatched_work_story_id", uin)
            prefs.getString(scopedKey, null) ?: prefs.getString("key_self_dispatched_work_story_id", null)
        } catch (_: Throwable) {
            null
        }
    }

}

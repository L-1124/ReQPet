package io.github.reqpet.engine.state

import android.content.Context
import io.github.reqpet.engine.AccountSessionGuard
import io.github.reqpet.protocol.QQPetDirectBridge
import io.github.reqpet.ui.PreferencesHelper

/**
 * 名单持久化仓储：雇佣好友名单、PK 黑名单、陌生人蓄水池与好友缓存快照
 *
 * 从 AccountSessionStore 拆出：这些名单是低频写入的静态数据，
 * 与高频读写的每日额度（点赞/福袋）生命周期不同，无需占用 LRU 缓存
 */
object RosterStore {


    fun loadSavedHireFriendUins(context: Context, uin: String): Set<Long> {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_UINS, uin)
            val csv = prefs.getString(key, "") ?: ""
            csv.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptySet()
        }
    }

    fun saveHireFriendUins(context: Context, uin: String, uins: Collection<Long>) {
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_UINS, uin)
            prefs.edit().putString(key, uins.joinToString(",")).apply()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    fun loadSavedPkBlacklistUins(context: Context, uin: String): Set<Long> {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_BLACKLIST_UINS, uin)
            val csv = prefs.getString(key, "") ?: ""
            csv.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptySet()
        }
    }

    fun savePkBlacklistUins(context: Context, uin: String, uins: Collection<Long>) {
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_BLACKLIST_UINS, uin)
            prefs.edit().putString(key, uins.joinToString(",")).apply()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    fun loadStrangerUinPool(context: Context, uin: String): List<Long> {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val key = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_STRANGER_UIN_POOL, uin)
            val csv = prefs.getString(key, "") ?: ""
            csv.split(",").mapNotNull { it.trim().toLongOrNull() }.distinct()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    fun loadCachedHireableFriends(context: Context, uin: String): List<QQPetDirectBridge.HireableFriend> {
        return try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val raw =
                prefs.getString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_CACHE, uin), "") ?: ""
            raw.lines().mapNotNull { line ->
                val p = line.split("\t")
                val u = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                QQPetDirectBridge.HireableFriend(
                    u,
                    p.getOrNull(1).orEmpty(),
                    p.getOrNull(2).orEmpty(),
                    p.getOrNull(3).orEmpty(),
                    p.getOrNull(4)?.toLongOrNull() ?: 0L,
                    p.getOrNull(5)?.toLongOrNull() ?: 0L,
                    p.getOrNull(6)?.toLongOrNull() ?: 0L,
                    p.getOrNull(7)?.toBooleanStrictOrNull() ?: true,
                    p.getOrNull(8)?.toLongOrNull() ?: 0L
                )
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    fun saveCachedHireableFriends(context: Context, uin: String, list: List<QQPetDirectBridge.HireableFriend>) {
        try {
            val raw =
                list.joinToString("\n") { "${it.uin}\t${it.friendNick}\t${it.petNick}\t${it.petId}\t${it.power}\t${it.intel}\t${it.charm}\t${it.isIdle}\t${it.remainingSec}" }
            context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE).edit()
                .putString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_CACHE, uin), raw).apply()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    fun saveScopedPetId(context: Context, petId: String, runtimeUin: String? = null) {
        if (petId.isBlank()) return
        val owner = AccountSessionGuard.extractOwnerUinFromPetId(petId).ifEmpty { runtimeUin?.trim().orEmpty() }
        context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE).edit()
            .putString("key_cached_pet_id", petId)
            .putString(AccountSessionGuard.scopedKey("key_cached_pet_id", owner), petId)
            .apply()
    }
}

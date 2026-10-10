package io.github.reqpet.protocol

import android.content.Context
import io.github.reqpet.engine.AccountSessionGuard
import io.github.reqpet.engine.PetAdventureEngine
import java.time.LocalDate

class PurchaseGuard(private val store: Store) {
    data class State(
        val day: String = "",
        val count: Int = 0,
        val lastAttemptMillis: Long = 0L,
        val pendingItemId: String = "",
        val pendingCount: Int = 0,
        val pendingBaseline: Int = 0
    )

    interface Store {
        fun read(uin: String): State
        fun write(uin: String, state: State): Boolean
    }

    internal data class Reservation(val uin: String, val itemId: String, val count: Int, val at: Long)
    internal enum class Outcome { SUCCESS, REJECTED, UNKNOWN }

    internal fun reserveForPet(petId: String, itemId: String, count: Int, baseline: Int): Pair<Reservation?, String?> {
        val uin = AccountSessionGuard.extractOwnerUinFromPetId(petId)
        if (uin != PetAdventureEngine.currentActiveUin || !AccountSessionGuard.isValidUin(uin)) {
            return null to "采购账号未确认，暂停补购"
        }
        return reserve(uin, itemId, count, baseline = baseline)
    }

    internal fun reserve(
        uin: String,
        itemId: String,
        count: Int,
        now: Long = System.currentTimeMillis(),
        day: String = LocalDate.now().toString(),
        baseline: Int = 0
    ): Pair<Reservation?, String?> = try {
        synchronized(lock) {
            if (!AccountSessionGuard.isValidUin(uin) || itemId.isBlank() || count !in 1..MAX_PER_PURCHASE || baseline < 0) {
                return@synchronized null to "采购账号或数量无效"
            }
            val saved = store.read(uin)
            if (saved.pendingCount != 0) return@synchronized null to "上次采购结果待确认，暂停补购"
            if (saved.lastAttemptMillis > 0L && now - saved.lastAttemptMillis < COOLDOWN_MILLIS) {
                return@synchronized null to "采购冷却中，至少间隔 15 分钟"
            }
            val effectiveDay = maxOf(day, saved.day)
            val used = if (saved.day == effectiveDay) saved.count else 0
            if (used !in 0..MAX_PER_DAY || count > MAX_PER_DAY - used) {
                return@synchronized null to "今日采购已达每账号合计 $MAX_PER_DAY 个上限"
            }
            val next = State(effectiveDay, used + count, now, itemId, count, baseline)
            // 发包前落盘，进程重启后也不能重复提交结果未知的订单。
            if (!store.write(uin, next)) return@synchronized null to "采购保护无法保存，暂停补购"
            Reservation(uin, itemId, count, now) to null
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null to "采购保护读取失败，暂停补购"
    }

    internal fun complete(reservation: Reservation, outcome: Outcome) = runCatching {
        synchronized(lock) {
            if (outcome == Outcome.UNKNOWN) return@synchronized
            val state = store.read(reservation.uin)
            if (state.lastAttemptMillis != reservation.at || state.pendingItemId != reservation.itemId ||
                state.pendingCount != reservation.count
            ) return@synchronized
            store.write(
                reservation.uin,
                state.copy(
                    count = if (outcome == Outcome.REJECTED) state.count - reservation.count else state.count,
                    pendingItemId = "",
                    pendingCount = 0
                )
            )
        }
    }

    internal fun observeInventory(uin: String, itemId: String, balance: Int) = runCatching {
        synchronized(lock) {
            if (!AccountSessionGuard.isValidUin(uin)) return@synchronized
            val state = store.read(uin)
            if (state.pendingCount > 0 && state.pendingItemId == itemId &&
                balance.toLong() >= state.pendingBaseline.toLong() + state.pendingCount
            ) {
                store.write(uin, state.copy(pendingItemId = "", pendingCount = 0))
            }
        }
    }

    companion object {
        const val MAX_PER_PURCHASE = 10
        const val MAX_PER_DAY = 50
        const val BLOCKED_CODE = -103
        const val COOLDOWN_MILLIS = 15 * 60 * 1000L
        private val lock = Any()

        fun forContext(context: Context): PurchaseGuard {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            return PurchaseGuard(object : Store {
                override fun read(uin: String): State {
                    val key = AccountSessionGuard.scopedKey("key_purchase", uin)
                    return State(
                        prefs.getString("${key}_day", "").orEmpty(),
                        prefs.getInt("${key}_count", 0),
                        prefs.getLong("${key}_last", 0L),
                        prefs.getString("${key}_pending_item", "").orEmpty(),
                        prefs.getInt("${key}_pending_count", 0),
                        prefs.getInt("${key}_pending_baseline", 0)
                    )
                }

                override fun write(uin: String, state: State): Boolean {
                    val key = AccountSessionGuard.scopedKey("key_purchase", uin)
                    return prefs.edit()
                        .putString("${key}_day", state.day)
                        .putInt("${key}_count", state.count)
                        .putLong("${key}_last", state.lastAttemptMillis)
                        .putString("${key}_pending_item", state.pendingItemId)
                        .putInt("${key}_pending_count", state.pendingCount)
                        .putInt("${key}_pending_baseline", state.pendingBaseline)
                        .commit()
                }
            })
        }
    }
}

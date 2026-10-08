package com.copilot.qqpet.protocol.channel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 请求-响应配对表：为每次发包分配请求 id，保证同一次请求的回包至多投递一次，
 * 并基于 sessionGeneration 会话代数与 accountUin 严格隔离跨账号、跨会话迟到回包，
 * 丢弃超过调用方超时（见 [DEFAULT_TIMEOUT_MS]）才到达的迟到回包。
 */
class RequestTracker(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    data class Pending(
        val id: Int,
        val command: String,
        val createdAt: Long,
        val sessionGeneration: Long = 0L,
        val accountUin: String = "",
        val taskId: String? = null
    )

    private class Entry(
        val command: String,
        val createdAt: Long,
        val sessionGeneration: Long = 0L,
        val accountUin: String = "",
        val taskId: String? = null
    )

    private val seq = AtomicInteger(0)
    private val entries = ConcurrentHashMap<Int, Entry>()

    fun register(
        command: String,
        sessionGeneration: Long = 0L,
        accountUin: String = "",
        taskId: String? = null,
        now: Long = System.currentTimeMillis()
    ): Int {
        val id = seq.incrementAndGet()
        entries[id] = Entry(
            command = command,
            createdAt = now,
            sessionGeneration = sessionGeneration,
            accountUin = accountUin,
            taskId = taskId
        )
        return id
    }

    /**
     * 首次投递返回 true。以下情况返回 false 并释放配对：
     * - 重复回包；
     * - 超过 [timeoutMs] 才到达的迟到回包（此时调用方早已放弃等待）；
     * - 传入了当前代数 [currentGeneration] 且条目代数与当前代数不匹配（跨代迟到回包）；
     * - 显式传入了当前 UIN [currentUin] 且绑定账号与当前 UIN 不匹配（含登出后的空 UIN）。
     */
    fun tryDeliver(
        id: Int,
        currentGeneration: Long? = null,
        currentUin: String? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean = tryComplete(id, currentGeneration, currentUin, now)

    /** 本地拒绝或发包失败不依赖当前账号，但仍遵守代数、超时及单次完成规则。 */
    fun tryCompleteLocal(
        id: Int,
        currentGeneration: Long? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean = tryComplete(id, currentGeneration, currentUin = null, now = now)

    private fun tryComplete(
        id: Int,
        currentGeneration: Long?,
        currentUin: String?,
        now: Long
    ): Boolean {
        val entry = entries[id] ?: return false

        // 验证当前代数：若传入了当前代数且不匹配，直接返回 false 并移除请求，丢弃迟到跨号/跨代回包
        if (currentGeneration != null && entry.sessionGeneration != currentGeneration) {
            entries.remove(id, entry)
            return false
        }

        // null 表示调用方未提供账号；空字符串表示明确登出，不能接受绑定账号的回包。
        if (currentUin != null && entry.accountUin.isNotEmpty() && entry.accountUin != currentUin) {
            entries.remove(id, entry)
            return false
        }

        if (now - entry.createdAt >= timeoutMs) {
            entries.remove(id, entry)
            return false
        }
        // 原子消费条目，同时与批量作废、清理及本地/远程完成竞争。
        return entries.remove(id, entry)
    }

    /**
     * 批量作废指定代数及更早代数的未决请求。
     * 切号时调用此方法，可立即清理在途请求，彻底防止迟到回包污染新账号。
     * 返回被清理的条目列表。
     */
    fun invalidateSession(targetGeneration: Long): List<Pending> {
        val invalidated = mutableListOf<Pending>()
        for ((id, entry) in entries) {
            if (entry.sessionGeneration <= targetGeneration && entries.remove(id, entry)) {
                invalidated += Pending(
                    id = id,
                    command = entry.command,
                    createdAt = entry.createdAt,
                    sessionGeneration = entry.sessionGeneration,
                    accountUin = entry.accountUin,
                    taskId = entry.taskId
                )
            }
        }
        return invalidated
    }

    /**
     * 批量作废指定账号 UIN 的所有未决请求。
     */
    fun invalidateAccount(accountUin: String): List<Pending> {
        if (accountUin.isEmpty()) return emptyList()
        val invalidated = mutableListOf<Pending>()
        for ((id, entry) in entries) {
            if (entry.accountUin == accountUin && entries.remove(id, entry)) {
                invalidated += Pending(
                    id = id,
                    command = entry.command,
                    createdAt = entry.createdAt,
                    sessionGeneration = entry.sessionGeneration,
                    accountUin = entry.accountUin,
                    taskId = entry.taskId
                )
            }
        }
        return invalidated
    }

    /** 清空所有待配对请求 */
    fun clear(): List<Pending> {
        val cleared = mutableListOf<Pending>()
        for ((id, entry) in entries) {
            if (entries.remove(id, entry)) {
                cleared += Pending(
                    id = id,
                    command = entry.command,
                    createdAt = entry.createdAt,
                    sessionGeneration = entry.sessionGeneration,
                    accountUin = entry.accountUin,
                    taskId = entry.taskId
                )
            }
        }
        return cleared
    }

    /** 清理始终未回包的条目，返回被清理项供上层记录（配对表的内存上界） */
    fun sweepExpired(now: Long = System.currentTimeMillis()): List<Pending> {
        val expired = mutableListOf<Pending>()
        for ((id, entry) in entries) {
            if (now - entry.createdAt >= timeoutMs && entries.remove(id, entry)) {
                expired += Pending(
                    id = id,
                    command = entry.command,
                    createdAt = entry.createdAt,
                    sessionGeneration = entry.sessionGeneration,
                    accountUin = entry.accountUin,
                    taskId = entry.taskId
                )
            }
        }
        return expired
    }

    fun pendingCount(): Int = entries.size

    companion object {
        /** 不得小于调用方最大超时（当前全仓最大为 8s），否则会丢掉调用方仍在等待的回包 */
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}

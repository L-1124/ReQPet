package com.copilot.qqpet.protocol.channel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 请求-响应配对表：为每次发包分配请求 id，保证同一次请求的回包至多投递一次，
 * 并丢弃超过调用方超时（见 [DEFAULT_TIMEOUT_MS]）才到达的迟到回包。
 */
class RequestTracker(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    data class Pending(val id: Int, val command: String, val createdAt: Long)

    private class Entry(val command: String, val createdAt: Long) {
        val delivered = AtomicBoolean(false)
    }

    private val seq = AtomicInteger(0)
    private val entries = ConcurrentHashMap<Int, Entry>()

    fun register(command: String, now: Long = System.currentTimeMillis()): Int {
        val id = seq.incrementAndGet()
        entries[id] = Entry(command, now)
        return id
    }

    /**
     * 首次投递返回 true。以下情况返回 false 并释放配对：
     * 重复回包；或超过 [timeoutMs] 才到达的迟到回包（此时调用方早已放弃等待）。
     */
    fun tryDeliver(id: Int, now: Long = System.currentTimeMillis()): Boolean {
        val entry = entries[id] ?: return false
        if (now - entry.createdAt >= timeoutMs) {
            entries.remove(id)
            return false
        }
        if (!entry.delivered.compareAndSet(false, true)) return false
        entries.remove(id)
        return true
    }

    /** 清理始终未回包的条目，返回被清理项供上层记录（配对表的内存上界） */
    fun sweepExpired(now: Long = System.currentTimeMillis()): List<Pending> {
        val expired = mutableListOf<Pending>()
        for ((id, entry) in entries) {
            if (now - entry.createdAt >= timeoutMs && entries.remove(id, entry)) {
                expired += Pending(id, entry.command, entry.createdAt)
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

package com.copilot.qqpet.protocol

import com.copilot.qqpet.protocol.channel.RequestTracker
import com.copilot.qqpet.protocol.channel.RequestTracker.RejectionReason
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTrackerTest {

    @Test
    fun `同一请求的回包只投递一次`() {
        val tracker = RequestTracker()
        val id = tracker.register("OidbSvcTrpcTcp.0x975a_1")

        assertTrue(tracker.tryDeliver(id))
        assertFalse(tracker.tryDeliver(id))
        assertFalse(tracker.tryDeliver(id))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `不同请求各自独立配对`() {
        val tracker = RequestTracker()
        val first = tracker.register("cmd_a")
        val second = tracker.register("cmd_b")

        assertTrue(tracker.tryDeliver(first))
        assertFalse(tracker.tryDeliver(first))
        assertTrue(tracker.tryDeliver(second))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `超过调用方超时的迟到回包被丢弃并释放配对`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_late", now = 0L)

        // 调用方在 8s 放弃等待，回包 8.5s 才到
        assertFalse(tracker.tryDeliver(id, now = 8_500L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `未超过调用方超时的回包正常投递`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_ok", now = 0L)

        assertTrue(tracker.tryDeliver(id, now = 7_999L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `超时清理后迟到的回包被丢弃`() {
        val tracker = RequestTracker(timeoutMs = 1000L)
        val id = tracker.register("cmd_slow", now = 10_000L)

        assertTrue(tracker.sweepExpired(now = 10_500L).isEmpty())
        assertEquals(1, tracker.pendingCount())

        val expired = tracker.sweepExpired(now = 11_000L)
        assertEquals(1, expired.size)
        assertEquals(id, expired[0].id)
        assertEquals("cmd_slow", expired[0].command)
        assertEquals(0, tracker.pendingCount())

        assertFalse(tracker.tryDeliver(id))
    }

    @Test
    fun `未回包请求在清理前保持待配对数`() {
        val tracker = RequestTracker(timeoutMs = 30_000L)
        tracker.register("cmd_1", now = 0L)
        tracker.register("cmd_2", now = 1_000L)

        assertEquals(2, tracker.pendingCount())
        assertTrue(tracker.sweepExpired(now = 20_000L).isEmpty())
        assertEquals(2, tracker.pendingCount())
    }

    @Test
    fun `未注册的请求 id 不会被投递`() {
        val tracker = RequestTracker()
        assertFalse(tracker.tryDeliver(999))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `代数一致时正常投递`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_gen1", sessionGeneration = 1L, accountUin = "910298997")

        assertTrue(tracker.tryDeliver(id, currentGeneration = 1L, currentUin = "910298997"))
        assertFalse(tracker.tryDeliver(id, currentGeneration = 1L, currentUin = "910298997"))
        assertFalse(tracker.tryCompleteLocal(id, currentGeneration = 1L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `代数不一致时直接丢弃并移除配对`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_gen1", sessionGeneration = 1L, accountUin = "910298997")

        // 切号后代数升级为 2L，迟到回包必须被丢弃
        assertFalse(tracker.tryDeliver(id, currentGeneration = 2L, currentUin = "910298997"))
        assertEquals(0, tracker.pendingCount())
        // 再次投递同样失败
        assertFalse(tracker.tryDeliver(id, currentGeneration = 1L))
    }

    @Test
    fun `UIN不一致时直接丢弃并移除配对`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_uin1", sessionGeneration = 1L, accountUin = "910298997")

        // 切号到小号 813380203，大号请求迟到回包必须被丢弃
        assertFalse(tracker.tryDeliver(id, currentGeneration = 1L, currentUin = "813380203"))
        assertFalse(tracker.tryCompleteLocal(id, currentGeneration = 1L))
        assertFalse(tracker.tryDeliver(id, currentGeneration = 1L, currentUin = "910298997"))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `显式登出空UIN丢弃绑定账号回包且不能再次完成`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_logout", sessionGeneration = 1L, accountUin = "910298997")

        assertFalse(tracker.tryDeliver(id, currentGeneration = 1L, currentUin = ""))
        assertFalse(tracker.tryDeliver(id, currentGeneration = 1L, currentUin = "910298997"))
        assertFalse(tracker.tryCompleteLocal(id, currentGeneration = 1L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `未提供当前UIN的独立调用方仍可完成绑定账号请求`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_standalone", accountUin = "910298997")

        assertTrue(tracker.tryDeliver(id))
        assertFalse(tracker.tryDeliver(id))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `本地账号拒绝不受当前账号不同影响且只完成一次`() {
        val tracker = RequestTracker()
        // 通道发现 live=813380203 与 active=910298997 不同，注册后直接本地拒绝。
        val id = tracker.register("cmd_account_rejected", sessionGeneration = 1L, accountUin = "813380203")
        var callbacks = 0

        if (tracker.tryCompleteLocal(id, currentGeneration = 1L)) callbacks++
        if (tracker.tryCompleteLocal(id, currentGeneration = 1L)) callbacks++
        if (tracker.tryDeliver(id, currentGeneration = 1L, currentUin = "813380203")) callbacks++
        if (tracker.tryDeliver(id, currentGeneration = 1L, currentUin = "910298997")) callbacks++

        assertEquals(1, callbacks)
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `本地无效登录态拒绝可立即完成`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_logged_out", sessionGeneration = 1L, accountUin = "")

        assertTrue(tracker.tryCompleteLocal(id, currentGeneration = 1L))
        assertFalse(tracker.tryCompleteLocal(id, currentGeneration = 1L))
        assertFalse(tracker.tryDeliver(id, currentGeneration = 1L, currentUin = ""))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `跨代的本地完成被丢弃后不能重新完成`() {
        val tracker = RequestTracker()
        val id = tracker.register("cmd_old_local", sessionGeneration = 1L, now = 0L)

        assertFalse(tracker.tryCompleteLocal(id, currentGeneration = 2L, now = 1L))
        assertFalse(tracker.tryCompleteLocal(id, currentGeneration = 1L, now = 1L))
        assertFalse(tracker.tryDeliver(id, currentGeneration = 1L, now = 1L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `超时的本地完成被丢弃后不能重新完成`() {
        val tracker = RequestTracker(timeoutMs = 1000L)
        val id = tracker.register("cmd_expired_local", now = 0L)

        assertFalse(tracker.tryCompleteLocal(id, now = 1000L))
        assertFalse(tracker.tryCompleteLocal(id, now = 999L))
        assertFalse(tracker.tryDeliver(id, now = 999L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `invalidateSession批量作废目标代数及更早代数的未决请求`() {
        val tracker = RequestTracker()
        val id1 = tracker.register("cmd_old1", sessionGeneration = 1L)
        val id2 = tracker.register("cmd_old2", sessionGeneration = 1L)
        val id3 = tracker.register("cmd_new", sessionGeneration = 2L)

        assertEquals(3, tracker.pendingCount())
        val invalidated = tracker.invalidateSession(1L)
        assertEquals(2, invalidated.size)
        assertEquals(1, tracker.pendingCount())

        // 废弃的请求无法再投递
        assertFalse(tracker.tryDeliver(id1, currentGeneration = 1L))
        assertFalse(tracker.tryDeliver(id2, currentGeneration = 1L))
        assertFalse(tracker.tryCompleteLocal(id1, currentGeneration = 1L))
        assertFalse(tracker.tryCompleteLocal(id2, currentGeneration = 1L))
        // 新代数的请求仍能正常投递
        assertTrue(tracker.tryDeliver(id3, currentGeneration = 2L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `invalidateAccount批量作废指定UIN的未决请求`() {
        val tracker = RequestTracker()
        val id1 = tracker.register("cmd_a1", accountUin = "111")
        val id2 = tracker.register("cmd_a2", accountUin = "111")
        val id3 = tracker.register("cmd_b1", accountUin = "222")

        val invalidated = tracker.invalidateAccount("111")
        assertEquals(2, invalidated.size)
        assertEquals(1, tracker.pendingCount())

        assertFalse(tracker.tryDeliver(id1, currentUin = "111"))
        assertTrue(tracker.tryDeliver(id3, currentUin = "222"))
    }

    @Test
    fun `拒绝原因遵守代数账号超时优先级且消费后只能报告缺失`() {
        val tracker = RequestTracker(timeoutMs = 1000L)
        val reasons = mutableListOf<RejectionReason>()
        val generation = tracker.register("cmd", sessionGeneration = 1L, accountUin = "111", now = 0L)
        assertFalse(tracker.tryDeliver(generation, 2L, "222", 1000L, reasons::add))
        assertFalse(tracker.tryDeliver(generation, 1L, "111", 999L, reasons::add))
        val account = tracker.register("cmd", sessionGeneration = 1L, accountUin = "111", now = 0L)
        assertFalse(tracker.tryDeliver(account, 1L, "", 1000L, reasons::add))
        val timeout = tracker.register("cmd", sessionGeneration = 1L, accountUin = "111", now = 0L)
        assertFalse(tracker.tryDeliver(timeout, 1L, "111", 1000L, reasons::add))

        assertEquals(
            listOf(
                RejectionReason.GENERATION,
                RejectionReason.MISSING_OR_ALREADY_COMPLETED,
                RejectionReason.ACCOUNT,
                RejectionReason.TIMEOUT
            ),
            reasons
        )
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `本地完成忽略账号但准确报告代数超时及缺失`() {
        val tracker = RequestTracker(timeoutMs = 1000L)
        val reasons = mutableListOf<RejectionReason>()
        val generation = tracker.register("cmd", sessionGeneration = 1L, now = 0L)
        assertFalse(tracker.tryCompleteLocal(generation, 2L, 1000L, reasons::add))
        val timeout = tracker.register("cmd", sessionGeneration = 1L, now = 0L)
        assertFalse(tracker.tryCompleteLocal(timeout, 1L, 1000L, reasons::add))
        val accepted = tracker.register("cmd", sessionGeneration = 1L, accountUin = "111", now = 0L)
        assertTrue(tracker.tryCompleteLocal(accepted, 1L, 999L, reasons::add))
        assertFalse(tracker.tryDeliver(accepted, 1L, "222", 999L, reasons::add))

        assertEquals(
            listOf(RejectionReason.GENERATION, RejectionReason.TIMEOUT, RejectionReason.MISSING_OR_ALREADY_COMPLETED),
            reasons
        )
    }

    @Test
    fun `作废及清理后不保留拒绝原因墓碑`() {
        val tracker = RequestTracker(timeoutMs = 1000L)
        val reasons = mutableListOf<RejectionReason>()
        val invalidated = tracker.register("cmd", sessionGeneration = 1L, now = 0L)
        tracker.invalidateSession(1L)
        assertFalse(tracker.tryDeliver(invalidated, 2L, "", 1000L, reasons::add))
        val expired = tracker.register("cmd", now = 0L)
        tracker.sweepExpired(1000L)
        assertFalse(tracker.tryCompleteLocal(expired, now = 1000L, onRejected = reasons::add))
        val cleared = tracker.register("cmd", now = 0L)
        tracker.clear()
        assertFalse(tracker.tryDeliver(cleared, now = 1L, onRejected = reasons::add))
        assertEquals(List(3) { RejectionReason.MISSING_OR_ALREADY_COMPLETED }, reasons)
    }

    @Test
    fun `成功回包不调用拒绝观察者且后续本地完成不能重复投递`() {
        val tracker = RequestTracker()
        val reasons = mutableListOf<RejectionReason>()
        val id = tracker.register("cmd", sessionGeneration = 1L, accountUin = "111", now = 0L)
        assertTrue(tracker.tryDeliver(id, 1L, "111", 1L, reasons::add))
        assertTrue(reasons.isEmpty())
        assertFalse(tracker.tryCompleteLocal(id, 1L, 1L, reasons::add))
        assertEquals(listOf(RejectionReason.MISSING_OR_ALREADY_COMPLETED), reasons)
    }

    @Test
    fun `回包本地完成和代数拒绝竞争时只有原子赢家能报告完成或具体拒绝`() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            repeat(50) {
                val tracker = RequestTracker()
                val id = tracker.register("cmd", sessionGeneration = 1L, accountUin = "111", now = 0L)
                val ready = CountDownLatch(3)
                val start = CountDownLatch(1)
                val futures = (0..2).map { mode ->
                    executor.submit(Callable {
                        var rejection: RejectionReason? = null
                        ready.countDown()
                        start.await()
                        val accepted = if (mode == 0) {
                            tracker.tryCompleteLocal(id, 1L, 1L) { rejection = it }
                        } else {
                            tracker.tryDeliver(id, if (mode == 1) 1L else 2L, "111", 1L) { rejection = it }
                        }
                        accepted to rejection
                    })
                }
                ready.await()
                start.countDown()
                val results = futures.map { it.get() }
                assertEquals(1, results.count { it.first || it.second == RejectionReason.GENERATION })
                assertEquals(2, results.count { it.second == RejectionReason.MISSING_OR_ALREADY_COMPLETED })
                assertEquals(0, tracker.pendingCount())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}

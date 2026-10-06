package com.copilot.qqpet.protocol

import com.copilot.qqpet.protocol.channel.RequestTracker
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
}

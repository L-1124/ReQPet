package io.github.reqpet.engine.resilience

import io.github.reqpet.protocol.channel.ProtocolBreakers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitBreakerTest {

    @Test
    fun testCircuitBreakerTripsWhenFailuresExceedThresholdInWindow() {
        val config = CircuitBreakerConfig(
            failureThreshold = 3,
            successThreshold = 2,
            timeoutMs = 10_000L
        )
        val breaker = CircuitBreaker("test-breaker", config)

        assertTrue(breaker.allowRequest())
        assertEquals(CircuitState.Closed, breaker.getState())

        // 记录 2 次失败，未达阈值 3
        breaker.recordFailure(RuntimeException("err1"))
        breaker.recordFailure(RuntimeException("err2"))
        assertEquals(CircuitState.Closed, breaker.getState())
        assertTrue(breaker.allowRequest())

        // 第 3 次失败，达到阈值，触发熔断
        breaker.recordFailure(RuntimeException("err3"))
        assertEquals(CircuitState.Open, breaker.getState())
        assertFalse(breaker.allowRequest())
    }

    @Test
    fun testCircuitBreakerSuccessResetsWindow() {
        val config = CircuitBreakerConfig(
            failureThreshold = 3,
            successThreshold = 2,
            timeoutMs = 10_000L
        )
        val breaker = CircuitBreaker("test-reset", config)

        breaker.recordFailure(RuntimeException("err1"))
        breaker.recordFailure(RuntimeException("err2"))
        assertEquals(2, breaker.getStats()["failureCount"])

        // 成功调用重置失败窗口
        breaker.recordSuccess()
        assertEquals(0, breaker.getStats()["failureCount"])
        assertEquals(CircuitState.Closed, breaker.getState())

        // 之后即使再失败 2 次也不会熔断（需要重新凑满 3 次）
        breaker.recordFailure(RuntimeException("err3"))
        breaker.recordFailure(RuntimeException("err4"))
        assertEquals(CircuitState.Closed, breaker.getState())
    }

    @Test
    fun testCancellationExceptionNotRecordedInExecute() = runBlocking {
        val config = CircuitBreakerConfig(failureThreshold = 2)
        val breaker = CircuitBreaker("test-cancel", config)

        try {
            breaker.execute(fallback = { "fallback" }) {
                throw CancellationException("job cancelled")
            }
        } catch (e: CancellationException) {
            // 正常捕获
        }

        // CancellationException 不应计入失败
        assertEquals(0, breaker.getStats()["failureCount"])
        assertEquals(CircuitState.Closed, breaker.getState())
    }

    @Test
    fun testProtocolBreakersQueryDomainDecoupledFromCareer() {
        // 0x975a 划入独立的免熔断域 DOMAIN_QUERY
        val queryDomain = ProtocolBreakers.resolveDomain("OidbSvcTrpcTcp.0x975a_1")
        assertEquals(ProtocolBreakers.DOMAIN_QUERY, queryDomain)

        // 其他 career 指令仍在 DOMAIN_CAREER
        val workDomain = ProtocolBreakers.resolveDomain("OidbSvcTrpcTcp.0x975e_1")
        assertEquals(ProtocolBreakers.DOMAIN_CAREER, workDomain)

        // DOMAIN_QUERY 恒定允许发送
        assertTrue(ProtocolBreakers.allowSend("OidbSvcTrpcTcp.0x975a_1"))
    }

    @Test
    fun testProtocolBreakersNormalBusinessCodesDoNotTripBreaker() {
        val cmd = "OidbSvcTrpcTcp.0x985b_1" // social domain

        // 业务码 136202（今日已赞）、135091（福袋已空）、1000210（饼干不足）
        // 均为 code >= 0，不应判定为传输故障
        assertFalse(ProtocolBreakers.isTransportFailure(0))
        assertFalse(ProtocolBreakers.isTransportFailure(136202))
        assertFalse(ProtocolBreakers.isTransportFailure(135091))
        assertFalse(ProtocolBreakers.isTransportFailure(135098))

        // 只有负数才是传输故障
        assertTrue(ProtocolBreakers.isTransportFailure(-1))
        assertTrue(ProtocolBreakers.isTransportFailure(-100))

        // 连续 10 次正常业务码不触发熔断
        for (i in 1..10) {
            val state = ProtocolBreakers.recordOutcome(cmd, 136202)
            assertEquals(CircuitState.Closed, state)
        }
        assertTrue(ProtocolBreakers.allowSend(cmd))
    }

    @Test
    fun testProtocolBreakersQueryRateLimit() {
        val now = 1_000_000L
        ProtocolBreakers.markQueryStoryStatus(now)

        // 14 秒后，未满 15 秒，不允许发起
        assertFalse(ProtocolBreakers.canQueryStoryStatus(now + 14_000L))

        // 15 秒后，允许发起
        assertTrue(ProtocolBreakers.canQueryStoryStatus(now + 15_000L))

        // 20 秒后，允许发起
        assertTrue(ProtocolBreakers.canQueryStoryStatus(now + 20_000L))
    }
}

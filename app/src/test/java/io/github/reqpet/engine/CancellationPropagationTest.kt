package io.github.reqpet.engine

import io.github.reqpet.engine.cache.LRUCacheManager
import io.github.reqpet.engine.cache.StoryCacheRepository
import io.github.reqpet.engine.state.AdventureState
import io.github.reqpet.engine.state.OutdoorStatusMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CancellationPropagationTest {
    @After
    fun tearDown() { StoryCacheRepository.clearAll() }

    @Test
    fun cancellingStoryRefreshStopsCallerAndDoesNotCacheFailure() = runBlocking {
        StoryCacheRepository.init(LRUCacheManager())
        val entered = CompletableDeferred<Unit>()
        var continued = false
        val job = launch {
            StoryCacheRepository.getOrRefreshStory("pet", queryFunction = {
                entered.complete(Unit)
                awaitCancellation()
            })
            continued = true
        }
        entered.await()
        job.cancelAndJoin()
        assertFalse(continued)
        assertNull(StoryCacheRepository.getCurrentRemainingSec("pet"))
    }

    @Test
    fun cancellingOutdoorQueryStopsCallerWithoutChangingState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val monitor = OutdoorStatusMonitor {
            entered.complete(Unit)
            awaitCancellation()
        }
        var continued = false
        val job = launch {
            monitor.updateFromQuery("pet")
            continued = true
        }
        entered.await()
        job.cancelAndJoin()
        assertFalse(continued)
        assertSame(AdventureState.Idle, monitor.statusFlow.value)
    }

    @Test
    fun queryCancellationIsPropagatedUnchanged() = runBlocking {
        val cancellation = CancellationException("session changed")
        val monitor = OutdoorStatusMonitor { throw cancellation }
        try {
            monitor.updateFromQuery("pet")
            fail("取消不能转换成普通查询失败")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }

    @Test
    fun ordinaryQueryFailuresKeepFallbackBehavior() = runBlocking {
        StoryCacheRepository.init(LRUCacheManager())
        assertNull(StoryCacheRepository.getOrRefreshStory("pet", { error("offline") }))
        val monitor = OutdoorStatusMonitor { error("offline") }
        assertFalse(monitor.updateFromQuery("pet"))
    }
}

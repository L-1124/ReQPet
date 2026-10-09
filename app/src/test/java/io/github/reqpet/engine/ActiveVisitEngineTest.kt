package io.github.reqpet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ActiveVisitEngineTest {

    @Test
    fun extractStrangersFromVisitorsExcludesFriendsAndSelf() {
        val ownUin = 10001L
        val friendUins = setOf(20001L, 20002L)
        val visitorUins = listOf(10001L, 20001L, 30001L, 30002L, 0L, -5L)

        val strangers = ActiveVisitHelper.extractStrangersFromVisitors(
            visitorUins = visitorUins,
            ownUin = ownUin,
            friendUins = friendUins
        )

        assertEquals(setOf(30001L, 30002L), strangers)
    }

    @Test
    fun sampleRandomStrangersHonorsQuotaAndExcludesAlreadyLiked() {
        val pool = setOf(30001L, 30002L, 30003L, 30004L, 30005L)
        val todayLiked = setOf(30001L, 30003L)

        val sampled = ActiveVisitHelper.sampleRandomStrangers(
            pool = pool,
            todayLikedUins = todayLiked,
            quota = 2,
            random = Random(42)
        )

        assertEquals(2, sampled.size)
        assertFalse(sampled.contains(30001L))
        assertFalse(sampled.contains(30003L))
        assertTrue(pool.containsAll(sampled))
    }

    @Test
    fun resolveActiveVisitTargetsCombinesFriendsAndStrangersWithinLimit() {
        val friendUins = listOf(20001L, 20002L, 20003L)
        val strangerPool = setOf(30001L, 30002L, 30003L, 30004L)
        val todayLiked = setOf(20001L)

        val targets = ActiveVisitHelper.resolveActiveVisitTargets(
            friendUins = friendUins,
            strangerPool = strangerPool,
            enableFriends = true,
            enableStrangers = true,
            todayLikedUins = todayLiked,
            maxDailyLimit = 4,
            random = Random(123)
        )

        assertEquals(4, targets.size)
        assertFalse(targets.any { it.uin == 20001L })
        assertTrue(targets.any { it.uin == 20002L && it.isFriend })
        assertTrue(targets.any { it.uin == 20003L && it.isFriend })
        val strangersInTargets = targets.filter { !it.isFriend }
        assertEquals(2, strangersInTargets.size)
        assertTrue(strangerPool.containsAll(strangersInTargets.map { it.uin }))
    }

    @Test
    fun resolveActiveVisitTargetsCanToggleIndependently() {
        val friendUins = listOf(20001L, 20002L)
        val strangerPool = setOf(30001L, 30002L)
        val todayLiked = emptySet<Long>()

        val onlyStrangers = ActiveVisitHelper.resolveActiveVisitTargets(
            friendUins = friendUins,
            strangerPool = strangerPool,
            enableFriends = false,
            enableStrangers = true,
            todayLikedUins = todayLiked,
            maxDailyLimit = 10
        )
        assertEquals(2, onlyStrangers.size)
        assertTrue(onlyStrangers.all { !it.isFriend })

        val onlyFriends = ActiveVisitHelper.resolveActiveVisitTargets(
            friendUins = friendUins,
            strangerPool = strangerPool,
            enableFriends = true,
            enableStrangers = false,
            todayLikedUins = todayLiked,
            maxDailyLimit = 10
        )
        assertEquals(2, onlyFriends.size)
        assertTrue(onlyFriends.all { it.isFriend })
    }

    @Test
    fun sampleRandomStrangersReturnsEmptyWhenPoolEmptyOrQuotaZero() {
        val emptyPool = emptySet<Long>()
        val emptyResult = ActiveVisitHelper.sampleRandomStrangers(emptyPool, emptySet(), 10)
        assertTrue(emptyResult.isEmpty())

        val pool = setOf(1001L, 1002L)
        val zeroQuota = ActiveVisitHelper.sampleRandomStrangers(pool, emptySet(), 0)
        assertTrue(zeroQuota.isEmpty())
    }

    @Test
    fun resolveActiveVisitTargetsReturnsEmptyWhenDailyLimitZero() {
        val targets = ActiveVisitHelper.resolveActiveVisitTargets(
            friendUins = listOf(1001L),
            strangerPool = setOf(2001L),
            enableFriends = true,
            enableStrangers = true,
            todayLikedUins = emptySet(),
            maxDailyLimit = 0
        )
        assertTrue(targets.isEmpty())
    }
}

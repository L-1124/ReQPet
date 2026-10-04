package com.copilot.qqpet.engine

import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBusinessRulesTest {

    @Test
    fun calculateFeedingRoundsShouldHonorDeficitAndSessionLimit() {
        // 当前 30，目标 80 -> 差额 50，需喂 3 次 (+60)
        val rounds1 = PetPureCalculations.calculateFeedingRounds(
            currentEnergy = 30,
            targetThreshold = 80,
            energyPerFeed = 20,
            maxRoundsPerSession = 5
        )
        assertEquals(3, rounds1)

        // 当前 75，目标 80 -> 差额 5，需喂 1 次 (+20)
        val rounds2 = PetPureCalculations.calculateFeedingRounds(
            currentEnergy = 75,
            targetThreshold = 80,
            energyPerFeed = 20,
            maxRoundsPerSession = 5
        )
        assertEquals(1, rounds2)

        // 当前 85，目标 80 -> 已充足，返回 0
        val rounds3 = PetPureCalculations.calculateFeedingRounds(
            currentEnergy = 85,
            targetThreshold = 80,
            energyPerFeed = 20,
            maxRoundsPerSession = 5
        )
        assertEquals(0, rounds3)

        // 当前 0，目标 100 -> 差额 100，需喂 5 次达到上限
        val rounds4 = PetPureCalculations.calculateFeedingRounds(
            currentEnergy = 0,
            targetThreshold = 100,
            energyPerFeed = 20,
            maxRoundsPerSession = 5
        )
        assertEquals(5, rounds4)
    }

    @Test
    fun filterPendingCoinBagsPrioritizesSelfAndCapsFriends() {
        val bags = listOf(
            QQPetDirectBridge.FriendCoinBagInfo(1001L, "友1", "p1", "宠1", "bag_friend_1", false),
            QQPetDirectBridge.FriendCoinBagInfo(9999L, "我", "p0", "我宠", "bag_self", true),
            QQPetDirectBridge.FriendCoinBagInfo(1002L, "友2", "p2", "宠2", "bag_friend_2", false),
            QQPetDirectBridge.FriendCoinBagInfo(1003L, "友3", "p3", "宠3", "bag_friend_3", false),
            QQPetDirectBridge.FriendCoinBagInfo(1004L, "友4", "p4", "宠4", "bag_friend_4", false),
            QQPetDirectBridge.FriendCoinBagInfo(1005L, "友5", "p5", "宠5", "bag_friend_5", false),
            QQPetDirectBridge.FriendCoinBagInfo(1006L, "友6", "p6", "宠6", "bag_friend_6", false)
        )
        val todayClaimed = setOf("bag_friend_1")

        val pending = PetPureCalculations.filterPendingCoinBags(
            allBags = bags,
            currentUin = "9999",
            todayClaimedBagIds = todayClaimed,
            isManual = false,
            maxFriendBags = 3
        )

        assertEquals(4, pending.size)
        assertEquals("bag_self", pending[0].coinbagId)
        assertEquals("bag_friend_2", pending[1].coinbagId)
        assertEquals("bag_friend_3", pending[2].coinbagId)
        assertEquals("bag_friend_4", pending[3].coinbagId)
    }

    @Test
    fun pkOpponentSelectionPrefersWeakerAndExcludesBlacklist() {
        val myTotal = 1500L
        val blacklist = setOf(2001L)

        assertTrue(PetPureCalculations.canChallengePkOpponent(
            oppTotal = 1200L,
            myTotal = myTotal,
            oppUin = 2002L,
            blacklist = blacklist,
            canPk = true
        ))

        assertFalse(PetPureCalculations.canChallengePkOpponent(
            oppTotal = 1600L,
            myTotal = myTotal,
            oppUin = 2003L,
            blacklist = blacklist,
            canPk = true
        ))

        assertFalse(PetPureCalculations.canChallengePkOpponent(
            oppTotal = 800L,
            myTotal = myTotal,
            oppUin = 2001L,
            blacklist = blacklist,
            canPk = true
        ))
    }

    @Test
    fun filterPendingLikeBackMembersHonorsManualAndAutoMode() {
        val members = listOf(
            QQPetDirectBridge.LikeMember(uin = 101L, nick = "好友A", headerUrl = "", timestamp = 1000L, desc = "踩了你", canLikeBack = true),
            QQPetDirectBridge.LikeMember(uin = 102L, nick = "好友B", headerUrl = "", timestamp = 1001L, desc = "踩了你", canLikeBack = false),
            QQPetDirectBridge.LikeMember(uin = 103L, nick = "好友C", headerUrl = "", timestamp = 1002L, desc = "踩了你", canLikeBack = true)
        )
        val todayLiked = setOf(101L)

        // 自动模式：必须 canLikeBack == true 且今日未踩过，因此只剩 103
        val autoPending = PetPureCalculations.filterPendingLikeBackMembers(
            members = members,
            todayLikedUins = todayLiked,
            isManual = false
        )
        assertEquals(1, autoPending.size)
        assertEquals(103L, autoPending[0].uin)

        // 手动模式：绕过本地 todayLiked 限制，以 canLikeBack 为准强制探测，因此 101 和 103 均入选
        val manualPending = PetPureCalculations.filterPendingLikeBackMembers(
            members = members,
            todayLikedUins = todayLiked,
            isManual = true
        )
        assertEquals(2, manualPending.size)
        assertEquals(101L, manualPending[0].uin)
        assertEquals(103L, manualPending[1].uin)
    }

    @Test
    fun testFriendCareThresholdRules() {
        val curEnergy = 45
        val energyThreshold = 60
        val needFeed = curEnergy in 0 until energyThreshold
        assertTrue(needFeed)

        val highEnergy = 85
        val noFeed = highEnergy in 0 until energyThreshold
        assertFalse(noFeed)

        val curClean = 30
        val cleanThreshold = 60
        val needBath = curClean in 0 until cleanThreshold
        assertTrue(needBath)
    }
}

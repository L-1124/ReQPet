package com.copilot.qqpet.engine

import com.copilot.qqpet.protocol.QQPetDirectBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HireFriendSelectorTest {

    @Test
    fun `parseHireFriendUins filters invalid values and deduplicates`() {
        val parsed = PetAdventureEngine.parseHireFriendUins(" 10001, 20002,abc,-5,10001 , 0 ")
        assertEquals(setOf(10001L, 20002L), parsed)
    }

    @Test
    fun `whitelist only selects ticked idle friends sorted by highest totalAttr`() {
        val f1 = QQPetDirectBridge.HireableFriend(
            uin = 111111L,
            friendNick = "路人甲",
            petNick = "小甲",
            petId = "pet_111",
            power = 900,
            intel = 900,
            charm = 900,
            isIdle = true
        )
        val f2 = QQPetDirectBridge.HireableFriend(
            uin = 222222L,
            friendNick = "抚琴的人",
            petNick = "琴宝",
            petId = "pet_222",
            power = 500,
            intel = 600,
            charm = 700,
            isIdle = true
        )
        val f3 = QQPetDirectBridge.HireableFriend(
            uin = 333333L,
            friendNick = "高战忙碌号",
            petNick = "大忙人",
            petId = "pet_333",
            power = 800,
            intel = 800,
            charm = 800,
            isIdle = false,
            remainingSec = 1800L
        )
        val f4 = QQPetDirectBridge.HireableFriend(
            uin = 444444L,
            friendNick = "低战空闲号",
            petNick = "小闲",
            petId = "pet_444",
            power = 200,
            intel = 200,
            charm = 200,
            isIdle = true
        )

        val allFriends = listOf(f1, f2, f3, f4)
        // 仅勾选 222222、333333、444444（未勾选 111111 路人甲）
        val selectedUins = setOf(222222L, 333333L, 444444L)

        val idleSorted = allFriends
            .filter { it.uin in selectedUins && it.isIdle }
            .sortedByDescending { it.totalAttr }

        // 未勾选的 111111 和忙碌的 333333 必须被排除；空闲中 222222(总资质1800) 排在 444444(总资质600) 前面
        assertEquals(2, idleSorted.size)
        assertEquals(222222L, idleSorted[0].uin)
        assertEquals("抚琴的人", idleSorted[0].friendNick)
        assertEquals(1800L, idleSorted[0].totalAttr)
        assertEquals(444444L, idleSorted[1].uin)
    }

    @Test
    fun `dual mode search matches friendNick petNick and uin`() {
        val friends = listOf(
            QQPetDirectBridge.HireableFriend(2697069238L, "抚琴的人", "星小企", "pid1"),
            QQPetDirectBridge.HireableFriend(1087942084L, "测试小号", "糯米团", "pid2")
        )

        val byName = friends.filter {
            it.friendNick.contains("抚琴", ignoreCase = true) ||
                it.petNick.contains("抚琴", ignoreCase = true) ||
                it.uin.toString().contains("抚琴")
        }
        assertEquals(1, byName.size)
        assertEquals(2697069238L, byName.first().uin)

        val byUin = friends.filter {
            it.friendNick.contains("269706", ignoreCase = true) ||
                it.petNick.contains("269706", ignoreCase = true) ||
                it.uin.toString().contains("269706")
        }
        assertEquals(1, byUin.size)
        assertEquals("抚琴的人", byUin.first().friendNick)

        val byPet = friends.filter {
            it.friendNick.contains("糯米", ignoreCase = true) ||
                it.petNick.contains("糯米", ignoreCase = true) ||
                it.uin.toString().contains("糯米")
        }
        assertTrue(byPet.size == 1 && byPet.first().uin == 1087942084L)
    }
}

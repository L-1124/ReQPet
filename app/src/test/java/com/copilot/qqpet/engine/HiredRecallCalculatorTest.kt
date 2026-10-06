package com.copilot.qqpet.engine

import com.copilot.qqpet.engine.utils.PetPureCalculations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiredRecallCalculatorTest {

    @Test
    fun `calculateHiredProgress accurately calculates elapsed progress percentage`() {
        // 4小时任务 (14400s)，剩余 12806s (走过了 1594s) -> 11.0694%
        val progress1 = PetAdventureEngine.calculateHiredProgress(14400L, 12806L)
        assertEquals(11.0694, progress1, 0.001)

        // 剩余为 0 -> 100%
        val progress2 = PetAdventureEngine.calculateHiredProgress(14400L, 0L)
        assertEquals(100.0, progress2, 0.001)

        // 刚开始剩余等于总长 -> 0%
        val progress3 = PetAdventureEngine.calculateHiredProgress(14400L, 14400L)
        assertEquals(0.0, progress3, 0.001)

        // 非法异常值防护
        val progress4 = PetAdventureEngine.calculateHiredProgress(0L, 0L)
        assertEquals(0.0, progress4, 0.001)
    }

    @Test
    fun `shouldTriggerHiredRecall verifies exact threshold matching`() {
        // 阈值 0 代表关闭：任何进度都不触发
        assertFalse(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 75.0, targetThreshold = 0))
        assertFalse(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 100.0, targetThreshold = 0))

        // 阈值 12%：未到 12% 不触发，达到或超过 12% 触发
        assertFalse(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 11.069, targetThreshold = 12))
        assertFalse(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 11.999, targetThreshold = 12))
        assertTrue(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 12.0, targetThreshold = 12))
        assertTrue(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 15.5, targetThreshold = 12))

        // 阈值 42%：未到 42% 不触发，达到或超过 42% 触发
        assertFalse(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 41.8, targetThreshold = 42))
        assertTrue(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 42.0, targetThreshold = 42))
        assertTrue(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 50.0, targetThreshold = 42))

        // 阈值 72%（最高收益档）：未到 72% 不触发，达到或超过 72% 触发
        assertFalse(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 71.5, targetThreshold = 72))
        assertTrue(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 72.0, targetThreshold = 72))
        assertTrue(PetAdventureEngine.shouldTriggerHiredRecall(currentProgress = 85.0, targetThreshold = 72))
    }

    @Test
    fun `story copy identifies being hired by a friend`() {
        val json = """[{"text":"被"},{"text":"小福猪","font_weight":"2"},{"text":"拉来一起做禁咒实验！"}]"""
        assertEquals("被小福猪拉来一起", PetPureCalculations.hiredByFriendEvidence(json))
        assertEquals("被Tom拉来一起", PetPureCalculations.hiredByFriendEvidence("被Tom拉来一起打工"))
        assertEquals("被🐱拉来一起", PetPureCalculations.hiredByFriendEvidence("被🐱拉来一起"))
        assertEquals(null, PetPureCalculations.hiredByFriendEvidence("现在召回，可获得（金币）1968"))
        assertEquals(null, PetPureCalculations.hiredByFriendEvidence("拉来一起做实验"))
        assertEquals(null, PetPureCalculations.hiredByFriendEvidence("最高额外+42%"))
    }

    @Test
    fun `employed uin matches self only when the pet was hired by a friend`() {
        val selfUin = 972455807L
        val friendUin = 1028645636L
        assertTrue(PetAdventureEngine.isEmployedByFriend(selfUin, selfUin))
        assertFalse(PetAdventureEngine.isEmployedByFriend(friendUin, selfUin))
        assertFalse(PetAdventureEngine.isEmployedByFriend(0L, selfUin))
    }

    @Test
    fun `default hired recall progress is strictly 72 percent for maximum yield`() {
        val defaultProgress = PetAdventureEngine.prefHiredRecallProgress
        assertEquals(72, defaultProgress)

        // 默认 72% 档位下，进度达到 72% 及以上必须被触发
        assertTrue(PetAdventureEngine.shouldTriggerHiredRecall(72.0, defaultProgress))
        assertTrue(PetAdventureEngine.shouldTriggerHiredRecall(75.0, defaultProgress))
        assertFalse(PetAdventureEngine.shouldTriggerHiredRecall(71.9, defaultProgress))
    }

    @Test
    fun `hired sleep duration clamp prevents long sleep when target progress is reached or exceeded`() {
        val total = 14400L
        val rem = 8343L
        val targetProgress = 42

        val targetElapsedSec = (total * targetProgress) / 100L
        val currentElapsedSec = total - rem
        val neededSec = targetElapsedSec - currentElapsedSec

        val safeSleepSec = if (neededSec > 0L) {
            neededSec.coerceIn(15L, 120L)
        } else {
            15L
        }
        assertEquals(15L, safeSleepSec)
    }

    @Test
    fun `isPetAlreadyOutError identifies code 135054 and out-of-home messages`() {
        assertTrue(PetAdventureEngine.isPetAlreadyOutError(135054, "你的宠物已经出门了，稍后再来吧～"))
        assertTrue(PetAdventureEngine.isPetAlreadyOutError(135054, null))
        assertTrue(PetAdventureEngine.isPetAlreadyOutError(0, "宠物已经出门了"))
        assertTrue(PetAdventureEngine.isPetAlreadyOutError(1001, "您的宠物已外出"))
        assertFalse(PetAdventureEngine.isPetAlreadyOutError(0, "成功"))
        assertFalse(PetAdventureEngine.isPetAlreadyOutError(135010, "配置为空"))
    }

    @Test
    fun `resolveEffectiveTotalSec falls back to standard work tiers when total is zero`() {
        // total 正常大于 0 且覆盖 remaining 时，直接返回原 total
        assertEquals(14400L, PetAdventureEngine.resolveEffectiveTotalSec(14400L, 12000L))

        // total 为 0 时，根据 remainingSec 自动自适应匹配最近的标准工时档位 (600s, 2700s, 7200s, 14400s)
        assertEquals(14400L, PetAdventureEngine.resolveEffectiveTotalSec(0L, 10000L))
        assertEquals(7200L, PetAdventureEngine.resolveEffectiveTotalSec(0L, 5000L))
        assertEquals(2700L, PetAdventureEngine.resolveEffectiveTotalSec(0L, 1200L))
        assertEquals(600L, PetAdventureEngine.resolveEffectiveTotalSec(0L, 300L))
        assertEquals(600L, PetAdventureEngine.resolveEffectiveTotalSec(0L, 180L))
        assertEquals(0L, PetAdventureEngine.resolveEffectiveTotalSec(0L, 0L))
    }

    @Test
    fun `calculateHiredRemainingToTarget accurately predicts countdown to target percentage`() {
        val total = 14400L
        // 72% 目标需要走过 10368s
        // 剩余 14000s (走过 400s) -> 还需要 9968s
        val needed1 = PetAdventureEngine.calculateHiredRemainingToTarget(total, 14000L, 72)
        assertEquals(9968L, needed1)

        // 剩余 4032s (恰好走过 10368s) -> 还需要 0s
        val needed2 = PetAdventureEngine.calculateHiredRemainingToTarget(total, 4032L, 72)
        assertEquals(0L, needed2)

        // 剩余 2000s (走过 12400s) -> 已超出目标，返回负数
        val needed3 = PetAdventureEngine.calculateHiredRemainingToTarget(total, 2000L, 72)
        assertTrue(needed3 < 0L)
    }

    @Test
    fun `calculateHiredMonitorSleepMillis waits until the recall threshold`() {
        val sleepFar = StealthScheduler.calculateHiredMonitorSleepMillis(3000L, hasReachedTarget = false)
        assertEquals(3_002_000L, sleepFar)

        val sleepNear = StealthScheduler.calculateHiredMonitorSleepMillis(30L, hasReachedTarget = false)
        assertEquals(32_000L, sleepNear)

        val sleepRetry = StealthScheduler.calculateHiredMonitorSleepMillis(0L, hasReachedTarget = true)
        assertEquals(60_000L, sleepRetry)
    }
}

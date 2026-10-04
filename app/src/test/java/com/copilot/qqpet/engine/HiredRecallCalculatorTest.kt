package com.copilot.qqpet.engine

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
    fun `isHiredTask distinguishes between hired and regular tasks`() {
        // 图2实测真机文案：包含雇佣者、被雇佣者、基础工资、加成
        val hiredStrings = listOf(
            "被雇佣中",
            "刘花",
            "赵海波",
            "75%",
            "25%",
            "当前可获得基础工资249",
            "和额外加成的25%",
            "现在召回，可获得"
        )
        assertTrue(PetAdventureEngine.isHiredTask(hiredStrings))

        // 普通打工文本
        val regularWorkStrings = listOf(
            "风铃旅社",
            "打工进行中",
            "获得打工收益",
            "小宠正在勤劳工作中"
        )
        assertFalse(PetAdventureEngine.isHiredTask(regularWorkStrings))

        // 普通进修文本
        val schoolStrings = listOf(
            "初级学园",
            "武术课程",
            "正在认真听讲中"
        )
        assertFalse(PetAdventureEngine.isHiredTask(schoolStrings))
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
    fun `isTrueHiredWork strictly excludes self-dispatched and regular town work`() {
        val selfStoryId = "6400_self_dispatched_uuid"
        val hiredStoryId = "6400_friend_hired_uuid"

        // 1. 自己派出的打工，即使带有 isHiredFlag，也绝对不能被判定为被雇佣召回
        assertFalse(
            PetAdventureEngine.isTrueHiredWork(
                isHiredFlag = true,
                currentStoryId = selfStoryId,
                selfDispatchedStoryId = selfStoryId,
                rewardTip = "1248"
            )
        )

        // 2. 普通小镇打工（收益为区间浮动，如 68~91），绝不能被判定为被雇佣召回
        assertFalse(
            PetAdventureEngine.isTrueHiredWork(
                isHiredFlag = true,
                currentStoryId = "6400_town_work",
                selfDispatchedStoryId = null,
                rewardTip = "68~91"
            )
        )

        // 3. 真正的被好友雇佣打工（非自主派遣、命中雇佣特征且固定提成奖励）-> 判定为被雇佣
        assertTrue(
            PetAdventureEngine.isTrueHiredWork(
                isHiredFlag = true,
                currentStoryId = hiredStoryId,
                selfDispatchedStoryId = null,
                rewardTip = "1248"
            )
        )
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
}

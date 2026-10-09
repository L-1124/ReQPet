package io.github.reqpet.engine

import io.github.reqpet.protocol.QQPetDirectBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StealthSchedulerTest {

    @Test
    fun testTaskSleepSecondsWhenShortRemaining() {
        // 剩余 60 秒时，拟人休眠开启下应休眠 60 秒 + 随机 5~15 秒 (65~75 秒)
        val sleepSec = StealthScheduler.calculateTaskSleepSeconds(60L, humanLikeEnabled = true)
        assertTrue("Short remaining sleep should be >= 65 but was $sleepSec", sleepSec >= 65L)
        assertTrue("Short remaining sleep should be <= 76 but was $sleepSec", sleepSec <= 76L)
    }

    @Test
    fun testTaskSleepSecondsWhenLongRemaining() {
        // 剩余 3600 秒时，默认开启切片守护，应返回 3~5 分钟 (180~300 秒) 离散切片以保障日常自理
        val sleepSec = StealthScheduler.calculateTaskSleepSeconds(3600L, humanLikeEnabled = true)
        assertTrue("Long remaining sleep should be >= 180 but was $sleepSec", sleepSec >= 180L)
        assertTrue("Long remaining sleep should be <= 300 but was $sleepSec", sleepSec <= 300L)
    }

    @Test
    fun testTaskSleepSecondsWhenEnteringFinalWindow() {
        // 剩余 120 秒时 (已低于 180 秒收尾阈值)，应精准休眠到任务结束并带 10~25 秒 Jitter (130~145 秒)
        val sleepSec = StealthScheduler.calculateTaskSleepSeconds(120L, humanLikeEnabled = true)
        assertTrue("Final window sleep should be >= 130 but was $sleepSec", sleepSec >= 130L)
        assertTrue("Final window sleep should be <= 146 but was $sleepSec", sleepSec <= 146L)
    }

    @Test
    fun testTaskSleepSecondsWhenDisabled() {
        val sleepSec = StealthScheduler.calculateTaskSleepSeconds(3600L, humanLikeEnabled = false)
        assertEquals(60L, sleepSec)
        val short = StealthScheduler.calculateTaskSleepSeconds(20L, humanLikeEnabled = false)
        assertEquals(22L, short)
    }

    @Test
    fun testIdleCycleDelayMillis() {
        // 拟人休眠开启时，空闲轮询间隔落在 60,000 ~ 180,000 ms (1~3 分钟)
        val delayMs = StealthScheduler.calculateIdleCycleDelayMillis(humanLikeEnabled = true)
        assertTrue("Idle delay should be >= 60_000ms but was $delayMs", delayMs >= 60_000L)
        assertTrue("Idle delay should be <= 180_000ms but was $delayMs", delayMs <= 180_000L)
    }

    @Test
    fun testNightSilenceWindow() {
        assertFalse(StealthScheduler.isNightSilentWindow(enabled = false))
        val nightSleepMs = StealthScheduler.calculateNightSleepMillis()
        assertTrue("Night sleep should be > 0 but was $nightSleepMs", nightSleepMs > 0L)
    }

    @Test
    fun testStealthFlags() {
        assertFalse(StealthScheduler.isLogAllowed(debugEnabled = false))
        assertTrue(StealthScheduler.isLogAllowed(debugEnabled = true))
        assertFalse(StealthScheduler.shouldInjectSettingCard(hideSettingEntry = true))
        assertTrue(StealthScheduler.shouldInjectSettingCard(hideSettingEntry = false))
    }

    @Test
    fun testContainsFatigueKeyword() {
        assertTrue(QQPetDirectBridge.containsFatigueKeyword("疲惫，收益减少"))
        assertTrue(QQPetDirectBridge.containsFatigueKeyword("我今天学习/打工太久，要学不进去啦"))
        assertTrue(QQPetDirectBridge.containsFatigueKeyword("我今天学习/打工太久，干不动活啦"))
        assertTrue(QQPetDirectBridge.containsFatigueKeyword("mqqapi://markdown/node?nodeType=petTips&text=%E7%96%B2%E6%83%AB"))
        assertFalse(QQPetDirectBridge.containsFatigueKeyword("魅力+7，正常收益"))
        assertFalse(QQPetDirectBridge.containsFatigueKeyword(null))
    }

    @Test
    fun testClampSleepForPendingTaskNoPendingTask() {
        assertEquals(3_600_000L, StealthScheduler.clampSleepForPendingTask(3_600_000L, 0L))
        assertEquals(3_600_000L, StealthScheduler.clampSleepForPendingTask(3_600_000L, -1L))
    }

    @Test
    fun testClampSleepForPendingTaskClampsToRemainingTime() {
        // 距结束还有 60 秒，静默本想睡 1 小时，但必须截断到 60 秒唤醒
        val now = 1_000_000L
        val endTime = now + 60_000L
        val sleep = StealthScheduler.clampSleepForPendingTask(3_600_000L, endTime, now)
        assertEquals(60_000L, sleep)
    }

    @Test
    fun testClampSleepForPendingTaskEnsuresMinimum3Seconds() {
        // 距结束还有 1 秒，不得返回 1 秒或 0 秒，必须保底 3 秒防频刷
        val now = 1_000_000L
        val endTime = now + 1_000L
        val sleep = StealthScheduler.clampSleepForPendingTask(3_600_000L, endTime, now)
        assertEquals(3_000L, sleep)
    }

    @Test
    fun testClampSleepForPendingTaskHonorsStealthIfShorterThanTask() {
        // 距结束还有 2 小时，熄屏空闲轮询为 60 秒，应保留 60 秒
        val now = 1_000_000L
        val endTime = now + 7_200_000L
        val sleep = StealthScheduler.clampSleepForPendingTask(60_000L, endTime, now)
        assertEquals(60_000L, sleep)
    }

    @Test
    fun testFormatLiveStatusTextPurityDoesNotClearTaskEndTime() {
        PetAdventureEngine.masterEnabled = true
        PetAdventureEngine.currentTaskEndTimeMillis = 1000L // 过去的时间戳，sec <= 0
        PetAdventureEngine.currentTaskTypeName = "进阶修习中"
        val statusText = PetAdventureEngine.formatLiveStatusText()
        assertEquals("任务已修毕 · 正在自动结算收益...", statusText)
        // DEF-12 纯函数验证：formatLiveStatusText 绝不得清除或篡改 currentTaskEndTimeMillis
        assertEquals(1000L, PetAdventureEngine.currentTaskEndTimeMillis)
    }
}

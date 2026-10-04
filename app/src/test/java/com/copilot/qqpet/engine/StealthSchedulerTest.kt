package com.copilot.qqpet.engine

import com.copilot.qqpet.protocol.QQPetDirectBridge
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
        // 剩余 3600 秒时，拟人休眠应精准休眠至任务结束并带 10~35 秒操作延迟 (3610~3635 秒)
        val sleepSec = StealthScheduler.calculateTaskSleepSeconds(3600L, humanLikeEnabled = true)
        assertTrue("Long remaining sleep should be >= 3610 but was $sleepSec", sleepSec >= 3610L)
        assertTrue("Long remaining sleep should be <= 3636 but was $sleepSec", sleepSec <= 3636L)
    }

    @Test
    fun testTaskSleepSecondsWhenDisabled() {
        // 关闭拟人休眠时，采用传统保底
        val sleepSec = StealthScheduler.calculateTaskSleepSeconds(3600L, humanLikeEnabled = false)
        assertEquals(60L, sleepSec)
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
}

package com.copilot.qqpet.engine

import java.util.Calendar
import kotlin.random.Random

/**
 * 抗风控隐身调度器：提供长任务精准休眠、夜间防风控静默、按需延时抖动与隐身开关判定
 */
object StealthScheduler {

    /**
     * 判断当前是否处于夜间防风控静默窗口 (默认 01:30 ~ 06:30)
     */
    fun isNightSilentWindow(enabled: Boolean = true): Boolean {
        if (!enabled) return false
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val currentMinutes = hour * 60 + minute
        return currentMinutes in 90..390
    }

    /**
     * 计算夜间静默休眠毫秒数 (睡到早晨 06:35 ~ 06:55 唤醒)
     */
    fun calculateNightSleepMillis(): Long {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val currentMinutes = hour * 60 + minute
        val wakeTargetMinutes = 390 + Random.nextInt(5, 26)
        val diffMinutes = if (wakeTargetMinutes > currentMinutes) {
            wakeTargetMinutes - currentMinutes
        } else {
            (1440 - currentMinutes) + wakeTargetMinutes
        }
        return diffMinutes * 60 * 1000L
    }

    /**
     * 计算在途任务休眠秒数
     * @param remainingSeconds 任务剩余秒数
     * @param humanLikeEnabled 是否开启自选拟人休眠
     */
    fun calculateTaskSleepSeconds(
        remainingSeconds: Long?,
        humanLikeEnabled: Boolean = true
    ): Long {
        if (remainingSeconds == null || remainingSeconds <= 0) {
            return if (humanLikeEnabled) {
                // 随机 1~3 分钟 (60~180 秒)
                Random.nextLong(60, 181)
            } else {
                30L
            }
        }

        return if (humanLikeEnabled) {
            val clampedRemaining = minOf(remainingSeconds, 15000L)
            if (clampedRemaining <= 60) {
                clampedRemaining + Random.nextLong(5, 16)
            } else {
                clampedRemaining + Random.nextLong(10, 36)
            }
        } else {
            // 关闭拟人休眠时的常规保底
            minOf(remainingSeconds + 2, 60L)
        }
    }

    /**
     * 空闲轮询间隔（毫秒）：随机 1~3 分钟 (60,000 ~ 180,000 ms)
     */
    fun calculateIdleCycleDelayMillis(humanLikeEnabled: Boolean = true): Long {
        return if (humanLikeEnabled) {
            Random.nextLong(60, 181) * 1000L
        } else {
            30 * 1000L
        }
    }

    /**
     * 计算被雇佣监控期间的下一次唤醒毫秒数，防止长任务休眠睡死
     */
    fun calculateHiredMonitorSleepMillis(neededSec: Long, hasReachedTarget: Boolean): Long {
        if (hasReachedTarget || neededSec <= 0L) {
            return Random.nextLong(10, 16) * 1000L
        }
        val safeSleepSec = if (neededSec <= 60L) {
            maxOf(neededSec + Random.nextLong(1, 4), 10L)
        } else {
            minOf(neededSec, Random.nextLong(45, 76))
        }
        return safeSleepSec * 1000L
    }

   fun isLogAllowed(debugEnabled: Boolean): Boolean = debugEnabled

   fun shouldInjectSettingCard(hideSettingEntry: Boolean): Boolean = !hideSettingEntry

    /**
     * 判断设备屏幕是否处于点亮/交互状态 (PowerManager.isInteractive)
     * 锁屏或屏幕熄灭时返回 false，用于熄屏防风控静默判定。
     */
    fun isScreenInteractive(context: android.content.Context): Boolean {
        return try {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isInteractive ?: true
        } catch (_: Throwable) {
            true
        }
    }
}

package com.copilot.qqpet.engine

import java.time.LocalTime
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
        val now = LocalTime.now()
        val currentMinutes = now.hour * 60 + now.minute
        return currentMinutes in 90..390
    }

    /**
     * 计算夜间静默休眠毫秒数 (睡到早晨 06:35 ~ 06:55 唤醒)
     */
    fun calculateNightSleepMillis(): Long {
        val now = LocalTime.now()
        val currentMinutes = now.hour * 60 + now.minute
        val wakeTargetMinutes = 390 + Random.nextInt(5, 26)
        val diffMinutes = if (wakeTargetMinutes > currentMinutes) {
            wakeTargetMinutes - currentMinutes
        } else {
            (1440 - currentMinutes) + wakeTargetMinutes
        }
        return diffMinutes * 60 * 1000L
    }

    const val MIN_SLICE_SECONDS = 180L
    const val MAX_SLICE_SECONDS = 300L
    const val SHORT_TASK_THRESHOLD_SECONDS = 180L

    /**
     * 计算在途任务休眠秒数（支持长任务切片式守护，防止睡死阻断日常自理）
     * @param remainingSeconds 任务总剩余秒数
     * @param humanLikeEnabled 是否开启自选拟人休眠
     * @param enableSlices 是否启用长任务切片守护巡检
     */
    fun calculateTaskSleepSeconds(
        remainingSeconds: Long?,
        humanLikeEnabled: Boolean = true,
        enableSlices: Boolean = true
    ): Long {
        if (remainingSeconds == null || remainingSeconds <= 0) {
            return if (humanLikeEnabled) {
                // 随机 1~3 分钟 (60~180 秒)
                Random.nextLong(60, 181)
            } else {
                30L
            }
        }

        if (!humanLikeEnabled) {
            // 未开拟人：剩余不足 1 分钟就睡到结束，否则最多 1 分钟
            return if (remainingSeconds < 60L) remainingSeconds + 2L else 60L
        }

        // 拟人休眠开启：当长任务剩余时间大于切片阈值时，拆分成 3~5 分钟随机抖动切片守护巡检
        if (enableSlices && remainingSeconds > SHORT_TASK_THRESHOLD_SECONDS) {
            val sliceSec = Random.nextLong(MIN_SLICE_SECONDS, MAX_SLICE_SECONDS + 1)
            return minOf(remainingSeconds, sliceSec)
        }

        // 剩余时间进入收尾期时，休眠到任务到期并带微小 Jitter (5~15 秒)
        return if (remainingSeconds <= 60L) {
            remainingSeconds + Random.nextLong(5, 16)
        } else {
            remainingSeconds + Random.nextLong(10, 26)
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
     * 被雇佣召回：能算出距阈值还有多久，就睡到那个时间点（多留 2 秒，避免卡在阈值前）。
     * 已经到点或召回失败时无法再向前推算，改为 1 分钟后重试。
     */
    fun calculateHiredMonitorSleepMillis(neededSec: Long, hasReachedTarget: Boolean): Long {
        if (!hasReachedTarget && neededSec > 0L) {
            return (neededSec + 2L) * 1000L
        }
        return 60_000L
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
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            true
        }
    }
}

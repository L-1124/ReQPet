package com.copilot.qqpet.engine

import android.content.Context
import com.copilot.qqpet.HookEntry

/**
 * 主循环前置闸门：静默窗口检查与发包桥接就绪判断
 *
 * 返回休眠毫秒（null/true 表示闸门通过），从 PetAdventureEngine 拆出以收敛主循环体积
 */
internal object EngineGates {

    /**
     * 检查夜间/熄屏静默窗口；命中则返回应休眠毫秒，否则 null
     *
     * DEF-11:
     * - 到期放行特权：若本地有在途任务 (taskEndTime > 0) 且当前时间已达到或超过预估结束时间 (now >= taskEndTime)，
     *   说明任务已经到期待结算，返回 null 放行主循环执行本轮结算与自理补状态，完成后再进入静默睡眠。
     * - 闭环唤醒计算：在进入夜间静默或熄屏静默时，休眠时间必须为 minOf(stealthSleep, maxOf(3_000L, taskEndTime - now))，
     *   确保任务到期时能准时醒来，杜绝深度睡眠数小时阻断结算。
     */
    fun checkStealthWindows(
        context: Context,
        taskEndTime: Long = PetAdventureEngine.currentTaskEndTimeMillis,
        now: Long = System.currentTimeMillis()
    ): Long? {
        if (taskEndTime in 1..now) {
            PetAdventureEngine.sendLog("[静默放行] 在途任务已到期，放行主循环执行本轮结算与自理补状态")
            return null
        }
        if (PetAdventureEngine.prefNightSleepMode && StealthScheduler.isNightSilentWindow(true)) {
            val baseSleep = StealthScheduler.calculateNightSleepMillis()
            val s = StealthScheduler.clampSleepForPendingTask(baseSleep, taskEndTime, now)
            val desc = if (s < 3600_000L) "${(s + 59_999L) / 60_000L} 分钟" else "${s / 3600000L} 小时"
            PetAdventureEngine.sendLog("[夜间静默] 深夜防风控窗口中，预计 $desc 后恢复")
            return s
        }
        if (PetAdventureEngine.prefScreenOffSilent && !StealthScheduler.isScreenInteractive(context)) {
            val baseSleep = StealthScheduler.calculateIdleCycleDelayMillis(PetAdventureEngine.prefHumanLikeSleep)
            val s = StealthScheduler.clampSleepForPendingTask(baseSleep, taskEndTime, now)
            PetAdventureEngine.sendLog("[熄屏静默] 屏幕已熄灭，拟人休眠 ${s / 1000L} 秒直至亮屏")
            return s
        }
        return null
    }
}

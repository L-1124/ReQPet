package com.copilot.qqpet.engine

import android.content.Context
import com.copilot.qqpet.HookEntry

/**
 * 主循环前置闸门：静默窗口检查与发包桥接就绪判断
 *
 * 返回休眠毫秒（null/true 表示闸门通过），从 PetAdventureEngine 拆出以收敛主循环体积
 */
internal object EngineGates {

    /** 检查夜间/熄屏静默窗口；命中则返回应休眠毫秒，否则 null */
    fun checkStealthWindows(context: Context): Long? {
        if (PetAdventureEngine.prefNightSleepMode && StealthScheduler.isNightSilentWindow(true)) {
            val s = StealthScheduler.calculateNightSleepMillis()
            PetAdventureEngine.sendLog(context, "[夜间静默] 深夜防风控窗口中，预计 ${s / 3600000L} 小时后恢复")
            return s
        }
        if (PetAdventureEngine.prefScreenOffSilent && !StealthScheduler.isScreenInteractive(context)) {
            val s = StealthScheduler.calculateIdleCycleDelayMillis(PetAdventureEngine.prefHumanLikeSleep)
            PetAdventureEngine.sendLog(context, "[熄屏静默] 屏幕已熄灭，拟人休眠 ${s / 1000L} 秒直至亮屏")
            return s
        }
        return null
    }
}

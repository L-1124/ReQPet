package com.copilot.qqpet.ui.util

import android.content.Context
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine

/** 配置生效中枢：设置页已写入 qqpet_inproc_prefs，这里让引擎重读配置；主循环是否唤醒由调用方决定。 */
object SettingConfigSyncer {

    fun triggerAction(context: Context, engine: PetAdventureEngine?, action: String) {
        val target = engine ?: HookEntry.globalEngine ?: return
        if (HookEntry.globalBridge?.isReady != true) HookEntry.reconnectBridgeIfAvailable(context)
        if (action == "query_work_places" || action == "query_account_status") {
            target.preloadAccountData(context)
        } else {
            target.runAction(context, action)
        }
        target.wakeUpMasterCycle(context)
    }

    fun syncConfig(engine: PetAdventureEngine?, context: Context, wakeCycle: Boolean = false) {
        val target = engine ?: HookEntry.globalEngine ?: return
        if (HookEntry.globalBridge?.isReady != true) HookEntry.reconnectBridgeIfAvailable(context)
        target.reloadConfig(context)
        if (wakeCycle) {
            target.logConfigSummary(context)
            target.wakeUpMasterCycle(context)
        }
    }

    fun onMasterSwitchChanged(engine: PetAdventureEngine?, context: Context) {
        syncConfig(engine, context, wakeCycle = true)
    }
}

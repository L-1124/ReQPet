package io.github.reqpet.ui.util

import android.content.Context
import io.github.reqpet.HookEntry
import io.github.reqpet.engine.PetAdventureEngine

/** 配置生效中枢：设置页已写入 qqpet_inproc_prefs，这里让引擎重读配置；主循环是否唤醒由调用方决定。 */
object SettingConfigSyncer {

    fun triggerAction(context: Context, engine: PetAdventureEngine?, action: String) {
        val appContext = context.applicationContext ?: context
        val target = engine ?: HookEntry.globalEngine ?: return
        if (HookEntry.globalBridge?.isReady != true) HookEntry.reconnectBridgeIfAvailable(appContext)
        if (action == "query_work_places" || action == "query_account_status") {
            target.preloadAccountData(appContext)
        } else {
            target.runAction(appContext, action)
        }
        target.wakeUpMasterCycle(appContext)
    }

    fun syncConfig(engine: PetAdventureEngine?, context: Context, wakeCycle: Boolean = false) {
        val appContext = context.applicationContext ?: context
        val target = engine ?: HookEntry.globalEngine ?: return
        if (HookEntry.globalBridge?.isReady != true) HookEntry.reconnectBridgeIfAvailable(appContext)
        target.reloadConfig(appContext)
        if (wakeCycle) {
            target.logConfigSummary(appContext)
            target.wakeUpMasterCycle(appContext)
        }
    }
}

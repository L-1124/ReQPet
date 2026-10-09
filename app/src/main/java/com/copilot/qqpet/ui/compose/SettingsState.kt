package com.copilot.qqpet.ui.compose

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.copilot.qqpet.ui.SettingsTrace
import kotlinx.coroutines.launch
import com.copilot.qqpet.engine.EngineLog
import com.copilot.qqpet.engine.LogEntry
import com.copilot.qqpet.engine.PetAccountGateway
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.engine.metrics.EngineMetrics
import com.copilot.qqpet.engine.metrics.EngineMetricsImpl
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import com.copilot.qqpet.ui.util.SettingConfigSyncer

/**
 * 设置页唯一状态源：配置读 qqpet_inproc_prefs，运行状态读引擎缓存。
 * 写入落盘后仅重读引擎配置；只有总开关翻转会唤醒/停掉主循环，读侧由 Compose 快照自动跟踪。
 */
@Stable
class SettingsState(
    private val context: Context,
    private val engine: PetAdventureEngine?,
    val previewMode: Boolean = false
) {
    private val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
    private val values: SnapshotStateMap<String, Any?> = mutableStateMapOf()

    var statusText by mutableStateOf("")
        private set
    var hasOngoingTask by mutableStateOf(false)
        private set
    var attributesText by mutableStateOf("")
        private set
    var schoolDetails by mutableStateOf<QQPetDirectBridge.SecondMapDetails?>(null)
        private set
    var workPlaces by mutableStateOf<QQPetDirectBridge.SecondMapDetails?>(null)
        private set
    var workJobs by mutableStateOf<List<QQPetDirectBridge.SelectEvent>?>(null)
        private set
    var hireableFriends by mutableStateOf<List<QQPetDirectBridge.HireableFriend>>(emptyList())
        private set
    val engineMetrics: EngineMetrics? = runCatching {
        engine?.let { EngineMetricsImpl.getInstance(context) }
    }.getOrNull()
    var logLines by mutableStateOf<List<LogEntry>>(emptyList())
        private set
    var pkBlacklistSummary by mutableStateOf("")
        private set

    private val prefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key != null) {
                values[key] = sharedPreferences.all[key]
            } else {
                for ((k, v) in sharedPreferences.all) values[k] = v
            }
        }


    private var lastPkSummaryKey: Pair<String, String>? = null
    private var lastPkSummaryFriends: List<QQPetDirectBridge.HireableFriend>? = null
    private var logCollectJob: kotlinx.coroutines.Job? = null


    init {
        if (previewMode) {
            statusText = "预览 · 自动托管未开启"
            attributesText = "暂无小宠数据"
        } else {
            refresh()
        }
    }

    fun attach(scope: kotlinx.coroutines.CoroutineScope) {
        if (previewMode) return
        SettingsTrace.trace("settings.state_attach") {
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
            for ((k, v) in prefs.all) values[k] = v
            logCollectJob?.cancel()
            logCollectJob = scope.launch {
                EngineLog.logFlow.collect {
                    refreshLogs()
                }
            }
        }
    }

    fun detach() {
        if (previewMode) return
        logCollectJob?.cancel()
        logCollectJob = null
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    fun bool(key: String, def: Boolean = false): Boolean =
        values[key] as? Boolean ?: if (previewMode) def else prefs.getBoolean(key, def)

    fun int(key: String, def: Int = 0): Int =
        values[key] as? Int ?: if (previewMode) def else prefs.getInt(key, def)

    fun string(key: String, def: String = ""): String =
        values[key] as? String ?: if (previewMode) def else prefs.getString(key, def).orEmpty()

    fun setBool(key: String, value: Boolean) {
        if (previewMode) {
            values[key] = value
            return
        }
        prefs.edit().putBoolean(key, value).apply()
        values[key] = value
        syncOrWake(key)
    }

    fun setInt(key: String, value: Int) {
        if (previewMode) {
            values[key] = value
            return
        }
        prefs.edit().putInt(key, value).apply()
        values[key] = value
        syncOrWake(key)
    }

    fun setString(key: String, value: String) {
        if (previewMode) {
            values[key] = value
            return
        }
        prefs.edit().putString(key, value).apply()
        values[key] = value
        syncOrWake(key)
    }

    /** 总开关翻转决定主循环生死，必须唤醒；其余开关引擎每轮巡检前 reloadConfig 即可生效 */
    private fun syncOrWake(key: String) {
        if (key == PreferencesHelper.KEY_MASTER_ENABLED) {
            SettingConfigSyncer.onMasterSwitchChanged(engine, context)
        } else {
            SettingConfigSyncer.syncConfig(engine, context)
        }
    }

    /** 每秒 tick 与页面 resume 都会调用：引擎状态、日志每秒刷新；prefs 仅在外部变化时重读 */
    fun refresh() {
        if (previewMode) return
        SettingsTrace.trace("settings.state_refresh") {
            statusText = PetAdventureEngine.formatLiveStatusText()
            hasOngoingTask = PetAdventureEngine.getLiveRemainingSeconds() > 0L
            val details = PetAdventureEngine.cachedSchoolDetails
            schoolDetails = details
            workPlaces = PetAdventureEngine.cachedWorkPlaces
            workJobs = PetAdventureEngine.cachedWorkJobs
            hireableFriends = PetAdventureEngine.cachedHireableFriends

            val petId = PetAdventureEngine.cachedPetId
            val attrs = if (!petId.isNullOrEmpty()) {
                QQPetDirectBridge.cachedPetAttributes
            } else {
                null
            }
            val attrPrefix = if (details != null && details.code == 0) {
                "力量 ${details.power}  智力 ${details.intel}  魅力 ${details.charm}"
            } else {
                "实时同步官方属性中"
            }
            val liveCare = if (attrs != null && attrs.energy >= 0f) {
                " · 体力 ${attrs.energy.toInt()} 清洁 ${attrs.clean.toInt()}"
            } else {
                ""
            }
            attributesText = "$attrPrefix$liveCare"

            // PK 黑名单仅在名单/好友缓存实际变化时重算（避免每秒读 prefs + CSV 解析）
            val blKey = PetAdventureEngine.currentActiveUin to PetAdventureEngine.prefPkBlacklistUinsCsv
            val friendCacheStamp = PetAdventureEngine.cachedHireableFriends
            if (blKey != lastPkSummaryKey || friendCacheStamp !== lastPkSummaryFriends) {
                lastPkSummaryKey = blKey
                lastPkSummaryFriends = friendCacheStamp
                pkBlacklistSummary = buildPkBlacklistSummary()
            }
            refreshLogs()
        }
    }


    private fun buildPkBlacklistSummary(): String {
        val blacklistUins = PetAccountGateway.loadSavedPkBlacklistUins(context)
        if (blacklistUins.isEmpty()) {
            return "未设置免战名单 (全部碾压对手均可对决 · 点击管理黑名单)"
        }
        val cachedFriends = PetAccountGateway.loadCachedHireableFriends(context)
        val matchedNames = blacklistUins.map { uin ->
            val friend = cachedFriends.find { it.uin == uin }
            if (friend != null && friend.friendNick.isNotBlank()) friend.friendNick else uin.toString()
        }
        val preview = matchedNames.take(3).joinToString("、")
        val more = if (matchedNames.size > 3) " 等" else ""
        return "已拉黑 ${blacklistUins.size} 位对手 ($preview$more) · 自动跳过免战"
    }

    fun refreshLogs() {
        if (previewMode) return
        logLines = EngineLog.snapshot()
    }

    fun clearLogs() {
        if (previewMode) return
        EngineLog.clear()
        refreshLogs()
    }

    /** 让引擎重读配置（不唤醒主循环；总开关翻转走 syncOrWake 专用通道） */
    fun syncConfig() {
        if (previewMode) return
        SettingConfigSyncer.syncConfig(engine, context)
    }

    /** 触发一次具体动作（查询工作地点、账号状态等） */
    fun action(name: String) {
        if (previewMode) return
        SettingConfigSyncer.triggerAction(context, engine, name)
    }
}

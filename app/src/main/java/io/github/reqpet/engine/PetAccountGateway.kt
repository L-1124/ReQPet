package io.github.reqpet.engine

import android.content.Context
import io.github.reqpet.engine.state.RosterStore
import io.github.reqpet.engine.utils.PetPureCalculations
import io.github.reqpet.protocol.QQPetDirectBridge

/**
 * 账号数据网关：名单解析与雇佣好友缓存的统一入口
 *
 * 将 PetAdventureEngine 伴生对象中的 AccountSessionStore 转发收口到一个专职对象，
 * 调用方仍以静态方式访问；当前活跃 UIN 由引擎伴生状态提供
 */
internal object PetAccountGateway {

    private val activeUin: String get() = PetAdventureEngine.currentActiveUin
    private val hireFriendUinsCsv: String get() = PetAdventureEngine.prefHireFriendUinsCsv
    private val pkBlacklistUinsCsv: String get() = PetAdventureEngine.prefPkBlacklistUinsCsv

    fun parseHireFriendUins(csv: String = hireFriendUinsCsv): Set<Long> =
        PetPureCalculations.parseHireFriendUins(csv)

    fun loadSavedHireFriendUins(context: Context): Set<Long> =
        RosterStore.loadSavedHireFriendUins(context, activeUin)

    fun saveHireFriendUins(context: Context, uins: Collection<Long>) =
        RosterStore.saveHireFriendUins(context, activeUin, uins)

    fun parsePkBlacklistUins(csv: String = pkBlacklistUinsCsv): Set<Long> =
        PetPureCalculations.parsePkBlacklistUins(csv)

    fun loadSavedPkBlacklistUins(context: Context): Set<Long> =
        RosterStore.loadSavedPkBlacklistUins(context, activeUin)

    fun savePkBlacklistUins(context: Context, uins: Collection<Long>) =
        RosterStore.savePkBlacklistUins(context, activeUin, uins)

    fun loadCachedHireableFriends(context: Context): List<QQPetDirectBridge.HireableFriend> =
        RosterStore.loadCachedHireableFriends(context, activeUin)
            .also { if (it.isNotEmpty()) PetAdventureEngine.cachedHireableFriends = it }
            .ifEmpty { PetAdventureEngine.cachedHireableFriends }

    fun saveCachedHireableFriends(context: Context, list: List<QQPetDirectBridge.HireableFriend>) {
        PetAdventureEngine.cachedHireableFriends = list
        RosterStore.saveCachedHireableFriends(context, activeUin, list)
    }
}

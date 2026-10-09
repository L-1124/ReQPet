package io.github.reqpet.engine.task

import android.content.Context
import io.github.reqpet.engine.EngineLog
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.engine.RuntimeDiagnostics
import io.github.reqpet.engine.config.TimeConfigManager
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 探险调度：森林探险的发起与回包状态登记
 *
 * 从 PetCycleDispatcher 拆出，降低调度器单文件体积
 */
internal object PetAdventureDispatch {

    suspend fun dispatchAdventure(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        PetAdventureEngine.currentStatusText = "森林探险启程中..."
        PetAdventureEngine.sendLog("[探险启程] 正在前往神秘森林发起探险巡航...")
        val adventureRes: Pair<Int, String?>? = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Pair<Int, String?>> { cont ->
                bridge.startAdventure(petId) { code, storyId, _, _ ->
                    if (cont.isActive) cont.resume(Pair(code, storyId))
                }
            }
        }
        val code = adventureRes?.first ?: -99
        val storyId = adventureRes?.second
        if (code == 0 && !storyId.isNullOrEmpty()) {
            PetAdventureEngine.lastActiveStoryId = storyId
            PetAdventureEngine.currentTaskTypeName = "森林探险中"
            PetAdventureEngine.currentTaskEndTimeMillis =
                System.currentTimeMillis() + TimeConfigManager.getCurrentDuration("ADVENTURE") * 1000L
            PetAdventureEngine.currentStatusText = "正在神秘森林探险寻宝中"
            RuntimeDiagnostics.event(
                "task_started", "transaction" to PetAdventureEngine.currentTransactionId,
                "cycle" to PetAdventureEngine.currentCycleId, "kind" to "adventure",
                "story_id" to RuntimeDiagnostics.id(storyId),
                "task_end_ms" to PetAdventureEngine.currentTaskEndTimeMillis
            )
            PetAdventureEngine.sendLog("[探险成功] 顺利踏入神秘森林！StoryID: $storyId，奇遇宝藏探索中")
            return true
        }
        PetAdventureEngine.sendLog(EngineLog.Level.WARN, "[探险回包] 森林探险启程未生效 (code=$code)")
        return false
    }
}

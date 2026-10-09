package io.github.reqpet.engine

import android.content.Context
import io.github.reqpet.engine.model.StoryStatusResult
import io.github.reqpet.engine.task.PetCareTask
import io.github.reqpet.engine.task.PetSocialTask
import io.github.reqpet.engine.task.PetWorkTask
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 引擎网络门面：全部 Await 调用的统一收口
 *
 * 独立成对象以保持 PetAdventureEngine 聚焦调度循环；bridge 生命周期由调用方传入
 */
internal object PetEngineApi {

    private const val NETWORK_TIMEOUT_MS = 8000L

    suspend fun queryOwnPetAwait(
        bridge: QQPetDirectBridge,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, String?> =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bridge.queryOwnPet { code, petId, _ -> if (cont.isActive) cont.resume(Pair(code, petId)) }
            }
        } ?: Pair(-99, null)

    suspend fun startAdventureAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, String?> =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                bridge.startAdventure(petId) { code, storyId, _, _ ->
                    if (cont.isActive) cont.resume(Pair(code, storyId))
                }
            }
        } ?: Pair(-99, null)

    suspend fun queryPetAttributesAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        isSelf: Boolean = true
    ): QQPetDirectBridge.PetAttributes? =
        PetCareTask.queryPetAttributesAwait(bridge, petId, isSelf)

    suspend fun querySecondMapInfoDetailsAwait(
        bridge: QQPetDirectBridge,
        eventType: Long,
        petId: String
    ): QQPetDirectBridge.SecondMapDetails =
        PetWorkTask.querySecondMapInfoDetailsAwait(bridge, eventType, petId)

    suspend fun querySelectEventsAwait(
        bridge: QQPetDirectBridge,
        page: Long,
        petId: String,
        schoolStage: Int = 0,
        careerType: Int = 0
    ): Pair<Int, List<QQPetDirectBridge.SelectEvent>> =
        PetWorkTask.querySelectEventsAwait(bridge, page, petId, schoolStage, careerType)

    suspend fun fetchAllHireableFriendsAwait(
        bridge: QQPetDirectBridge,
        context: Context,
        currentUin: String,
        enrichSelectedAndTop: Boolean = true
    ): List<QQPetDirectBridge.HireableFriend> =
        PetWorkTask.fetchAllHireableFriendsAwait(context, bridge, currentUin, enrichSelectedAndTop)

    suspend fun enrichFriendDetailsAwait(
        bridge: QQPetDirectBridge,
        friend: QQPetDirectBridge.HireableFriend
    ): QQPetDirectBridge.HireableFriend =
        PetWorkTask.enrichFriendDetailsAwait(bridge, friend)

    suspend fun fetchLikeListAwait(
        bridge: QQPetDirectBridge,
        extra: String = ""
    ): Pair<Int, List<QQPetDirectBridge.LikeMember>> =
        PetSocialTask.fetchLikeListAwait(bridge, extra)
}

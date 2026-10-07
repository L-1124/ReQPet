package com.copilot.qqpet.engine

import com.copilot.qqpet.engine.task.PetWorkTask
import com.copilot.qqpet.protocol.QQPetDirectBridge

/**
 * 学园/职业小镇数据预载：批量拉取第二地图详情与事件列表写入引擎缓存
 *
 * 从 PetAdventureEngine 拆出，专注"预载"这一独立流程
 */
internal object PetPreloader {

    /** 预载 6100 学园与 6400 打工两地图数据；结果同步写入 PetAdventureEngine 缓存字段 */
    suspend fun preloadAccountDataAwait(bridge: QQPetDirectBridge, petId: String) {
        val details = PetEngineApi.querySecondMapInfoDetailsAwait(bridge, 6100L, petId)
            .also { if (it.code == 0) PetAdventureEngine.cachedSchoolDetails = it }
        val targetStage = if (details.currentStage > 0) details.currentStage else 3
        PetEngineApi.querySelectEventsAwait(bridge, 6100L, petId, schoolStage = targetStage)
            .second.let { if (it.isNotEmpty()) PetAdventureEngine.cachedSchoolCourses = it }
        PetEngineApi.querySecondMapInfoDetailsAwait(bridge, 6400L, petId)
            .let { if (it.code == 0) PetAdventureEngine.cachedWorkPlaces = it }
        PetEngineApi.querySelectEventsAwait(bridge, 6400L, petId, careerType = 3)
            .second.let { if (it.isNotEmpty()) PetAdventureEngine.cachedWorkJobs = it }
    }

    /** 从持久层加载雇佣好友缓存并回写引擎伴生状态 */
    suspend fun refreshFriendCacheIfMissing(
        context: android.content.Context,
        bridge: QQPetDirectBridge,
        enrichSelectedAndTop: Boolean
    ) {
        if (PetAccountGateway.loadCachedHireableFriends(context).isEmpty()) {
            PetWorkTask.fetchAllHireableFriendsAwait(
                context,
                bridge,
                PetAdventureEngine.currentActiveUin,
                enrichSelectedAndTop
            )
        }
    }
}

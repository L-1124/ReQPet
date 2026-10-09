package io.github.reqpet.engine

import io.github.reqpet.engine.task.PetWorkTask
import io.github.reqpet.engine.utils.randomJitter
import kotlinx.coroutines.delay
import io.github.reqpet.protocol.QQPetDirectBridge

/**
 * 学园/职业小镇数据预载：批量拉取第二地图详情与事件列表写入引擎缓存
 *
 * 从 PetAdventureEngine 拆出，专注"预载"这一独立流程
 */
internal object PetPreloader {

    private const val TAG = "PetPreloader"

    /** 预载 6100 学园与 6400 打工两地图数据；结果同步写入 PetAdventureEngine 缓存字段 */
    suspend fun preloadAccountDataAwait(bridge: QQPetDirectBridge, petId: String) {
        // 四连查询之间插随机间隔：背靠背固定序列是初始化阶段最显眼的机器指纹
        val details = PetEngineApi.querySecondMapInfoDetailsAwait(bridge, 6100L, petId)
            .also { if (it.code == 0) PetAdventureEngine.cachedSchoolDetails = it }
        delay(randomJitter(200L, 800L))
        val targetStage = if (details.currentStage > 0) details.currentStage else 3
        PetEngineApi.querySelectEventsAwait(bridge, 6100L, petId, schoolStage = targetStage)
            .second.let { if (it.isNotEmpty()) PetAdventureEngine.cachedSchoolCourses = it }
        delay(randomJitter(200L, 800L))
        PetEngineApi.querySecondMapInfoDetailsAwait(bridge, 6400L, petId)
            .let { if (it.code == 0) PetAdventureEngine.cachedWorkPlaces = it }
        delay(randomJitter(200L, 800L))
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

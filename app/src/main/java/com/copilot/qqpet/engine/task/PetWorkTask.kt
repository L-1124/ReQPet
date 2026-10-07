package com.copilot.qqpet.engine.task

import com.copilot.qqpet.engine.TaskLogger
import com.copilot.qqpet.engine.utils.randomJitter
import android.content.Context
import android.content.Intent
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.engine.model.PetFriendsPageResult
import com.copilot.qqpet.engine.model.StoryStatusResult
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.engine.state.RosterStore
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.ProtoWire
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 负责小镇打工调度、工种自适应选择、以及高收益好友雇佣协同开工
 */
object PetWorkTask {

    private const val NETWORK_TIMEOUT_MS = 8000L
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val CANDIDATE_JOBS_CLERK = listOf(
        Triple("星尘魔法塔", 6400L, 6401L),
        Triple("迷雾侦探所", 6400L, 6401L),
        Triple("小镇文职", 6400L, 6401L),
        Triple("小镇文职(10分)", 6400L, 6401L),
        Triple("图书管理(10分)", 6400L, 6401L),
        Triple("图书管理", 6400L, 6401L),
        Triple("文职兼职", 6400L, 6401L),
        Triple("", 6400L, 6401L)
    )
    val CANDIDATE_JOBS_PHYSICAL = listOf(
        Triple("风铃旅社", 6400L, 6501L),
        Triple("咕噜厨房", 6400L, 6501L),
        Triple("竹影武馆", 6400L, 6501L),
        Triple("小镇体力", 6400L, 6501L),
        Triple("小镇体力(10分)", 6400L, 6501L),
        Triple("搬运兼职(45分)", 6400L, 6501L),
        Triple("小镇搬运工(2小时)", 6400L, 6408L),
        Triple("", 6400L, 6501L)
    )
    val CANDIDATE_JOBS_PERFORM = listOf(
        Triple("彩虹画室", 6400L, 6601L),
        Triple("云朵梦舍", 6400L, 6601L),
        Triple("闪耀星屋", 6400L, 6601L),
        Triple("小镇演艺", 6400L, 6601L),
        Triple("小镇演艺(10分)", 6400L, 6601L),
        Triple("戏剧参演(45分)", 6400L, 6601L),
        Triple("舞台助理(2小时)", 6400L, 6601L),
        Triple("", 6400L, 6601L)
    )

    suspend fun startWorkAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        jobName: String = "小镇兼职",
        page: Long = 6400L,
        subEventType: Long = 6401L,
        hiredPetId: String = "",
        hiredUin: Long = 0L,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, String?, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.startWork(
                        petId,
                        jobName,
                        page,
                        subEventType,
                        hiredPetId,
                        hiredUin
                    ) { code, storyId, _, errorMsg ->
                        if (cont.isActive) cont.resume(Triple(code, storyId, errorMsg))
                    }
                }
            } ?: Triple(-99, null, "网络响应超时")
        } catch (t: Throwable) {
            Triple(-99, null, t.message)
        }

    data class WorkStartWithHireResult(
        val code: Int,
        val storyId: String?,
        val errorMsg: String?,
        val hiredFriend: QQPetDirectBridge.HireableFriend?
    )

    suspend fun startWorkWithOptionalHireAwait(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        jobName: String,
        page: Long,
        subEventType: Long,
        hireCandidates: List<QQPetDirectBridge.HireableFriend>,
        enableHireFriend: Boolean,
        onLog: TaskLogger
    ): WorkStartWithHireResult {
        if (enableHireFriend && hireCandidates.isNotEmpty()) {
            for (candidate in hireCandidates) {
                val friendLabel = candidate.friendNick.ifEmpty { candidate.uin.toString() }
                onLog("[打工雇佣] 正在尝试雇佣空闲最高收益好友「$friendLabel」(QQ:${candidate.uin}, 小宠:${candidate.petNick}, 总资质:${candidate.totalAttr})...")
                val (code, storyId, errMsg) = startWorkAwait(
                    bridge = bridge, petId = petId, jobName = jobName, page = page,
                    subEventType = subEventType, hiredPetId = candidate.petId, hiredUin = candidate.uin
                )
                if (code == 0 && !storyId.isNullOrEmpty()) {
                    return WorkStartWithHireResult(code, storyId, errMsg, candidate)
                }
                if (PetPureCalculations.isPetAlreadyOutError(code, errMsg)) {
                    onLog("[雇佣阻断] 开工被拒绝，小宠已在外出 (code=$code ${errMsg ?: "无说明"})，停止后续雇佣和单人打工")
                    return WorkStartWithHireResult(code, storyId, errMsg, null)
                }
                onLog("[雇佣顺延] 雇佣好友「$friendLabel」未能生效 (code=$code ${errMsg ?: ""})，尝试下一候选或回退单人打工...")
                delay(randomJitter(210L, 489L))
            }
        }
        val (soloCode, soloStoryId, soloErr) = startWorkAwait(
            bridge = bridge, petId = petId, jobName = jobName, page = page, subEventType = subEventType, hiredPetId = ""
        )
        return WorkStartWithHireResult(soloCode, soloStoryId, soloErr, null)
    }

    suspend fun selectBestHireCandidatesAwait(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        enableHireFriend: Boolean,
        cachedFriends: List<QQPetDirectBridge.HireableFriend>,
        onLog: TaskLogger
    ): List<QQPetDirectBridge.HireableFriend> {
        if (!enableHireFriend) return emptyList()
        val selectedUins = RosterStore.loadSavedHireFriendUins(context, currentUin)
        if (selectedUins.isEmpty()) {
            onLog("[打工雇佣] 已开启雇佣好友，但当前未勾选好友白名单，本次执行单人打工")
            return emptyList()
        }
        val matched = cachedFriends.filter { it.uin in selectedUins && it.petId.isNotBlank() && it.petId != ownPetId }
        if (matched.isEmpty()) {
            onLog("[打工雇佣] 已勾选 ${selectedUins.size} 位白名单好友，暂未匹配到有效宠物 ID，本次执行单人打工")
            return emptyList()
        }
        val idleCandidates = matched.filter { it.isIdle }.sortedByDescending { it.totalAttr }
        if (idleCandidates.isEmpty()) {
            onLog("[打工雇佣] 已勾选的白名单好友当前均在忙碌中，本次自动转为单人打工")
        } else {
            val best = idleCandidates.first()
            val bestName = best.friendNick.ifEmpty { best.uin.toString() }
            onLog("[雇佣优选] 已锁定空闲且收益资质最高的好友：「$bestName」(总资质:${best.totalAttr})")
        }
        return idleCandidates
    }

    suspend fun querySecondMapInfoDetailsAwait(
        bridge: QQPetDirectBridge, eventType: Long, petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.SecondMapDetails =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.querySecondMapInfoDetails(eventType, petId) { if (cont.isActive) cont.resume(it) }
                }
            } ?: QQPetDirectBridge.SecondMapDetails(-99, 0, 0L, emptyList())
        } catch (_: Throwable) {
            QQPetDirectBridge.SecondMapDetails(-99, 0, 0L, emptyList())
        }

    suspend fun querySelectEventsAwait(
        bridge: QQPetDirectBridge,
        page: Long,
        petId: String,
        schoolStage: Int = 0,
        careerType: Int = 0,
        timeoutMs: Long = NETWORK_TIMEOUT_MS,
        onLog: TaskLogger = TaskLogger { _, _ -> }
    ): Pair<Int, List<QQPetDirectBridge.SelectEvent>> {
        onLog("[岗位查询] 已发出 0x9ab2 page=$page career=$careerType，单独计时 ${timeoutMs / 1000} 秒")
        val done = AtomicBoolean(false)
        val watch = watchScope.launch {
            delay(timeoutMs)
            if (!done.get()) onLog("[岗位查询] ${timeoutMs / 1000} 秒到点仍无 0x9ab2 回包。调用还卡在发包里")
        }
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.querySelectEvents(page, petId, schoolStage, careerType) { code, events, raw, err ->
                        done.set(true)
                        val note = "code=$code 岗位=${events.size} err=${err ?: "无"} bytes=${raw?.size ?: -1} ${
                            ProtoWire.outline(
                                raw,
                                220
                            )
                        }"
                        if (cont.isActive) cont.resume(Pair(code, events) to note)
                    }
                }
            }
            done.set(true)
            if (result == null) {
                onLog("[岗位查询] 等待结束，0x9ab2 没有回包")
                Pair(-99, emptyList())
            } else {
                onLog("[岗位查询] ${result.second}")
                result.first
            }
        } catch (t: Throwable) {
            done.set(true)
            if (t is CancellationException) throw t
            onLog.warn("[岗位查询] 中断 ${t.javaClass.simpleName}: ${t.message ?: "无说明"}")
            Pair(-99, emptyList())
        } finally {
            watch.cancel()
        }
    }

    suspend fun queryStoryStatusAwait(
        bridge: QQPetDirectBridge, petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): StoryStatusResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryStoryStatus(petId) { code, rem, tot, storyId, status, note ->
                        if (cont.isActive) cont.resume(StoryStatusResult(code, rem, tot, storyId, status, note))
                    }
                }
            } ?: StoryStatusResult(-99, null, null, null, bodyNote = "状态查询超时")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            StoryStatusResult(-99, null, null, null, bodyNote = "状态查询异常 ${t.message ?: ""}")
        }

    suspend fun fetchPetFriendsPageAwait(
        bridge: QQPetDirectBridge, cookie: String = "", timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): PetFriendsPageResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchPetFriendsPage(cookie) { code, friends, hasMore, nextCookie, err ->
                        if (cont.isActive) cont.resume(PetFriendsPageResult(code, friends, hasMore, nextCookie, err))
                    }
                }
            } ?: PetFriendsPageResult(-99, emptyList(), false, "", "超时")
        } catch (t: Throwable) {
            PetFriendsPageResult(-99, emptyList(), false, "", t.message)
        }

    suspend fun enrichFriendDetailsAwait(
        bridge: QQPetDirectBridge, friend: QQPetDirectBridge.HireableFriend
    ): QQPetDirectBridge.HireableFriend {
        if (friend.petId.isBlank()) return friend
        val details = querySecondMapInfoDetailsAwait(bridge, 6100L, friend.petId)
        val status = queryStoryStatusAwait(bridge, friend.petId)
        val rem = status.remaining ?: 0L
        val idle = (status.code != 0) || rem <= 0L
        val p = if (details.code == 0 && details.power > 0L) details.power else friend.power
        val i = if (details.code == 0 && details.intel > 0L) details.intel else friend.intel
        val c = if (details.code == 0 && details.charm > 0L) details.charm else friend.charm
        return friend.copy(power = p, intel = i, charm = c, isIdle = idle, remainingSec = if (rem > 0L) rem else 0L)
    }

    suspend fun fetchAllHireableFriendsAwait(
        context: Context, bridge: QQPetDirectBridge, currentUin: String, enrichSelectedAndTop: Boolean = true
    ): List<QQPetDirectBridge.HireableFriend> {
        val existingMap =
            RosterStore.loadCachedHireableFriends(context, currentUin).associateBy { it.uin }.toMutableMap()
        val mergedMap = LinkedHashMap<Long, QQPetDirectBridge.HireableFriend>()
        var cookie = ""
        var pageCount = 0
        while (pageCount < 10) {
            pageCount++
            val page = fetchPetFriendsPageAwait(bridge, cookie)
            if (page.code != 0) break
            for (f in page.friends) {
                if (f.uin <= 0L || f.uin.toString() == currentUin) continue
                val old = existingMap[f.uin]
                mergedMap[f.uin] = old?.let {
                    f.copy(
                        friendNick = f.friendNick.ifEmpty { it.friendNick },
                        petNick = f.petNick.ifEmpty { it.petNick },
                        power = it.power,
                        intel = it.intel,
                        charm = it.charm,
                        isIdle = it.isIdle,
                        remainingSec = it.remainingSec
                    )
                } ?: f
            }
            if (!page.hasMore || page.nextCookie.isEmpty() || page.nextCookie == cookie) break
            cookie = page.nextCookie
            delay(randomJitter(600L, 1400L))
        }
        if (mergedMap.isEmpty() && existingMap.isNotEmpty()) mergedMap.putAll(existingMap)
        val selectedUins = RosterStore.loadSavedHireFriendUins(context, currentUin)
        if (enrichSelectedAndTop && mergedMap.isNotEmpty()) {
            val toEnrich = selectedUins.filter { mergedMap.containsKey(it) }.take(5)
            for (u in toEnrich) {
                mergedMap[u]?.let { mergedMap[u] = enrichFriendDetailsAwait(bridge, it); delay(randomJitter(600L, 1400L)) }
            }
        }
        val sortedList = mergedMap.values.sortedWith(
            compareByDescending<QQPetDirectBridge.HireableFriend> { selectedUins.contains(it.uin) }
                .thenByDescending { it.totalAttr }
                .thenBy { it.uin }
        )
        RosterStore.saveCachedHireableFriends(context, currentUin, sortedList)
        return sortedList
    }

}

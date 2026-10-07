package com.copilot.qqpet.engine.task

import com.copilot.qqpet.engine.TaskLogger
import com.copilot.qqpet.engine.utils.randomJitter
import com.copilot.qqpet.engine.EngineLog
import android.content.Context
import com.copilot.qqpet.engine.ActiveVisitHelper
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.engine.state.RosterStore
import com.copilot.qqpet.engine.resilience.RateLimitExceededException
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责访客回踩、主动串门点赞、自家与好友金币福袋拾取，以及好友宠物代喂代洗
 */
object PetSocialTask {


    private const val NETWORK_TIMEOUT_MS = 8000L

    suspend fun fetchLikeListAwait(
        bridge: QQPetDirectBridge,
        extra: String = ""
    ): Pair<Int, List<QQPetDirectBridge.LikeMember>> =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchLikeList(extra) { code, members, _, _, _, _ ->
                        if (cont.isActive) cont.resume(Pair(code, members))
                    }
                }
            } ?: Pair(-99, emptyList())
        } catch (_: Throwable) {
            Pair(-99, emptyList())
        }

    suspend fun sendLikeAwait(bridge: QQPetDirectBridge, targetUin: Long): Pair<Int, String?> =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.sendLike(targetUin) { code, _, errorMsg ->
                        if (cont.isActive) cont.resume(Pair(code, errorMsg))
                    }
                }
            } ?: Pair(-99, "超时")
        } catch (t: Throwable) {
            Pair(-99, t.message)
        }

    data class LikeBackParams(
        val context: Context,
        val bridge: QQPetDirectBridge,
        val currentUin: String,
        val cachedFriends: List<QQPetDirectBridge.HireableFriend> = emptyList(),
        val isManual: Boolean = false
    )

    suspend fun executeAutoLikeBack(
        params: LikeBackParams,
        onLog: TaskLogger
    ): Int {
        val (context, bridge, currentUin, cachedFriends, isManual) = params
        if (isManual) onLog("[访客回踩] 正在扫描小窝最近来访记录...")
        val (code, members) = fetchLikeListAwait(bridge)
        if (code != 0) {
            if (isManual) onLog.warn("[访客回踩] 拉取来访列表未成功 (code=$code)")
            return 0
        }
        if (members.isEmpty()) {
            if (isManual) onLog("[访客回踩] 最近暂无好友或小伙伴来访记录")
            return 0
        }
        AccountSessionStore.syncTodayLikedUins(context, currentUin)
        val friendUins = cachedFriends.map { it.uin }.filter { it > 0L }.toSet()
        val ownUin = currentUin.toLongOrNull() ?: 0L
        val newStrangers = ActiveVisitHelper.extractStrangersFromVisitors(members.map { it.uin }, ownUin, friendUins)
        if (newStrangers.isNotEmpty()) RosterStore.recordStrangersToPool(context, currentUin, newStrangers)

        val toLike = com.copilot.qqpet.engine.utils.PetPureCalculations.filterPendingLikeBackMembers(
            members = members,
            todayLikedUins = AccountSessionStore.getTodayLikedUins(context, currentUin),
            isManual = isManual,
            limit = 5
        )
        if (toLike.isEmpty()) {
            if (isManual) onLog("[访客回踩] 当前来访的小伙伴今日均已回赠完毕")
            return 0
        }
        return processLikeBackMembers(params, toLike, friendUins, onLog)
    }

    private suspend fun processLikeBackMembers(
        params: LikeBackParams,
        toLike: List<QQPetDirectBridge.LikeMember>,
        friendUins: Set<Long>,
        onLog: TaskLogger
    ): Int {
        val (context, bridge, currentUin, _, isManual) = params
        var successCount = 0
        for (m in toLike) {
            val isFr = friendUins.contains(m.uin)
            val typeDesc = if (isFr) "好友" else "陌生访客"
            val name = if (m.nick.isNotEmpty()) m.nick else "$typeDesc(${m.uin})"
            val (lCode, _) = sendLikeAwait(bridge, m.uin)
            if (lCode == 0 || lCode == 136202) {
                AccountSessionStore.markFriendLikedToday(context, currentUin, m.uin)
                if (lCode == 0) {
                    successCount++
                    onLog("[自动回踩] 成功回赠$typeDesc $name！")
                    delay(randomJitter(1500L, 3500L))
                } else {
                    if (isManual) onLog("[访客回踩] $name 今日已回赠过 (code=136202)")
                    delay(randomJitter(1080L, 2520L))
                }
            } else {
                if (isManual) onLog.warn("[访客回踩] 回赠 $name 失败 (code=$lCode)")
                delay(randomJitter(720L, 1680L))
            }
        }
        if (successCount > 0) {
            onLog("[自动回踩] 本轮来访回赠完成，成功回礼 $successCount 位小伙伴 (好友+陌生人)")
        } else if (isManual) {
            onLog("[访客回踩] 本轮未产生新的回礼记录 (可能对方今日已达上限或已被回赠)")
        }
        return successCount
    }

    suspend fun snatchCoinBagAwait(
        bridge: QQPetDirectBridge,
        ownPetId: String,
        bagId: String
    ): QQPetDirectBridge.SnatchCoinBagResult =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.snatchCoinBag(ownPetId, bagId) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.SnatchCoinBagResult(-99, bagId, 0L, 0, false, "超时")
        } catch (t: Throwable) {
            QQPetDirectBridge.SnatchCoinBagResult(-99, bagId, 0L, 0, false, t.message)
        }

    data class FriendCoinBagsPage(
        val code: Int,
        val bags: List<QQPetDirectBridge.FriendCoinBagInfo>,
        val totalFriends: Int,
        val hasMore: Boolean,
        val nextCookie: String,
        val errorMsg: String?
    )

    suspend fun fetchFriendCoinBagsPageAwait(
        bridge: QQPetDirectBridge,
        cookie: String = ""
    ): FriendCoinBagsPage =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchFriendCoinBags(cookie) { code, bags, count, hasMore, nextCookie, err ->
                        if (cont.isActive) cont.resume(FriendCoinBagsPage(code, bags, count, hasMore, nextCookie, err))
                    }
                }
            } ?: FriendCoinBagsPage(-99, emptyList(), 0, false, "", "超时")
        } catch (t: Throwable) {
            FriendCoinBagsPage(-99, emptyList(), 0, false, "", t.message)
        }

    suspend fun fetchFriendCoinBagsAwait(
        bridge: QQPetDirectBridge,
        cookie: String = ""
    ): Pair<Int, List<QQPetDirectBridge.FriendCoinBagInfo>> {
        val page = fetchFriendCoinBagsPageAwait(bridge, cookie)
        return Pair(page.code, page.bags)
    }

    suspend fun fetchAllFriendCoinBagsAwait(
        bridge: QQPetDirectBridge,
        maxPages: Int = 6
    ): List<QQPetDirectBridge.FriendCoinBagInfo> {
        val discoveredBags = LinkedHashMap<String, QQPetDirectBridge.FriendCoinBagInfo>()
        var cookie = ""
        var pageCount = 0
        while (pageCount < maxPages) {
            pageCount++
            val page = fetchFriendCoinBagsPageAwait(bridge, cookie)
            if (page.code != 0) break
            for (b in page.bags) {
                if (b.coinbagId.isNotEmpty()) {
                    discoveredBags[b.coinbagId] = b
                }
            }
            if (!page.hasMore || page.nextCookie.isEmpty() || page.nextCookie == cookie) {
                break
            }
            cookie = page.nextCookie
            delay(randomJitter(90L, 210L))
        }
        return discoveredBags.values.toList()
    }

    suspend fun refreshOwnCoinBagAwait(bridge: QQPetDirectBridge, ownPetId: String): String? =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchOwnGroundCoinBag(ownPetId) { _, bagId, _ ->
                        if (cont.isActive) cont.resume(bagId)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }

    suspend fun refreshOwnCoinBagFromProfileAwait(_bridge: QQPetDirectBridge): String? =
        QQPetDirectBridge.cachedOwnCoinBagId

    enum class SnatchOutcome { SUCCESS, LIMIT_REACHED, SKIP, FAIL }

    suspend fun executeAutoClaimCoinBags(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        isManual: Boolean,
        onLog: TaskLogger
    ): Int {
        claimGroundCoinBag(context, bridge, ownPetId, currentUin, isManual, onLog)

        // 检查是否已达限额，如果已达限额立即终止
        if (AccountSessionStore.isCoinBagLimitReachedToday(context, currentUin)) {
            onLog.warn("[福袋巡检] 今日领取好友福袋次数已达官方上限，跳过本次扫描")
            return 0
        }

        if (isManual) onLog("[福袋巡检] 正在扫描小窝与好友列表，搜寻可领取的金币福袋...")

        try {
            val allBags = fetchAllFriendCoinBagsAwait(bridge, maxPages = 6)
            val todayClaimed = AccountSessionStore.getTodayClaimedBagIds(context, currentUin)
            val pendingBags = com.copilot.qqpet.engine.utils.PetPureCalculations.filterPendingCoinBags(
                allBags = allBags,
                currentUin = currentUin,
                todayClaimedBagIds = todayClaimed,
                isManual = isManual,
                maxFriendBags = 5
            )
            if (pendingBags.isEmpty()) {
                if (isManual) onLog("[福袋巡检] 当前暂无待领取的金币福袋")
                return 0
            }
            return snatchPendingBags(context, bridge, ownPetId, currentUin, pendingBags, onLog)
        } catch (e: RateLimitExceededException) {
            // 速率限制已处理，不再捕获
            throw e
        } catch (e: Exception) {
            EngineLog.w("PetSocialTask", "福袋领取异常：${e.message}")
            return 0
        }
    }

    private suspend fun snatchPendingBags(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        bags: List<QQPetDirectBridge.FriendCoinBagInfo>,
        onLog: TaskLogger
    ): Int {
        var successCount = 0
        for (bag in bags) {
            val outcome = processSingleCoinBag(context, bridge, ownPetId, currentUin, bag, onLog)
            when (outcome) {
                SnatchOutcome.SUCCESS -> successCount++
                SnatchOutcome.LIMIT_REACHED -> {
                    // 立即终止！不要继续遍历剩余福袋
                    throw RateLimitExceededException("Friend coin bag daily limit reached")
                }

                SnatchOutcome.SKIP, SnatchOutcome.FAIL -> {
                    // 跳过或失败继续下一个
                }
            }
        }
        return successCount
    }

    private suspend fun claimGroundCoinBag(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        isManual: Boolean,
        onLog: TaskLogger
    ) {
        val selfBagId = QQPetDirectBridge.cachedOwnCoinBagId?.trim().orEmpty().ifEmpty {
            refreshOwnCoinBagAwait(bridge, ownPetId)?.trim().orEmpty()
        }
        if (selfBagId.isNotEmpty() && (isManual || !AccountSessionStore.isCoinBagClaimedToday(
                context,
                currentUin,
                selfBagId
            ))
        ) {
            if (isManual) onLog("[自家福袋] 发现小窝地面掉落金币福袋，正在拆领...")
            val res = snatchCoinBagAwait(bridge, ownPetId, selfBagId)
            if (res.code == 0 || res.code in listOf(135091, 135092, 135096)) {
                AccountSessionStore.markCoinBagHandledToday(context, currentUin, selfBagId)
                QQPetDirectBridge.cachedOwnCoinBagId = null
                if (res.code == 0) {
                    val goldStr = if (res.gotGold > 0L) "，斩获 +${res.gotGold} 金币！" else "！"
                    onLog("[自家福袋] 成功拆开地面金币福袋$goldStr")
                }
            } else if (isManual) {
                onLog.warn("[自家福袋] 拆领地面福袋失败 (code=${res.code}, err=${res.errorMsg})")
            }
            delay(randomJitter(900L, 2100L))
        }
    }

    private suspend fun processSingleCoinBag(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        bag: QQPetDirectBridge.FriendCoinBagInfo,
        onLog: TaskLogger
    ): SnatchOutcome {
        val isSelfBag = bag.isSelf || (currentUin.isNotEmpty() && bag.friendUin.toString() == currentUin)
        val tagPrefix = if (isSelfBag) "自家福袋" else "好友福袋"
        val label = if (isSelfBag) "自己小窝" else bag.friendNick.ifEmpty { bag.friendUin.toString() }
        val res = snatchCoinBagAwait(bridge, ownPetId, bag.coinbagId)
        return when (res.code) {
            0 -> {
                AccountSessionStore.markCoinBagHandledToday(context, currentUin, bag.coinbagId)
                if (isSelfBag) QQPetDirectBridge.cachedOwnCoinBagId = null
                val goldStr = if (res.gotGold > 0L) "，斩获 +${res.gotGold} 金币" else ""
                onLog("[$tagPrefix] 成功拆开 $label 的福袋$goldStr！")
                delay(randomJitter(1080L, 2520L))
                SnatchOutcome.SUCCESS
            }

            135098 -> {
                AccountSessionStore.markCoinBagHandledToday(context, currentUin, bag.coinbagId)
                if (!isSelfBag) {
                    // 立即抛出异常终止会话
                    AccountSessionStore.markCoinBagDailyLimitReached(context, currentUin)
                    throw RateLimitExceededException("Friend coin bag daily limit reached (code=135098)")
                } else SnatchOutcome.SKIP
            }

            135091, 135092, 135096 -> {
                AccountSessionStore.markCoinBagHandledToday(context, currentUin, bag.coinbagId)
                if (isSelfBag) QQPetDirectBridge.cachedOwnCoinBagId = null
                delay(randomJitter(480L, 1120L))
                SnatchOutcome.SKIP
            }

            else -> {
                delay(randomJitter(600L, 1400L))
                SnatchOutcome.FAIL
            }
        }
    }

    suspend fun claimSelfCoinBagIfNeeded(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        isManual: Boolean,
        onLog: TaskLogger
    ): Boolean = executeAutoClaimCoinBags(context, bridge, ownPetId, currentUin, isManual, onLog) > 0

    /** 收益结算成功后，若开启了捡福袋，再执行一轮领取。不改变福袋的定时巡检。 */
    suspend fun claimOnceAfterSettle(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        enabled: Boolean,
        onLog: TaskLogger
    ) {
        if (!enabled) return
        onLog("[结算福袋] 收益已结算，按设置再捡一次福袋")
        executeAutoClaimCoinBags(context, bridge, ownPetId, currentUin, false, onLog)
    }
}

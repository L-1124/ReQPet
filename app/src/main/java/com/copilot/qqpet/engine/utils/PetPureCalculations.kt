package com.copilot.qqpet.engine.utils

/**
 * 100% 无副作用的纯计算逻辑，严格保证无 IO、无系统调用与无外部状态依赖
 */
object PetPureCalculations {

    fun calculateHiredProgress(totalSec: Long, remainingSec: Long): Double {
        val effectiveTotal = resolveEffectiveTotalSec(totalSec, remainingSec)
        if (effectiveTotal <= 0L) return 0.0
        val safeRem = remainingSec.coerceIn(0L, effectiveTotal)
        val elapsed = effectiveTotal - safeRem
        return (elapsed.toDouble() / effectiveTotal.toDouble()) * 100.0
    }

    fun resolveEffectiveTotalSec(totalSec: Long, remainingSec: Long): Long {
        if (totalSec > 0L && totalSec >= remainingSec) return totalSec
        if (remainingSec <= 0L) return if (totalSec > 0L) totalSec else 0L
        val standardTiers = listOf(2700L, 7200L, 14400L)
        return standardTiers.firstOrNull { it >= remainingSec } ?: maxOf(remainingSec, 14400L)
    }

    fun calculateHiredRemainingToTarget(totalSec: Long, remainingSec: Long, targetThreshold: Int): Long {
        if (targetThreshold <= 0) return remainingSec
        val effectiveTotal = resolveEffectiveTotalSec(totalSec, remainingSec)
        val targetElapsedSec = (effectiveTotal * targetThreshold) / 100L
        val safeRem = remainingSec.coerceIn(0L, effectiveTotal)
        val currentElapsedSec = effectiveTotal - safeRem
        return targetElapsedSec - currentElapsedSec
    }

    fun shouldTriggerHiredRecall(currentProgress: Double, targetThreshold: Int): Boolean {
        if (targetThreshold <= 0) return false
        return currentProgress >= targetThreshold.toDouble()
    }

    fun isHiredTask(strings: Collection<String>): Boolean {
        val keywords = listOf("被雇佣", "雇佣者", "被雇佣者", "基础工资", "加成奖金", "可获得基础工资")
        return strings.any { s -> keywords.any { k -> s.contains(k) } }
    }

    fun isTrueHiredWork(
        isHiredFlag: Boolean,
        currentStoryId: String?,
        selfDispatchedStoryId: String?,
        rewardTip: String? = null,
        totalSec: Long = 0L
    ): Boolean {
        if (!currentStoryId.isNullOrEmpty() && currentStoryId == selfDispatchedStoryId) {
            return false
        }
        if (!rewardTip.isNullOrEmpty() && rewardTip.contains("~")) {
            return false
        }
        return isHiredFlag
    }

    fun isPetAlreadyOutError(code: Int, errMsg: String?): Boolean {
        if (code == 135054) return true
        if (errMsg != null) {
            if (errMsg.contains("已经出门") || errMsg.contains("已外出") || errMsg.contains("外出") || errMsg.contains("出门") || errMsg.contains("出行中")) return true
        }
        return false
    }

    fun shouldUpdateCachedPetId(cachedPetId: String?, remotePetId: String?): Boolean {
        if (remotePetId.isNullOrBlank()) return false
        val trimmedRemote = remotePetId.trim()
        if (cachedPetId.isNullOrBlank()) return true
        return cachedPetId.trim() != trimmedRemote
    }

    fun isPetInvalidOrMismatchError(code: Int, errMsg: String?): Boolean {
        if (code == 135002 || code == 135075 || code == 135001) return true
        if (!errMsg.isNullOrBlank()) {
            val s = errMsg.lowercase()
            if (errMsg.contains("宠物不存在") || errMsg.contains("未领养") ||
                errMsg.contains("重新领养") || errMsg.contains("未初始化") ||
                errMsg.contains("宠物状态不匹配") || s.contains("pet not exist")
            ) return true
        }
        return false
    }

    fun parseHireFriendUins(csv: String): Set<Long> {
        if (csv.isBlank()) return emptySet()
        return csv.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0L }.toSet()
    }

    fun parsePkBlacklistUins(csv: String): Set<Long> {
        if (csv.isBlank()) return emptySet()
        return csv.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0L }.toSet()
    }

    fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "${mins}分${secs}秒"
    }

    fun calculateFeedingRounds(
        currentEnergy: Int,
        targetThreshold: Int,
        energyPerFeed: Int = 20,
        maxRoundsPerSession: Int = 5
    ): Int {
        if (currentEnergy >= targetThreshold || energyPerFeed <= 0) return 0
        val deficit = targetThreshold - currentEnergy
        val needed = (deficit + energyPerFeed - 1) / energyPerFeed
        return minOf(needed, maxRoundsPerSession)
    }

    fun calculateFeedingRounds(
        currentEnergy: Int,
        targetThreshold: Int,
        dailyRemainFeeds: Int,
        energyPerFeed: Int = 20,
        maxRoundsPerSession: Int = 5
    ): Int = calculateFeedingRounds(currentEnergy, targetThreshold, energyPerFeed, maxRoundsPerSession)

    fun filterPendingCoinBags(
        allBags: List<com.copilot.qqpet.protocol.QQPetDirectBridge.FriendCoinBagInfo>,
        currentUin: String,
        todayClaimedBagIds: Set<String>,
        isManual: Boolean,
        maxFriendBags: Int = 5
    ): List<com.copilot.qqpet.protocol.QQPetDirectBridge.FriendCoinBagInfo> {
        val unclaimed = if (isManual) allBags else allBags.filter { !todayClaimedBagIds.contains(it.coinbagId) }
        val (selfBags, friendBags) = unclaimed.partition {
            it.isSelf || (currentUin.isNotEmpty() && it.friendUin.toString() == currentUin)
        }
        val cappedFriends = if (isManual) friendBags else friendBags.take(maxFriendBags)
        return selfBags + cappedFriends
    }

    fun canChallengePkOpponent(
        oppTotal: Long,
        myTotal: Long,
        oppUin: Long,
        blacklist: Set<Long>,
        canPk: Boolean
    ): Boolean {
        if (!canPk) return false
        if (oppUin > 0L && blacklist.contains(oppUin)) return false
        return oppTotal <= myTotal
    }

    fun filterPendingLikeBackMembers(
        members: List<com.copilot.qqpet.protocol.QQPetDirectBridge.LikeMember>,
        todayLikedUins: Set<Long>,
        isManual: Boolean,
        limit: Int = 5
    ): List<com.copilot.qqpet.protocol.QQPetDirectBridge.LikeMember> {
        val candidates = if (isManual) {
            val likeable = members.filter { it.canLikeBack }
            if (likeable.isNotEmpty()) likeable else members
        } else {
            members.filter { it.canLikeBack && !todayLikedUins.contains(it.uin) }
        }
        return candidates.take(limit)
    }

}

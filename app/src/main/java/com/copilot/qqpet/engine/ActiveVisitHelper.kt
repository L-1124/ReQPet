package com.copilot.qqpet.engine

import java.util.Random

/**
 * 主动串门与随机陌生人池算法工具类
 */
object ActiveVisitHelper {

    data class VisitTarget(
        val uin: Long,
        val isFriend: Boolean
    )

    /**
     * 从访客记录中过滤出纯陌生人（排除自身、非正数 UIN 与双向好友）
     */
    fun extractStrangersFromVisitors(
        visitorUins: Collection<Long>,
        ownUin: Long,
        friendUins: Set<Long>
    ): Set<Long> {
        return visitorUins
            .filter { it > 0L && it != ownUin && !friendUins.contains(it) }
            .toSet()
    }

    /**
     * 从陌生人蓄水池中扣除今日已踩过的账号，并在剩余配额内执行随机洗牌采样
     */
    fun sampleRandomStrangers(
        pool: Set<Long>,
        todayLikedUins: Set<Long>,
        quota: Int,
        random: Random = Random()
    ): List<Long> {
        if (quota <= 0) return emptyList()
        val available = pool.filter { !todayLikedUins.contains(it) }
        if (available.isEmpty()) return emptyList()
        return available.shuffled(random).take(quota)
    }

    /**
     * 综合解析当天的主动串门目标清单（合并好友与随机陌生人，严格遵守单日安全上限与防重）
     */
    fun resolveActiveVisitTargets(
        friendUins: List<Long>,
        strangerPool: Set<Long>,
        enableFriends: Boolean,
        enableStrangers: Boolean,
        todayLikedUins: Set<Long>,
        maxDailyLimit: Int,
        random: Random = Random()
    ): List<VisitTarget> {
        if (maxDailyLimit <= 0) return emptyList()
        val result = mutableListOf<VisitTarget>()
        val seen = mutableSetOf<Long>()

        // 1. 若开启好友主动踩，优先加入未踩好友
        if (enableFriends) {
            val eligibleFriends = friendUins.filter { it > 0L && !todayLikedUins.contains(it) }
            for (f in eligibleFriends) {
                if (result.size >= maxDailyLimit) break
                if (seen.add(f)) {
                    result.add(VisitTarget(uin = f, isFriend = true))
                }
            }
        }

        // 2. 若开启随机陌生人踩，在剩余配额内随机抽样补充
        if (enableStrangers) {
            val remainingQuota = maxDailyLimit - result.size
            if (remainingQuota > 0) {
                val availableStrangers = strangerPool.filter { it > 0L && !todayLikedUins.contains(it) && !seen.contains(it) }
                val sampled = availableStrangers.shuffled(random).take(remainingQuota)
                for (s in sampled) {
                    if (seen.add(s)) {
                        result.add(VisitTarget(uin = s, isFriend = false))
                    }
                }
            }
        }

        return result
    }
}

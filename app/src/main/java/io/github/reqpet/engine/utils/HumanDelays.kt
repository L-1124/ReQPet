package io.github.reqpet.engine.utils

import kotlin.random.Random

/**
 * 拟人延时抖动：固定间隔是服务端时序聚类的机器行为特征，长尾随机化打散指纹
 */
fun randomJitter(minMs: Long, maxMs: Long): Long =
    if (maxMs <= minMs) minMs else Random.nextLong(minMs, maxMs + 1)

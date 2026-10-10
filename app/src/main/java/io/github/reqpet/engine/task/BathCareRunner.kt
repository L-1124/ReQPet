package io.github.reqpet.engine.task

import io.github.reqpet.engine.TaskLogger
import io.github.reqpet.engine.utils.randomJitter
import io.github.reqpet.protocol.QQPetDirectBridge
import io.github.reqpet.protocol.PurchaseGuard
import kotlinx.coroutines.delay

internal interface BathOperations {
    suspend fun configs(): Pair<Int, List<QQPetDirectBridge.BathItemConfig>>
    suspend fun inventory(): Pair<Int, Map<String, Int>>
    suspend fun buy(itemId: String, count: Int): Triple<Int, Int, String?>
    suspend fun bath(itemId: String): QQPetDirectBridge.BathResult
    suspend fun pause() { delay(randomJitter(720L, 1680L)) }
}

internal class BathCareRunner(private val operations: BathOperations, private val onLog: TaskLogger) {
    suspend fun run(startClean: Int, maxClean: Int, targetThreshold: Int): QQPetDirectBridge.BathResult {
        var clean = startClean
        var balance = -1
        var added = 0
        fun failure(code: Int, message: String?) =
            QQPetDirectBridge.BathResult(code, clean, added, balance, false, message)

        if (startClean < 0 || maxClean <= 0 || startClean > maxClean || targetThreshold < 0) {
            return failure(-104, "清洁度或阈值未确认，暂停照料")
        }
        val threshold = targetThreshold.coerceAtMost(maxClean)
        if (clean >= threshold) return QQPetDirectBridge.BathResult(0, clean, 0, -1, clean >= maxClean)
        val (configCode, configs) = operations.configs()
        if (configCode != 0) return failure(configCode, "洗护配置查询失败，暂停照料")
        val (inventoryCode, inventory) = operations.inventory()
        if (inventoryCode != 0) return failure(inventoryCode, "洗护库存未确认，暂停照料")
        val valid = configs.filter { it.itemId.toLongOrNull()?.let { id -> id > 0L } == true && it.cleanValue > 0 }
        val item = valid.firstOrNull { (inventory[it.itemId] ?: 0) > 0 } ?: valid.firstOrNull()
            ?: return failure(-104, "没有有效洗护配置，暂停照料")
        balance = inventory[item.itemId] ?: 0
        if (balance < 0) return failure(-104, "洗护库存无效，暂停照料")
        var purchased = false
        repeat(12) {
            if (balance == 0) {
                if (purchased) return failure(-104, "本轮已补购一次，暂停继续消耗")
                val gap = threshold.toLong() - clean
                val count = ((gap + item.cleanValue - 1L) / item.cleanValue)
                    .coerceIn(1L, PurchaseGuard.MAX_PER_PURCHASE.toLong()).toInt()
                onLog("[洗护采购] 库存已确认不足，按清洁缺口采购 $count 份${item.name}")
                val (code, order, error) = operations.buy(item.itemId, count)
                if (code != 0 || order != 1) return failure(
                    if (code != 0) code else -104, error ?: "洗护订单未确认成功 (result=$order)"
                )
                purchased = true
                operations.pause()
                val (refreshCode, refreshed) = operations.inventory()
                if (refreshCode != 0) return failure(refreshCode, "采购后库存未确认，暂停照料")
                balance = refreshed[item.itemId] ?: 0
                if (balance < count) return failure(-104, "采购入库尚未确认，暂停照料")
            }
            val result = operations.bath(item.itemId)
            if (result.code != 0) return failure(result.code, result.errorMsg)
            val next = if (result.isFullClean) maxClean else result.newClean
            if (next <= clean || next > maxClean || result.remainBalance !in 0 until balance) {
                return failure(-104, "清洁度无进展或库存变化异常，暂停照料")
            }
            added += next - clean
            clean = next
            balance = result.remainBalance
            onLog("[搓澡进度] 清洁度 $clean/$maxClean，剩余${item.name} $balance 份")
            if (clean >= threshold) return QQPetDirectBridge.BathResult(0, clean, added, balance, clean >= maxClean)
            operations.pause()
        }
        return failure(-104, "达到洗澡步数上限，尚未达到清洁阈值")
    }
}

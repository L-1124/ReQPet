package io.github.reqpet.protocol.utils

import io.github.reqpet.engine.EngineLog
import io.github.reqpet.protocol.QQPetDirectBridge
import io.github.reqpet.protocol.QQPetDirectBridge.PetAttributes

/**
 * 文本消耗解析与疲惫标识匹配器
 */
object PetCostAttributeParser {
    private const val TAG = "PetCostAttributeParser"
    private val ENERGY_COST_REGEX = Regex("""体力\d*\(当前(\d+)\)""")
    private val CLEAN_COST_REGEX = Regex("""清洁\d*\(当前(\d+)\)""")

    fun parseCurrentAttrsFromCost(costText: String?): Pair<Float?, Float?> {
        if (costText.isNullOrBlank()) return Pair(null, null)
        val energyMatch = ENERGY_COST_REGEX.find(costText)
        val cleanMatch = CLEAN_COST_REGEX.find(costText)
        val energy = energyMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val clean = cleanMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
        return Pair(energy, clean)
    }

    fun updateCachedAttributesFromCost(costText: String?): PetAttributes? {
        val (curEnergy, curClean) = parseCurrentAttrsFromCost(costText)
        if (curEnergy != null || curClean != null) {
            val old = QQPetDirectBridge.cachedPetAttributes
            val newEnergy = curEnergy ?: old?.energy ?: 0f
            val newClean = curClean ?: old?.clean ?: 0f
            val maxEnergy = old?.maxEnergy ?: 100f
            val maxClean = old?.maxClean ?: 100f
            val mood = old?.mood ?: 100f
            val attrs = PetAttributes(newEnergy, maxEnergy, newClean, maxClean, mood)
            QQPetDirectBridge.cachedPetAttributes = attrs
            EngineLog.d(
                "PetCostAttributeParser",
                "从 cost 同步三围: 体力=$newEnergy/$maxEnergy, 清洁=$newClean/$maxClean"
            )
            return attrs
        }
        return null
    }

    fun containsFatigueKeyword(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.contains("疲惫") ||
                text.contains("收益减少") ||
                text.contains("收益降低") ||
                text.contains("干不动") ||
                text.contains("学不进去") ||
                text.contains("%E7%96%B2%E6%83%AB", ignoreCase = true)
    }
}

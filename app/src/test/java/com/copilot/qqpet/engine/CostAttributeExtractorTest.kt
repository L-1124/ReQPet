package com.copilot.qqpet.engine

import com.copilot.qqpet.protocol.QQPetDirectBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CostAttributeExtractorTest {

    @Test
    fun parseCurrentAttrsFromCostExtractsEnergyAndCleanCorrectly() {
        // 1. 标准学园课程带图片与金币的格式
        val schoolCost = "![#20px #20px](https://qqpet.gtimg.com/pet-outdoor/result/icon/1776409721409.png) 22(当前![#20px #20px](https://qqpet.gtimg.com/pet-outdoor/result/icon/1776409721409.png)68884)，体力5(当前56)，清洁2(当前65)"
        val (energy1, clean1) = QQPetDirectBridge.parseCurrentAttrsFromCost(schoolCost)
        assertEquals(56f, energy1)
        assertEquals(65f, clean1)

        // 2. 满值小镇岗位格式
        val workCostFull = "体力25(当前100)，清洁8(当前100)"
        val (energy2, clean2) = QQPetDirectBridge.parseCurrentAttrsFromCost(workCostFull)
        assertEquals(100f, energy2)
        assertEquals(100f, clean2)

        // 3. 部分缺失或异常格式
        val partialCost = "体力15(当前39)"
        val (energy3, clean3) = QQPetDirectBridge.parseCurrentAttrsFromCost(partialCost)
        assertEquals(39f, energy3)
        assertNull(clean3)

        // 4. 空字符串或无关文本
        val (energy4, clean4) = QQPetDirectBridge.parseCurrentAttrsFromCost("")
        assertNull(energy4)
        assertNull(clean4)

        val (energy5, clean5) = QQPetDirectBridge.parseCurrentAttrsFromCost(null)
        assertNull(energy5)
        assertNull(clean5)
    }
}

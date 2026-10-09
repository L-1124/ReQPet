package io.github.reqpet.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 模拟 QQ 宿主混淆类体系
open class FakeSettingBaseContract
class FakeLegitItemProcessor : FakeSettingBaseContract()
class FakePoisonProcessor // 没有继承 FakeSettingBaseContract

class FakeSettingGroup(val items: List<Any>)

class SettingContractVerificationTest {

    @Test
    fun testDetectContractClassFromLegitItems() {
        val sampleItems = listOf(FakeLegitItemProcessor())
        val contractCls = QQSettingInjector.detectContractClass(sampleItems)
        assertNotNull(contractCls)
        assertEquals(FakeSettingBaseContract::class.java, contractCls)
    }

    @Test
    fun testRejectPoisonClassNotAssignToContract() {
        val sampleGroup = FakeSettingGroup(listOf(FakeLegitItemProcessor()))
        val (contractCls, candidates) = QQSettingInjector.resolveContractAndCandidates(
            sampleGroup,
            this.javaClass.classLoader!!
        )

        assertNotNull(contractCls)
        assertTrue(candidates.contains(FakeLegitItemProcessor::class.java))
        // 毒丸类未继承基类，绝不能进入候选集
        assertFalse(candidates.contains(FakePoisonProcessor::class.java))
    }
}

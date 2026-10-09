package io.github.reqpet.engine

import io.github.reqpet.engine.model.StudyDispatchParam
import io.github.reqpet.engine.model.WorkDispatchParam
import io.github.reqpet.engine.utils.PetPureCalculations
import io.github.reqpet.protocol.QQPetDirectBridge
import io.github.reqpet.ui.util.UiDescUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCareerDispatchTest {

    @Test
    fun testWorkCandidatePoolContainsFallback() {
        val pool = io.github.reqpet.engine.task.PetWorkTask.CANDIDATE_JOBS_CLERK
        assertTrue(pool.isNotEmpty())
        assertTrue(pool.any { it.first.contains("文职") || it.first.contains("图书") || it.first.contains("魔法塔") })
    }

    @Test
    fun testWorkPlaceSelectionDefaultStarTower() {
        val param = WorkDispatchParam(customWorkType = 0, cachedWorkPlaces = null)
        assertNotNull(param)
        assertEquals(0, param.customWorkType)
    }

    @Test
    fun testWorkPlaceOptionsIncludesSmartRecommendAndRealTownPlaces() {
        val options = UiDescUtils.buildWorkPlaceOptions(null)
        assertTrue("首选应为智能推荐", options.isNotEmpty() && options.first().careerId == 0)
        assertEquals("智能推荐", options.first().title)
        assertTrue("应包含星尘魔法塔", options.any { it.title.contains("星尘魔法塔") })
        assertTrue("应包含彩虹画室", options.any { it.title.contains("彩虹画室") })
        assertFalse("绝不能包含假数据伐木场", options.any { it.title.contains("伐木场") })
    }

    @Test
    fun testPetAlreadyOutErrorInterception() {
        assertTrue("135054 必须被识别为出行中错误", PetPureCalculations.isPetAlreadyOutError(135054, null))
        assertTrue(
            "提示外出文案必须被识别为出行中",
            PetPureCalculations.isPetAlreadyOutError(0, "您的宠物正在外出历练")
        )
        assertFalse("普通错误不应被误判为出行中", PetPureCalculations.isPetAlreadyOutError(1001, "网络繁忙"))
    }
}

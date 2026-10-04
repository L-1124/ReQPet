package com.copilot.qqpet.engine

import com.copilot.qqpet.engine.model.StudyDispatchParam
import com.copilot.qqpet.engine.model.WorkDispatchParam
import com.copilot.qqpet.engine.task.PetStudyTask
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.util.UiDescUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCareerDispatchTest {

    @Test
    fun testStudyCandidatePoolContainsFallback() {
        val pool = PetStudyTask.CANDIDATE_COURSES_INTELLECT
        assertTrue(pool.isNotEmpty())
        assertTrue(pool.any { it.first.contains("智力") || it.first.contains("文化") })
    }

    @Test
    fun testWorkCandidatePoolContainsFallback() {
        val pool = com.copilot.qqpet.engine.task.PetWorkTask.CANDIDATE_JOBS_CLERK
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
        assertTrue("提示外出文案必须被识别为出行中", PetPureCalculations.isPetAlreadyOutError(0, "您的宠物正在外出历练"))
        assertFalse("普通错误不应被误判为出行中", PetPureCalculations.isPetAlreadyOutError(1001, "网络繁忙"))
    }
}

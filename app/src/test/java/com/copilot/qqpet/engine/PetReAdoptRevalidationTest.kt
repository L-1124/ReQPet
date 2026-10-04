package com.copilot.qqpet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetReAdoptRevalidationTest {

    // 旧报废宠物 ID (910298997-4-2-1785238894469 的 Base64)
    private val oldPetId = "OTEwMjk4OTk3LTQtMi0xNzg1MjM4ODk0NDY5"
    // 重新领养后的新宠物 ID (910298997-4-3-1785999999999 的 Base64)
    private val newPetId = "OTEwMjk4OTk3LTQtMy0xNzg1OTk5OTk5OTk5"

    @Test
    fun shouldUpdateCachedPetIdDetectsNewPetAdoptionForSameUin() {
        // 1. 同一个 QQ 号重新领养宠物后，虽然归属人相同，但 ID 变更，必须判定需要更新
        assertTrue(PetAdventureEngine.shouldUpdateCachedPetId(oldPetId, newPetId))

        // 2. 本地无缓存，远端拉到有效宠物 ID，必须更新
        assertTrue(PetAdventureEngine.shouldUpdateCachedPetId(null, newPetId))
        assertTrue(PetAdventureEngine.shouldUpdateCachedPetId("", newPetId))

        // 3. 远端与本地一致，无需更新
        assertFalse(PetAdventureEngine.shouldUpdateCachedPetId(oldPetId, oldPetId))
        assertFalse(PetAdventureEngine.shouldUpdateCachedPetId(newPetId, newPetId))

        // 4. 远端拉取失败（返回空或 blank），严禁误清现有本地有效缓存
        assertFalse(PetAdventureEngine.shouldUpdateCachedPetId(oldPetId, null))
        assertFalse(PetAdventureEngine.shouldUpdateCachedPetId(oldPetId, ""))
        assertFalse(PetAdventureEngine.shouldUpdateCachedPetId(oldPetId, "   "))
    }

    @Test
    fun isPetInvalidOrMismatchErrorIdentifies135002And135075AsRecoveryTriggers() {
        // 135002: 宠物不存在 / 未初始化（重新领养后旧宠物报废常见错误码）
        assertTrue(PetAdventureEngine.isPetInvalidOrMismatchError(135002, "宠物不存在"))
        assertTrue(PetAdventureEngine.isPetInvalidOrMismatchError(135002, null))

        // 135075: 宠物数据不属于当前账号或状态失效
        assertTrue(PetAdventureEngine.isPetInvalidOrMismatchError(135075, "宠物状态不匹配"))
        assertTrue(PetAdventureEngine.isPetInvalidOrMismatchError(135075, null))

        // 135001: 宠物数据异常
        assertTrue(PetAdventureEngine.isPetInvalidOrMismatchError(135001, null))

        // 文本特征匹配
        assertTrue(PetAdventureEngine.isPetInvalidOrMismatchError(0, "您的宠物不存在，请先领养"))
        assertTrue(PetAdventureEngine.isPetInvalidOrMismatchError(1001, "请重新领养小宠"))
        assertTrue(PetAdventureEngine.isPetInvalidOrMismatchError(1002, "未初始化宠物档案"))

        // 正常或普通业务状态码不应误触发自愈
        assertFalse(PetAdventureEngine.isPetInvalidOrMismatchError(0, "成功"))
        assertFalse(PetAdventureEngine.isPetInvalidOrMismatchError(135054, "宠物已经出门了"))
        assertFalse(PetAdventureEngine.isPetInvalidOrMismatchError(135004, "任务进行中尚未到达结算时间"))
        assertFalse(PetAdventureEngine.isPetInvalidOrMismatchError(135010, "配置为空"))
    }
}

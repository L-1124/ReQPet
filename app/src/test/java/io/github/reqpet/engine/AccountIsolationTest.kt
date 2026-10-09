package io.github.reqpet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIsolationTest {

    // 真实报错日志中的大号 petId (Base64 解码为 910298997-4-2-1785238894469)
    private val mainAccountPetId = "OTEwMjk4OTk3LTQtMi0xNzg1MjM4ODk0NDY5"
    private val mainAccountUin = "910298997"

    // 小号 UIN 与模拟小号 petId (Base64 解码为 813380203-1-1-1785999999999)
    private val altAccountUin = "813380203"
    private val altAccountPetId = "ODEzMzgwMjAzLTEtMS0xNzg1OTk5OTk5OTk5"

    @Test
    fun extractsOwnerUinFromBase64PetId() {
        assertEquals(mainAccountUin, AccountSessionGuard.extractOwnerUinFromPetId(mainAccountPetId))
        assertEquals(altAccountUin, AccountSessionGuard.extractOwnerUinFromPetId(altAccountPetId))
        assertEquals("", AccountSessionGuard.extractOwnerUinFromPetId(null))
        assertEquals("", AccountSessionGuard.extractOwnerUinFromPetId("invalid-base64"))
    }

    @Test
    fun detectsMismatchWhenSwitchingFromMainToAltAccount() {
        // 切换到小号 813380203 时，大号 petId 必须判定为不匹配
        assertFalse(AccountSessionGuard.isPetIdBelongingToUin(mainAccountPetId, altAccountUin))
        // 大号自身重登 910298997 时，必须判定为匹配
        assertTrue(AccountSessionGuard.isPetIdBelongingToUin(mainAccountPetId, mainAccountUin))
        // 过渡态 runtime 尚未拿到 UIN ("" 或 "0") 时，保留原缓存不误杀
        assertTrue(AccountSessionGuard.isPetIdBelongingToUin(mainAccountPetId, ""))
        assertTrue(AccountSessionGuard.isPetIdBelongingToUin(mainAccountPetId, "0"))
    }

    @Test
    fun resolvesScopedPetIdWithoutCrossAccountPollution() {
        val store = mutableMapOf(
            "key_cached_pet_id" to mainAccountPetId
        )
        // 1. 大号首次同步：自动将旧版 key_cached_pet_id 迁移到 key_cached_pet_id_910298997 并保留
        val resolvedMain = AccountSessionGuard.resolveActivePetId(
            currentRuntimeUin = mainAccountUin,
            memoryPetId = mainAccountPetId,
            scopedSavedPetId = store[AccountSessionGuard.scopedKey("key_cached_pet_id", mainAccountUin)],
            legacySavedPetId = store["key_cached_pet_id"]
        )
        assertEquals(mainAccountPetId, resolvedMain)

        // 2. 切换到小号 813380203：内存中虽然残留大号 mainAccountPetId、旧表也有 mainAccountPetId，但小号必须返回 null 触发重新拉取
        val resolvedAlt = AccountSessionGuard.resolveActivePetId(
            currentRuntimeUin = altAccountUin,
            memoryPetId = mainAccountPetId,
            scopedSavedPetId = store[AccountSessionGuard.scopedKey("key_cached_pet_id", altAccountUin)],
            legacySavedPetId = store["key_cached_pet_id"]
        )
        assertNull(resolvedAlt)
    }
}

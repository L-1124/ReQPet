package com.copilot.qqpet.engine

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 多账号（大小号切换）宠物 ID 归属校验与按 UIN 分桶缓存隔离器
 */
object AccountSessionGuard {

    /**
     * 判断 UIN 是否为合法的非零 QQ 号数字串
     */
    fun isValidUin(uin: String?): Boolean {
        if (uin.isNullOrBlank()) return false
        val trimmed = uin.trim()
        return trimmed != "0" && trimmed.length >= 5 && trimmed.all { it.isDigit() }
    }

    /**
     * 从 Base64 编码的 petId (解码后形如 "910298997-4-2-1785238894469") 中提取主人 UIN
     */
    fun extractOwnerUinFromPetId(petId: String?): String {
        if (petId.isNullOrBlank()) return ""
        val raw = petId.trim()
        try {
            val decodedBytes = Base64.getDecoder().decode(raw)
            val decoded = String(decodedBytes, StandardCharsets.UTF_8)
            val uinPart = decoded.substringBefore("-").trim()
            if (isValidUin(uinPart)) {
                return uinPart
            }
        } catch (_: Throwable) {}
        return ""
    }

    /**
     * 校验缓存的 petId 是否属于当前登录的 QQ 号
     * - 若 currentRuntimeUin 尚未就绪（"" 或 "0"），返回 true（避免启动过渡期误清缓存）
     * - 若 currentRuntimeUin 合法且 petId 可解出 ownerUin，则严格要求 ownerUin == currentRuntimeUin
     */
    fun isPetIdBelongingToUin(petId: String?, currentRuntimeUin: String?): Boolean {
        if (petId.isNullOrBlank()) return false
        if (!isValidUin(currentRuntimeUin)) {
            // 运行时 UIN 暂不可用时保留现有有效 petId，不误删同号重登缓存
            return extractOwnerUinFromPetId(petId).isNotEmpty()
        }
        val ownerUin = extractOwnerUinFromPetId(petId)
        if (ownerUin.isEmpty()) return false
        return ownerUin == currentRuntimeUin!!.trim()
    }

    /**
     * 生成按 UIN 隔离的 SharedPreferences Key
     */
    fun scopedKey(baseKey: String, uin: String?): String {
        return if (isValidUin(uin)) "${baseKey}_${uin!!.trim()}" else baseKey
    }

    /**
     * 结合当前登录 UIN、内存 petId、分桶缓存与旧版全局缓存，解析当前账号真正可用的 petId。
     * 若切到了新号（缓存属于其他 UIN），返回 null 以触发向服务端 0x95e1_0 重新拉取新号 petId。
     */
    fun resolveActivePetId(
        currentRuntimeUin: String?,
        memoryPetId: String?,
        scopedSavedPetId: String?,
        legacySavedPetId: String?
    ): String? {
        // 1. 优先校验内存中的 petId
        if (!memoryPetId.isNullOrBlank() && isPetIdBelongingToUin(memoryPetId, currentRuntimeUin)) {
            return memoryPetId.trim()
        }
        // 2. 其次校验当前 UIN 专属分桶中的 petId
        if (!scopedSavedPetId.isNullOrBlank() && isPetIdBelongingToUin(scopedSavedPetId, currentRuntimeUin)) {
            return scopedSavedPetId.trim()
        }
        // 3. 兼容旧版未分桶的 key_cached_pet_id（仅当归属人与当前 UIN 一致时才采纳）
        if (!legacySavedPetId.isNullOrBlank() && isPetIdBelongingToUin(legacySavedPetId, currentRuntimeUin)) {
            return legacySavedPetId.trim()
        }
        return null
    }
}

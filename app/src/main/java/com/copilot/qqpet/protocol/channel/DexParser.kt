package com.copilot.qqpet.protocol.channel

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 纯 Kotlin 极简 DEX 规范字节码解析器：
 * 直接流式读取 APK 内各分包 classes*.dex 的 Header, TypeId 与 ClassDef 接口实现表，
 * 在 0 Native .so 依赖、0 Maps 暴露风险的前提下，毫秒级精确反查实现目标接口的真实类名。
 */
object DexParser {

    const val DEFAULT_INTERFACE_DESC = "Lcom/tencent/ergo/hostdelegate/pb/PetPbDelegate;"

    /**
     * 在指定 APK 文件中搜索实现指定接口的第一个类。
     * @param apkPath APK 文件的绝对路径（如 context.applicationInfo.sourceDir）
     * @param interfaceDesc 目标接口的 DEX 类型描述符（如 Lcom/.../PetPbDelegate;）
     * @return 匹配类的 Java 全限定类名（如 com.tencent.mobileqq.qqpet.delegate.m），未找到或出错返回 null
     */
    fun findImplementingClass(apkPath: String, interfaceDesc: String = DEFAULT_INTERFACE_DESC): String? {
        val apkFile = File(apkPath)
        if (!apkFile.exists() || !apkFile.canRead()) return null

        return try {
            ZipFile(apkFile).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    .sortedWith(Comparator { e1, e2 -> extractDexIndex(e1.name).compareTo(extractDexIndex(e2.name)) })
                    .toList()

                for (entry in dexEntries) {
                    val className = parseDexForImplementingClass(zip, entry, interfaceDesc)
                    if (className != null) {
                        return@use className
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractDexIndex(name: String): Int {
        val num = name.removePrefix("classes").removeSuffix(".dex")
        return if (num.isEmpty()) 1 else num.toIntOrNull() ?: 9999
    }

    private fun parseDexForImplementingClass(zip: ZipFile, entry: ZipEntry, targetInterfaceDesc: String): String? {
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        if (bytes.size < 112) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // 验证 DEX 魔数：dex\n035\0 ~ 039\0
        if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
            bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte()
        ) {
            return null
        }

        val stringIdsSize = buf.getInt(0x38)
        val stringIdsOff = buf.getInt(0x3C)
        val typeIdsSize = buf.getInt(0x40)
        val typeIdsOff = buf.getInt(0x44)
        val classDefsSize = buf.getInt(0x60)
        val classDefsOff = buf.getInt(0x64)

        if (stringIdsSize <= 0 || typeIdsSize <= 0 || classDefsSize <= 0) return null

        fun getString(strIdx: Int): String {
            if (strIdx !in 0 until stringIdsSize) return ""
            val strDataOff = buf.getInt(stringIdsOff + strIdx * 4)
            if (strDataOff < 0 || strDataOff >= bytes.size) return ""
            var pos = strDataOff
            // 跳过 ULEB128 编码的 utf16_size
            while (pos < bytes.size && (bytes[pos].toInt() and 0x80) != 0) {
                pos++
            }
            pos++
            var end = pos
            while (end < bytes.size && bytes[end].toInt() != 0) {
                end++
            }
            return String(bytes, pos, end - pos, Charsets.UTF_8)
        }

        // 在 type_ids 中利用字符串有序性进行二分查找
        var low = 0
        var high = typeIdsSize - 1
        var targetTypeIdx = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val strIdx = buf.getInt(typeIdsOff + mid * 4)
            val desc = getString(strIdx)
            val cmp = desc.compareTo(targetInterfaceDesc)
            if (cmp == 0) {
                targetTypeIdx = mid
                break
            } else if (cmp < 0) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        // 若本 DEX 中未引用目标接口，直接短路跳过
        if (targetTypeIdx == -1) return null

        // 遍历本 DEX 的 class_defs 表
        for (c in 0 until classDefsSize) {
            val cOff = classDefsOff + c * 32
            if (cOff + 16 > bytes.size) break
            val classIdx = buf.getInt(cOff)
            val interfacesOff = buf.getInt(cOff + 12)
            if (interfacesOff <= 0 || interfacesOff + 4 > bytes.size) continue

            val ifaceSize = buf.getInt(interfacesOff)
            if (ifaceSize <= 0 || interfacesOff + 4 + ifaceSize * 2 > bytes.size) continue

            for (i in 0 until ifaceSize) {
                val tIdx = buf.getShort(interfacesOff + 4 + i * 2).toInt() and 0xFFFF
                if (tIdx == targetTypeIdx) {
                    val classStrIdx = buf.getInt(typeIdsOff + classIdx * 4)
                    val rawDesc = getString(classStrIdx)
                    return descriptorToClassName(rawDesc)
                }
            }
        }

        return null
    }

    private fun descriptorToClassName(desc: String): String {
        return if (desc.startsWith("L") && desc.endsWith(";")) {
            desc.substring(1, desc.length - 1).replace('/', '.')
        } else {
            desc
        }
    }
}

package io.github.reqpet.protocol.channel

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object DexParser {

    const val DEFAULT_INTERFACE_DESC = "Lcom/tencent/ergo/hostdelegate/pb/PetPbDelegate;"

    private const val MAX_SINGLE_DEX_SIZE = 128L * 1024 * 1024
    private const val MAX_TOTAL_DEX_SIZE = 1024L * 1024 * 1024
    private const val ZIP_READ_CHUNK_SIZE = 64 * 1024

    private class DexCorruptException(message: String) : RuntimeException(message)

    private fun isSupportedDexVersion(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val v0 = bytes[4].toInt().toChar()
        val v1 = bytes[5].toInt().toChar()
        val v2 = bytes[6].toInt().toChar()
        val v3 = bytes[7].toInt()
        if (v3 != 0) return false
        val version = "$v0$v1$v2"
        return version in setOf("035", "036", "037", "038", "039", "040")
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        val b0 = bytes[offset].toLong() and 0xFFL
        val b1 = (bytes[offset + 1].toLong() and 0xFFL) shl 8
        val b2 = (bytes[offset + 2].toLong() and 0xFFL) shl 16
        val b3 = (bytes[offset + 3].toLong() and 0xFFL) shl 24
        return b0 or b1 or b2 or b3
    }

    private fun readI32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readDexEntryBytes(
        zip: ZipFile,
        entry: ZipEntry
    ): ByteArray? {
        val size = entry.size
        if (size !in 112L..MAX_SINGLE_DEX_SIZE) return null
        val bytes = ByteArray(size.toInt())
        zip.getInputStream(entry).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val toRead = minOf(ZIP_READ_CHUNK_SIZE, bytes.size - offset)
                val count = input.read(bytes, offset, toRead)
                if (count < 0) return null
                offset += count
            }
        }
        return bytes
    }

    fun findImplementingClass(apkPath: String, interfaceDesc: String = DEFAULT_INTERFACE_DESC): String? {
        val apkFile = File(apkPath)
        if (!apkFile.exists() || !apkFile.canRead()) return null

        return try {
            ZipFile(apkFile).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    .sortedWith(Comparator { e1, e2 -> extractDexIndex(e1.name).compareTo(extractDexIndex(e2.name)) })
                    .toList()

                var totalBytesRead = 0L
                val tempRef = IntArray(1)

                for (entry in dexEntries) {
                    val entrySize = entry.size
                    if (entrySize !in 112L..MAX_SINGLE_DEX_SIZE) continue
                    if (totalBytesRead + entrySize > MAX_TOTAL_DEX_SIZE) break
                    totalBytesRead += entrySize

                    val bytes = readDexEntryBytes(zip, entry) ?: continue
                    val className = parseDexForImplementingClassBytes(bytes, interfaceDesc, tempRef)
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

    internal fun parseDexForImplementingClassBytes(
        bytes: ByteArray,
        targetInterfaceDesc: String,
        tempRef: IntArray
    ): String? {
        if (bytes.size < 112) return null
        if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
            bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte()
        ) {
            return null
        }

        if (!isSupportedDexVersion(bytes)) return null

        val endianTag = readI32(bytes, 0x28)
        if (endianTag != 0x12345678) return null

        val stringIdsSize = readU32(bytes, 0x38)
        val stringIdsOff = readU32(bytes, 0x3C)
        val typeIdsSize = readU32(bytes, 0x40)
        val typeIdsOff = readU32(bytes, 0x44)
        val classDefsSize = readU32(bytes, 0x60)
        val classDefsOff = readU32(bytes, 0x64)

        val dexSize = bytes.size.toLong()
        if (stringIdsOff < 0L || stringIdsOff + stringIdsSize * 4L > dexSize ||
            typeIdsOff < 0L || typeIdsOff + typeIdsSize * 4L > dexSize ||
            classDefsOff < 0L || classDefsOff + classDefsSize * 32L > dexSize
        ) {
            return null
        }

        var low = 0
        var high = (typeIdsSize - 1L).toInt()
        var targetTypeIdx = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val desc = try {
                getTypeDescriptor(bytes, typeIdsOff, typeIdsSize, stringIdsOff, stringIdsSize, mid, tempRef)
            } catch (_: DexCorruptException) {
                return null
            }
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

        if (targetTypeIdx == -1) return null

        for (c in 0 until classDefsSize.toInt()) {
            val cOff = (classDefsOff + c * 32L).toInt()
            val classIdx = readU32(bytes, cOff).toInt()
            val interfacesOff = readU32(bytes, cOff + 12)
            if (interfacesOff <= 0L || interfacesOff + 4L > dexSize) continue

            val ifaceSize = readU32(bytes, interfacesOff.toInt()).toInt()
            if (ifaceSize <= 0 || interfacesOff + 4L + ifaceSize * 2L > dexSize) continue

            for (i in 0 until ifaceSize) {
                val tIdx = readU16(bytes, (interfacesOff + 4L + i * 2L).toInt())
                if (tIdx == targetTypeIdx) {
                    return try {
                        val rawDesc = getTypeDescriptor(
                            bytes,
                            typeIdsOff,
                            typeIdsSize,
                            stringIdsOff,
                            stringIdsSize,
                            classIdx,
                            tempRef
                        )
                        descriptorToClassName(rawDesc)
                    } catch (_: DexCorruptException) {
                        null
                    }
                }
            }
        }

        return null
    }

    internal fun readUleb128(bytes: ByteArray, offsetRef: IntArray): Long {
        var result = 0L
        var shift = 0
        var count = 0
        while (count < 5) {
            val pos = offsetRef[0]
            if (pos < 0 || pos >= bytes.size) {
                throw DexCorruptException("Truncated ULEB128 at offset $pos")
            }
            val byteVal = bytes[pos].toInt() and 0xFF
            offsetRef[0] = pos + 1

            // 5th byte can only contribute bits 28..31; high bits 4..7 indicate continuation or 32-bit overflow.
            if (count == 4 && (byteVal and 0xF0) != 0) {
                throw DexCorruptException("ULEB128 5th byte overflow (0x${Integer.toHexString(byteVal)}) at offset $pos")
            }

            result = result or ((byteVal.toLong() and 0x7FL) shl shift)
            count++
            if ((byteVal and 0x80) == 0) {
                return result
            }
            shift += 7
        }
        throw DexCorruptException("ULEB128 overflow: exceeds 5 bytes at offset ${offsetRef[0]}")
    }

    internal fun decodeMutf8(bytes: ByteArray, offset: Int, length: Int, utf16Size: Int): String {
        if (utf16Size < 0 || utf16Size > bytes.size) {
            throw DexCorruptException("MUTF-8 utf16Size $utf16Size exceeds DEX file bounds")
        }
        val chars = CharArray(utf16Size)
        var b = offset
        val end = offset + length
        var c = 0
        while (b < end) {
            if (c >= utf16Size) {
                throw DexCorruptException("MUTF-8 decoded char count exceeds declared utf16Size $utf16Size")
            }
            val b1 = bytes[b++].toInt() and 0xFF
            when (b1 shr 4) {
                in 0..7 -> {
                    if (b1 == 0 && b < end) {
                        throw DexCorruptException("Premature null byte in MUTF-8 at offset ${b - 1}")
                    }
                    chars[c++] = b1.toChar()
                }

                12, 13 -> {
                    if (b >= end) throw DexCorruptException("Truncated 2-byte MUTF-8 at offset $b")
                    val b2 = bytes[b++].toInt() and 0xFF
                    if ((b2 and 0xC0) != 0x80) throw DexCorruptException("Invalid MUTF-8 continuation byte at offset $b")
                    chars[c++] = (((b1 and 0x1F) shl 6) or (b2 and 0x3F)).toChar()
                }

                14 -> {
                    if (b + 1 >= end) throw DexCorruptException("Truncated 3-byte MUTF-8 at offset $b")
                    val b2 = bytes[b++].toInt() and 0xFF
                    val b3 = bytes[b++].toInt() and 0xFF
                    if ((b2 and 0xC0) != 0x80 || (b3 and 0xC0) != 0x80) {
                        throw DexCorruptException("Invalid MUTF-8 continuation bytes at offset $b")
                    }
                    chars[c++] = (((b1 and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar()
                }

                else -> {
                    throw DexCorruptException("Invalid MUTF-8 lead byte 0x${Integer.toHexString(b1)} at offset ${b - 1}")
                }
            }
        }
        if (c != utf16Size) {
            throw DexCorruptException("MUTF-8 declared size $utf16Size does not match decoded length $c")
        }
        return String(chars, 0, c)
    }

    private fun readStringAt(
        bytes: ByteArray,
        stringIdsOff: Long,
        stringIdsSize: Long,
        strIdx: Int,
        tempRef: IntArray
    ): String? {
        if (strIdx < 0 || strIdx.toLong() >= stringIdsSize) return null
        val entryOff = stringIdsOff + strIdx * 4L
        if (entryOff + 4L > bytes.size.toLong()) {
            throw DexCorruptException("String table entry $strIdx offset exceeds DEX file")
        }
        val strOff = readU32(bytes, entryOff.toInt())
        if (strOff <= 0L || strOff >= bytes.size.toLong()) {
            throw DexCorruptException("String offset $strOff outside DEX file")
        }
        tempRef[0] = strOff.toInt()
        val utf16Size = readUleb128(bytes, tempRef)
        if (utf16Size > bytes.size.toLong()) {
            throw DexCorruptException("String utf16Size $utf16Size exceeds DEX size")
        }
        val strStart = tempRef[0]
        var strEnd = strStart
        while (strEnd < bytes.size && bytes[strEnd] != 0.toByte()) {
            strEnd++
        }
        if (strEnd >= bytes.size) {
            throw DexCorruptException("Unterminated string at offset $strStart")
        }
        return decodeMutf8(bytes, strStart, strEnd - strStart, utf16Size.toInt())
    }

    private fun getTypeDescriptor(
        bytes: ByteArray,
        typeIdsOff: Long,
        typeIdsSize: Long,
        stringIdsOff: Long,
        stringIdsSize: Long,
        typeIdx: Int,
        tempRef: IntArray
    ): String {
        if (typeIdx < 0 || typeIdx.toLong() >= typeIdsSize) {
            throw DexCorruptException("Type index $typeIdx outside type_ids bounds $typeIdsSize")
        }
        val typeEntryOff = typeIdsOff + typeIdx * 4L
        if (typeEntryOff + 4L > bytes.size.toLong()) {
            throw DexCorruptException("Type entry offset outside DEX file")
        }
        val strIdx = readU32(bytes, typeEntryOff.toInt()).toInt()
        return readStringAt(bytes, stringIdsOff, stringIdsSize, strIdx, tempRef) ?: ""
    }

    internal fun extractDexIndex(name: String): Int {
        val num = name.removePrefix("classes").removeSuffix(".dex")
        return if (num.isEmpty()) 1 else num.toIntOrNull() ?: 9999
    }

    fun descriptorToClassName(desc: String): String {
        return if (desc.startsWith("L") && desc.endsWith(";")) {
            desc.substring(1, desc.length - 1).replace('/', '.')
        } else {
            desc
        }
    }
}

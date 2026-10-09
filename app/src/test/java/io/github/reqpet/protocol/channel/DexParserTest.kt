package io.github.reqpet.protocol.channel

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexParserTest {

    data class DexClass(
        val descriptor: String,
        val accessFlags: Int = 1,
        val superclass: String = "Ljava/lang/Object;",
        val interfaces: List<String> = emptyList()
    )

    private fun writeUleb128(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) {
                out.write(b)
                break
            } else {
                out.write(b or 0x80)
            }
        }
    }

    private fun buildSyntheticDex(
        classes: List<DexClass>,
        extraStrings: List<String> = emptyList()
    ): ByteArray {
        val stringSet = HashSet<String>()
        stringSet.addAll(extraStrings)
        stringSet.add("Ljava/lang/Object;")

        for ((descriptor, _, superclass, interfaces) in classes) {
            stringSet.add(descriptor)
            stringSet.add(superclass)
            stringSet.addAll(interfaces)
        }

        val sortedStrings = stringSet.toList().sorted()
        val stringIdxMap = HashMap<String, Int>(sortedStrings.size)
        for (i in sortedStrings.indices) {
            stringIdxMap[sortedStrings[i]] = i
        }

        val typeSet = HashSet<String>()
        typeSet.add("Ljava/lang/Object;")
        for ((descriptor, _, superclass, interfaces) in classes) {
            typeSet.add(descriptor)
            typeSet.add(superclass)
            typeSet.addAll(interfaces)
        }
        val sortedTypes = typeSet.toList().sortedBy { stringIdxMap[it]!! }
        val typeIdxMap = HashMap<String, Int>(sortedTypes.size)
        for (i in sortedTypes.indices) {
            typeIdxMap[sortedTypes[i]] = i
        }

        val sortedClasses = classes.sortedBy { typeIdxMap[it.descriptor]!! }

        val stringIdsOff = 0x70
        val typeIdsOff = stringIdsOff + sortedStrings.size * 4
        val classDefsOff = typeIdsOff + sortedTypes.size * 4
        val dataStartOff = classDefsOff + sortedClasses.size * 32

        val dataOut = ByteArrayOutputStream()

        val stringDataOffsets = IntArray(sortedStrings.size)
        for (i in sortedStrings.indices) {
            stringDataOffsets[i] = dataStartOff + dataOut.size()
            val s = sortedStrings[i]
            val utf8Bytes = s.toByteArray(Charsets.UTF_8)
            writeUleb128(dataOut, s.length)
            dataOut.write(utf8Bytes)
            dataOut.write(0)
        }

        val classInterfacesOffsets = HashMap<DexClass, Int>()
        for (cls in sortedClasses) {
            if (cls.interfaces.isEmpty()) {
                classInterfacesOffsets[cls] = 0
            } else {
                while ((dataStartOff + dataOut.size()) % 4 != 0) {
                    dataOut.write(0)
                }
                classInterfacesOffsets[cls] = dataStartOff + dataOut.size()
                val count = cls.interfaces.size
                dataOut.write(count and 0xFF)
                dataOut.write((count shr 8) and 0xFF)
                dataOut.write((count shr 16) and 0xFF)
                dataOut.write((count shr 24) and 0xFF)
                for (iface in cls.interfaces) {
                    val tIdx = typeIdxMap[iface]!!
                    dataOut.write(tIdx and 0xFF)
                    dataOut.write((tIdx shr 8) and 0xFF)
                }
                while ((dataStartOff + dataOut.size()) % 4 != 0) {
                    dataOut.write(0)
                }
            }
        }

        val totalSize = dataStartOff + dataOut.size()
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header (0x70)
        buf.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // checksum
        buf.put(ByteArray(20)) // signature
        buf.putInt(totalSize)
        buf.putInt(0x70)
        buf.putInt(0x12345678)
        buf.putInt(0); buf.putInt(0) // link
        buf.putInt(0) // map_off
        buf.putInt(sortedStrings.size)
        buf.putInt(stringIdsOff)
        buf.putInt(sortedTypes.size)
        buf.putInt(typeIdsOff)
        buf.putInt(0) // proto_ids_size
        buf.putInt(0) // proto_ids_off
        buf.putInt(0); buf.putInt(0) // fields
        buf.putInt(0) // method_ids_size
        buf.putInt(0) // method_ids_off
        buf.putInt(sortedClasses.size)
        buf.putInt(classDefsOff)
        buf.putInt(dataOut.size())
        buf.putInt(dataStartOff)

        // string_ids
        for (i in sortedStrings.indices) {
            buf.putInt(stringDataOffsets[i])
        }

        // type_ids
        for (i in sortedTypes.indices) {
            buf.putInt(stringIdxMap[sortedTypes[i]]!!)
        }

        // class_defs
        for (i in sortedClasses.indices) {
            val cls = sortedClasses[i]
            buf.putInt(typeIdxMap[cls.descriptor]!!)
            buf.putInt(cls.accessFlags)
            buf.putInt(typeIdxMap[cls.superclass]!!)
            buf.putInt(classInterfacesOffsets[cls]!!)
            buf.putInt(-1) // source_file_idx
            buf.putInt(0)  // annotations_off
            buf.putInt(0)  // class_data_off
            buf.putInt(0)  // static_values_off
        }

        // data section
        buf.put(dataOut.toByteArray())

        return buf.array()
    }

    private fun createZipWithDex(dexEntries: Map<String, ByteArray>): File {
        val tempFile = File.createTempFile("synth_dex_", ".apk")
        tempFile.deleteOnExit()
        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            for ((name, bytes) in dexEntries) {
                val entry = ZipEntry(name)
                entry.size = bytes.size.toLong()
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return tempFile
    }

    @Test
    fun testNonExistentApkReturnsNull() {
        val result = DexParser.findImplementingClass("non_existent_file.apk")
        assertNull(result)
    }

    @Test
    fun testSyntheticDexImplementingClass() {
        val cls = DexClass(
            descriptor = "Lcom/tencent/mobileqq/qqpet/delegate/TestDelegate;",
            interfaces = listOf(DexParser.DEFAULT_INTERFACE_DESC)
        )
        val dexBytes = buildSyntheticDex(listOf(cls))
        val apkFile = createZipWithDex(mapOf("classes.dex" to dexBytes))
        try {
            val name = DexParser.findImplementingClass(apkFile.absolutePath)
            assertEquals("com.tencent.mobileqq.qqpet.delegate.TestDelegate", name)
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testSyntheticDexCustomInterfaceDesc() {
        val customInterface = "Lcustom/pkg/CustomDelegateInterface;"
        val cls = DexClass(
            descriptor = "Lcom/tencent/ergo/impl/CustomDelegateImpl;",
            interfaces = listOf(customInterface)
        )
        val dexBytes = buildSyntheticDex(listOf(cls))
        val apkFile = createZipWithDex(mapOf("classes.dex" to dexBytes))
        try {
            val name = DexParser.findImplementingClass(apkFile.absolutePath, customInterface)
            assertEquals("com.tencent.ergo.impl.CustomDelegateImpl", name)
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testSyntheticDexNoImplementingClassReturnsNull() {
        val cls = DexClass(
            descriptor = "Lcom/tencent/mobileqq/qqpet/UnrelatedClass;",
            interfaces = emptyList()
        )
        val dexBytes = buildSyntheticDex(listOf(cls))
        val apkFile = createZipWithDex(mapOf("classes.dex" to dexBytes))
        try {
            val name = DexParser.findImplementingClass(apkFile.absolutePath)
            assertNull(name)
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testMultidexOrderingLocatesDelegateInSecondaryDex() {
        val unrelatedCls = DexClass(
            descriptor = "Lcom/tencent/mobileqq/Unrelated;",
            interfaces = emptyList()
        )
        val delegateCls = DexClass(
            descriptor = "Lcom/tencent/mobileqq/qqpet/SecondaryDelegate;",
            interfaces = listOf(DexParser.DEFAULT_INTERFACE_DESC)
        )

        val dex1 = buildSyntheticDex(listOf(unrelatedCls))
        val dex2 = buildSyntheticDex(listOf(delegateCls))

        val apkFile = createZipWithDex(mapOf("classes.dex" to dex1, "classes2.dex" to dex2))
        try {
            val name = DexParser.findImplementingClass(apkFile.absolutePath)
            assertEquals("com.tencent.mobileqq.qqpet.SecondaryDelegate", name)
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testMultidexFirstDexTakesPrecedence() {
        val delegate1 = DexClass(
            descriptor = "Lcom/tencent/mobileqq/qqpet/FirstDelegate;",
            interfaces = listOf(DexParser.DEFAULT_INTERFACE_DESC)
        )
        val delegate2 = DexClass(
            descriptor = "Lcom/tencent/mobileqq/qqpet/SecondDelegate;",
            interfaces = listOf(DexParser.DEFAULT_INTERFACE_DESC)
        )

        val dex1 = buildSyntheticDex(listOf(delegate1))
        val dex2 = buildSyntheticDex(listOf(delegate2))

        val apkFile = createZipWithDex(mapOf("classes.dex" to dex1, "classes2.dex" to dex2))
        try {
            val name = DexParser.findImplementingClass(apkFile.absolutePath)
            assertEquals("com.tencent.mobileqq.qqpet.FirstDelegate", name)
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testExtractDexIndexOrdering() {
        assertEquals(1, DexParser.extractDexIndex("classes.dex"))
        assertEquals(2, DexParser.extractDexIndex("classes2.dex"))
        assertEquals(10, DexParser.extractDexIndex("classes10.dex"))
        assertEquals(99, DexParser.extractDexIndex("classes99.dex"))
        assertEquals(9999, DexParser.extractDexIndex("classes_abc.dex"))

        val sorted = listOf("classes10.dex", "classes.dex", "classes2.dex")
            .sortedBy { DexParser.extractDexIndex(it) }
        assertEquals(listOf("classes.dex", "classes2.dex", "classes10.dex"), sorted)
    }

    @Test
    fun testInvalidDexMagicReturnsNull() {
        val cls = DexClass(
            descriptor = "Lcom/tencent/mobileqq/qqpet/delegate/TestDelegate;",
            interfaces = listOf(DexParser.DEFAULT_INTERFACE_DESC)
        )
        val dexBytes = buildSyntheticDex(listOf(cls))
        dexBytes[0] = 'b'.code.toByte() // Corrupt magic

        val apkFile = createZipWithDex(mapOf("classes.dex" to dexBytes))
        try {
            assertNull(DexParser.findImplementingClass(apkFile.absolutePath))
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testUnsupportedDexVersionReturnsNull() {
        val cls = DexClass(
            descriptor = "Lcom/tencent/mobileqq/qqpet/delegate/TestDelegate;",
            interfaces = listOf(DexParser.DEFAULT_INTERFACE_DESC)
        )
        val dexBytes = buildSyntheticDex(listOf(cls))
        dexBytes[4] = '0'.code.toByte()
        dexBytes[5] = '9'.code.toByte()
        dexBytes[6] = '9'.code.toByte() // Version 099 unsupported

        val apkFile = createZipWithDex(mapOf("classes.dex" to dexBytes))
        try {
            assertNull(DexParser.findImplementingClass(apkFile.absolutePath))
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testTruncatedDexBytesReturnsNull() {
        val truncated = ByteArray(64) { 0 }
        val apkFile = createZipWithDex(mapOf("classes.dex" to truncated))
        try {
            assertNull(DexParser.findImplementingClass(apkFile.absolutePath))
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testCorruptedTableOffsetsExceedingDexSizeReturnsNull() {
        val cls = DexClass(
            descriptor = "Lcom/tencent/mobileqq/qqpet/delegate/TestDelegate;",
            interfaces = listOf(DexParser.DEFAULT_INTERFACE_DESC)
        )
        val dexBytes = buildSyntheticDex(listOf(cls))
        // Overwrite classDefsOff at offset 0x64 with a value exceeding file size
        val buf = ByteBuffer.wrap(dexBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x64, 0x7FFFFFFF)

        val apkFile = createZipWithDex(mapOf("classes.dex" to dexBytes))
        try {
            assertNull(DexParser.findImplementingClass(apkFile.absolutePath))
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun testReadUleb128ValidAndHardenedOverflowChecks() {
        // Valid 1-byte
        val b1 = byteArrayOf(0x05)
        assertEquals(5L, DexParser.readUleb128(b1, intArrayOf(0)))

        // Valid 2-byte: 0x80, 0x01 = 128
        val b2 = byteArrayOf(0x80.toByte(), 0x01)
        assertEquals(128L, DexParser.readUleb128(b2, intArrayOf(0)))

        // Truncated ULEB128 (continuation bit set at EOF)
        val truncated = byteArrayOf(0x80.toByte())
        try {
            DexParser.readUleb128(truncated, intArrayOf(0))
            fail("Expected exception on truncated ULEB128")
        } catch (_: Exception) {
            // expected
        }

        // 5th byte continuation bit set (exceeds 5 bytes)
        val fiveContinuation = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()
        )
        try {
            DexParser.readUleb128(fiveContinuation, intArrayOf(0))
            fail("Expected exception on 5th byte continuation bit")
        } catch (_: Exception) {
            // expected
        }

        // 5th byte high bits overflow (0xF0 non-zero)
        val fiveHighBits = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10.toByte()
        )
        try {
            DexParser.readUleb128(fiveHighBits, intArrayOf(0))
            fail("Expected exception on 5th byte high bits overflow")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun testDecodeMutf8AsciiAndMultibyteCharacters() {
        // ASCII
        val asciiBytes = "Hello".toByteArray(Charsets.US_ASCII)
        assertEquals("Hello", DexParser.decodeMutf8(asciiBytes, 0, asciiBytes.size, 5))

        // 2-byte UTF-8: '\u00A9' (copyright symbol) -> 0xC2, 0xA9
        val twoByte = byteArrayOf(0xC2.toByte(), 0xA9.toByte())
        assertEquals("\u00A9", DexParser.decodeMutf8(twoByte, 0, twoByte.size, 1))

        // 3-byte UTF-8: '\u4E16\u754C' ("世界") -> 6 bytes
        val chineseBytes = "\u4E16\u754C".toByteArray(Charsets.UTF_8)
        assertEquals("\u4E16\u754C", DexParser.decodeMutf8(chineseBytes, 0, chineseBytes.size, 2))
    }

    @Test
    fun testDecodeMutf8HardenedCorruptionChecks() {
        // Declared utf16Size exceeds buffer
        val buf = byteArrayOf(0x61, 0x62)
        try {
            DexParser.decodeMutf8(buf, 0, buf.size, 100)
            fail("Expected exception when utf16Size exceeds bounds")
        } catch (_: Exception) {
            // expected
        }

        // Premature null byte inside string
        val prematureNull = byteArrayOf(0x61, 0x00, 0x62)
        try {
            DexParser.decodeMutf8(prematureNull, 0, prematureNull.size, 3)
            fail("Expected exception on premature null byte")
        } catch (_: Exception) {
            // expected
        }

        // Invalid 2-byte continuation byte
        val invalidContinuation = byteArrayOf(0xC2.toByte(), 0x00)
        try {
            DexParser.decodeMutf8(invalidContinuation, 0, invalidContinuation.size, 1)
            fail("Expected exception on invalid continuation byte")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun testDescriptorToClassName() {
        assertEquals("com.tencent.mobileqq.Test", DexParser.descriptorToClassName("Lcom/tencent/mobileqq/Test;"))
        assertEquals("java.lang.String", DexParser.descriptorToClassName("Ljava/lang/String;"))
        assertEquals("int", DexParser.descriptorToClassName("int"))
        assertEquals("", DexParser.descriptorToClassName(""))
        assertEquals("NotADescriptor", DexParser.descriptorToClassName("NotADescriptor"))
    }
}

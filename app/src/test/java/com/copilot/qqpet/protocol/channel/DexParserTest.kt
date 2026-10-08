package com.copilot.qqpet.protocol.channel

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DexParserTest {

    @Test
    fun testNonExistentApkReturnsNull() {
        val result = DexParser.findImplementingClass("non_existent_file.apk")
        assertNull(result)
    }

    @Test
    fun testRealApkExtractsDelegateClass() {
        val apkFile = File("9.3.70.41925_2a98ecf56e5ef04b.apk")
        if (!apkFile.exists()) {
            return
        }

        val startTime = System.currentTimeMillis()
        val className = DexParser.findImplementingClass(apkFile.absolutePath)
        val duration = System.currentTimeMillis() - startTime

        assertNotNull("Should find implementing class for PetPbDelegate", className)
        assertEquals("com.tencent.mobileqq.qqpet.delegate.m", className)
        assertTrue("Scan should finish rapidly (got ${duration}ms)", duration < 2000L)
    }

    @Test
    fun testNonExistentInterfaceReturnsNull() {
        val apkFile = File("9.3.70.41925_2a98ecf56e5ef04b.apk")
        if (!apkFile.exists()) {
            return
        }

        val result = DexParser.findImplementingClass(
            apkFile.absolutePath,
            "Lcom/tencent/nonexistent/FakeInterface;"
        )
        assertNull(result)
    }
}

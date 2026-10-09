package io.github.reqpet.protocol

import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.client.PetBathProtocolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation

class PetBathProtocolClientTest {

    @Before
    fun setUp() {
        TestUserPetManager.reset()
    }

    @After
    fun tearDown() {
        TestUserPetManager.reset()
    }

    open class TestNanoMessage {
        companion object {
            @JvmStatic
            fun toByteArray(msg: TestNanoMessage): ByteArray = msg.toByteArray()
        }

        open fun toByteArray(): ByteArray = ByteArray(0)
    }

    class TestEventClass : TestNanoMessage() {
        @JvmField
        var a: Int = 0

        @JvmField
        var b: Int = 0

        @JvmField
        var c: Int = 0

        override fun toByteArray(): ByteArray {
            val pw = ProtoWire.message()
            if (a != 0) pw.writeVarint(1, a.toLong())
            if (b != 0) pw.writeVarint(2, b.toLong())
            if (c != 0) pw.writeVarint(3, c.toLong())
            return pw.toByteArray()
        }
    }

    class TestExtInfoClass : TestNanoMessage() {
        @JvmField
        var b: Long = 0L

        @JvmField
        var h: Int = 0

        override fun toByteArray(): ByteArray {
            val pw = ProtoWire.message()
            if (b != 0L) pw.writeVarint(7, b)
            if (h != 0) pw.writeVarint(13, h.toLong())
            return pw.toByteArray()
        }
    }

    class BrokenExtInfoMissingH : TestNanoMessage() {
        @JvmField
        var b: Long = 0L
    }

    class TestItemClass : TestNanoMessage() {
        @JvmField
        var d: Int = 0

        override fun toByteArray(): ByteArray {
            val pw = ProtoWire.message()
            if (d != 0) pw.writeVarint(5, d.toLong())
            return pw.toByteArray()
        }
    }

    class TestBehaviorClass : TestNanoMessage()

    open class TestRequestClass : TestNanoMessage() {
        @JvmField
        var a: String = ""

        @JvmField
        var b: String = ""

        @JvmField
        var c: TestEventClass? = null

        @JvmField
        var d: TestExtInfoClass? = null

        @JvmField
        var e: TestItemClass? = null

        override fun toByteArray(): ByteArray {
            val pw = ProtoWire.message()
            if (a.isNotEmpty()) pw.writeString(1, a)
            if (b.isNotEmpty()) pw.writeString(2, b)
            c?.let { pw.writeBytes(3, it.toByteArray()) }
            d?.let { pw.writeBytes(4, it.toByteArray()) }
            e?.let { pw.writeBytes(5, it.toByteArray()) }
            return pw.toByteArray()
        }
    }

    class SecondRequestClass : TestRequestClass()

    class BrokenRequestClassMissingItem : TestNanoMessage() {
        @JvmField
        var a: String = ""

        @JvmField
        var b: String = ""

        @JvmField
        var c: TestEventClass? = null

        @JvmField
        var d: TestExtInfoClass? = null
    }

    class TestBehaviorAdapter {
        fun d(
            petId: String,
            friendUin: String,
            exePath: TestEventClass,
            exeExtInfo: TestExtInfoClass,
            extInfo: TestItemClass,
            behavior: TestBehaviorClass,
            continuation: Continuation<Any?>
        ): Any? = null
    }

    class TestUserPetManager {
        companion object {
            @JvmField
            var a: TestUserPetManager = TestUserPetManager()

            fun reset() {
                a = TestUserPetManager()
            }
        }

        var mockPet: Any? = null

        fun m(uin: String): Any? = mockPet
    }

    class TestPetObject {
        @JvmField
        var e: Any? = null
    }

    class TestPetStatusObject {
        @JvmField
        var b: Int = 0
    }

    class MalformedPetStatusObject {
        @JvmField
        var b: String = "malformed_string"
    }

    class MockHostClassLoader(
        private val classMap: Map<String, Class<*>>,
        parent: ClassLoader = MockHostClassLoader::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            classMap[name]?.let { return it }
            return super.loadClass(name, resolve)
        }
    }

    private fun buildHostLoader(
        requestClass: Class<*> = TestRequestClass::class.java,
        extraMap: Map<String, Class<*>> = emptyMap()
    ): MockHostClassLoader {
        val map = mutableMapOf<String, Class<*>>(
            "com.google.protobuf.nano.MessageNano" to TestNanoMessage::class.java,
            "com.tencent.ergo.behavior.net.EGBehaviorNetworkAdapter" to TestBehaviorAdapter::class.java,
            "com.tencent.ergo.user.UserPetManager" to TestUserPetManager::class.java,
            "io.github.reqpet.protocol.d" to requestClass
        )
        map.putAll(extraMap)
        return MockHostClassLoader(map)
    }

    @Test
    fun `before failing proof - 宿主类缺失时安全退出`() {
        val emptyLoader = MockHostClassLoader(emptyMap())
        assertNull(PetBathProtocolClient.deriveBathSchema(emptyLoader))

        val channel = OidbChannel(emptyLoader)
        val client = PetBathProtocolClient(channel)
        assertNull(client.tryReflectBathBody("pet_123", "10001", 100, 1700000000000L))

        var callbackCalled = false
        var resultCode = 0
        var resultRaw: ByteArray? = null
        client.bath(petId = "pet_123", petUin = "10001") { code, raw, _ ->
            callbackCalled = true
            resultCode = code
            resultRaw = raw
        }

        assertTrue(callbackCalled)
        assertEquals(-1, resultCode)
        assertNull(resultRaw)
    }

    @Test
    fun `schema mismatch fail-closed - 结构不匹配或缺少必需字段严格拒绝`() {
        val brokenReqLoader = buildHostLoader(requestClass = BrokenRequestClassMissingItem::class.java)
        assertNull(PetBathProtocolClient.deriveBathSchema(brokenReqLoader))

        assertFalse(
            "缺少 h 字段的 extInfo 必须验证失败",
            PetBathProtocolClient.validateExtInfoClass(
                BrokenExtInfoMissingH::class.java,
                TestNanoMessage::class.java
            )
        )
    }

    @Test
    fun `ambiguity fail-closed - candidate26 存在多个不同匹配候选类时闭合拒绝`() {
        val ambiguousLoader = buildHostLoader(
            extraMap = mapOf("io.github.reqpet.protocol.e" to SecondRequestClass::class.java)
        )
        assertNull(PetBathProtocolClient.deriveBathSchema(ambiguousLoader))
    }

    @Test
    fun `malformed status field - 非基本整型状态字段拒绝并 Fail-Closed`() {
        try {
            TestUserPetManager.a.mockPet = TestPetObject().apply {
                e = MalformedPetStatusObject()
            }
            val hostLoader = buildHostLoader()
            val channel = OidbChannel(hostLoader)
            val client = PetBathProtocolClient(channel)

            assertNull(
                "状态字段非 primitiveInt 时 resolvePetStatus 必须返回 null",
                PetBathProtocolClient.resolvePetStatus(hostLoader, "20240506")
            )
            assertNull(
                "状态字段异常时 tryReflectBathBody 必须执行 Safe-Fail 返回 null",
                client.tryReflectBathBody("pet_malformed", "20240506", 100, 1715000000000L)
            )
        } finally {
            TestUserPetManager.reset()
        }
    }

    @Test
    fun `after proof - 动态推导并生成正确协议包 (真 null 状态默认 0)`() {
        // Case 1: mockPet 为 null
        TestUserPetManager.a.mockPet = null
        val hostLoader = buildHostLoader()
        val schema = PetBathProtocolClient.deriveBathSchema(hostLoader)
        assertNotNull(schema)
        assertEquals(TestRequestClass::class.java, schema!!.requestClass)
        assertEquals(TestEventClass::class.java, schema.eventClass)
        assertEquals(TestExtInfoClass::class.java, schema.extInfoClass)
        assertEquals(TestItemClass::class.java, schema.itemClass)

        val channel = OidbChannel(hostLoader)
        val client = PetBathProtocolClient(channel)
        val fixedNow = 1715000000000L
        val bytes = client.tryReflectBathBody(
            petId = "pet_9370_ok",
            petUin = "20240506",
            cleanValue = 100,
            now = fixedNow
        )
        assertNotNull(bytes)

        assertEquals("pet_9370_ok", ProtoWire.firstString(bytes, 1))
        assertEquals("20240506", ProtoWire.firstString(bytes, 2))

        val eventBytes = ProtoWire.firstBytes(bytes, 3)
        assertNotNull(eventBytes)
        assertEquals(5000L, ProtoWire.firstVarint(eventBytes, 1))
        assertEquals(500L, ProtoWire.firstVarint(eventBytes, 2))
        assertEquals(501L, ProtoWire.firstVarint(eventBytes, 3))

        val extBytes = ProtoWire.firstBytes(bytes, 4)
        assertNotNull(extBytes)
        assertEquals(fixedNow, ProtoWire.firstVarint(extBytes, 7))
        assertNull("默认状态 ES_NORMAL(0) 时不得编码 tag 13", ProtoWire.firstVarint(extBytes, 13))

        val itemBytes = ProtoWire.firstBytes(bytes, 5)
        assertNotNull(itemBytes)
        assertEquals(100L, ProtoWire.firstVarint(itemBytes, 5))

        // Case 2: pet.e 为 null
        TestUserPetManager.a.mockPet = TestPetObject().apply { e = null }
        assertEquals(0, PetBathProtocolClient.resolvePetStatus(hostLoader, "20240506"))
    }

    @Test
    fun `after proof - 宿主状态非零时保留 tag 13`() {
        try {
            TestUserPetManager.a.mockPet = TestPetObject().apply {
                e = TestPetStatusObject().apply { b = 2 }
            }

            val hostLoader = buildHostLoader()
            val channel = OidbChannel(hostLoader)
            val client = PetBathProtocolClient(channel)
            val fixedNow = 1715000000000L

            val bytes = client.tryReflectBathBody(
                petId = "pet_working",
                petUin = "20240506",
                cleanValue = 100,
                now = fixedNow
            )
            assertNotNull(bytes)

            val extBytes = ProtoWire.firstBytes(bytes, 4)
            assertNotNull(extBytes)
            assertEquals(fixedNow, ProtoWire.firstVarint(extBytes, 7))
            assertEquals(2L, ProtoWire.firstVarint(extBytes, 13))
        } finally {
            TestUserPetManager.reset()
        }
    }

    @Test
    fun `after proof - 未指定好友 petUin 时不填充 tag 2`() {
        TestUserPetManager.reset()
        val hostLoader = buildHostLoader()
        val channel = OidbChannel(hostLoader)
        val client = PetBathProtocolClient(channel)
        val fixedNow = 1715000000123L

        val bytes = client.tryReflectBathBody(
            petId = "own_pet_1",
            petUin = "",
            cleanValue = 80,
            now = fixedNow
        )
        assertNotNull(bytes)

        assertEquals("own_pet_1", ProtoWire.firstString(bytes, 1))
        assertNull(ProtoWire.firstString(bytes, 2))

        val eventBytes = ProtoWire.firstBytes(bytes, 3)
        assertNotNull(eventBytes)
        assertEquals(5000L, ProtoWire.firstVarint(eventBytes, 1))
        assertEquals(500L, ProtoWire.firstVarint(eventBytes, 2))
        assertEquals(501L, ProtoWire.firstVarint(eventBytes, 3))

        val extBytes = ProtoWire.firstBytes(bytes, 4)
        assertNotNull(extBytes)
        assertEquals(fixedNow, ProtoWire.firstVarint(extBytes, 7))
        assertNull(ProtoWire.firstVarint(extBytes, 13))

        val itemBytes = ProtoWire.firstBytes(bytes, 5)
        assertNotNull(itemBytes)
        assertEquals(80L, ProtoWire.firstVarint(itemBytes, 5))
    }
}

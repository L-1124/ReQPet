package io.github.reqpet.protocol

import io.github.reqpet.protocol.channel.OidbChannel
import io.github.reqpet.protocol.client.PetCareProtocolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetCareFeedProtocolTest {

    abstract class FakeMessageNano {
        companion object {
            @JvmStatic
            fun toByteArray(nano: FakeMessageNano): ByteArray = nano.toByteArray()
        }

        abstract fun toByteArray(): ByteArray
    }

    class FakeHostT64B : FakeMessageNano() {
        @JvmField
        var a: String = ""

        @JvmField
        var b: String = ""

        @JvmField
        var c: String = ""

        @JvmField
        var d: String = ""

        @JvmField
        var e: Int = 0

        override fun toByteArray(): ByteArray {
            val proto = ProtoWire.message()
            if (a.isNotEmpty()) proto.writeString(1, a)
            if (b.isNotEmpty()) proto.writeString(2, b)
            if (c.isNotEmpty()) proto.writeString(3, c)
            if (d.isNotEmpty()) proto.writeString(4, d)
            if (e != 0) proto.writeVarint(5, e.toLong())
            return proto.toByteArray()
        }
    }

    class FakeHostZh5B : FakeMessageNano() {
        @JvmField
        var a: String = ""

        @JvmField
        var b: String = ""

        @JvmField
        var c: String = ""

        @JvmField
        var d: String = ""

        @JvmField
        var e: Int = 0

        override fun toByteArray(): ByteArray = FakeHostT64B().also {
            it.a = this.a
            it.b = this.b
            it.c = this.c
            it.d = this.d
            it.e = this.e
        }.toByteArray()
    }

    class FakeHostO64P : FakeMessageNano() {
        @JvmField
        var a: Int = 0

        @JvmField
        var b: Long = 0L

        @JvmField
        var c: Long = 0L

        @JvmField
        var k: String = ""

        @JvmField
        var l: String = ""

        override fun toByteArray(): ByteArray = error("Irrelevant rejected schema")
    }

    class FakeMalformedNoFieldE : FakeMessageNano() {
        @JvmField
        var a: String = ""

        @JvmField
        var b: String = ""

        @JvmField
        var c: String = ""

        @JvmField
        var d: String = ""
        override fun toByteArray(): ByteArray = ByteArray(0)
    }

    class FakeNonNanoClass {
        @JvmField
        var a: String = ""

        @JvmField
        var b: String = ""

        @JvmField
        var c: String = ""

        @JvmField
        var d: String = ""

        @JvmField
        var e: Int = 0
    }

    class MockClassLoader(
        private val classMap: Map<String, Class<*>>
    ) : ClassLoader(MockClassLoader::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            classMap[name]?.let { return it }
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun testIncompatibleSchemasRejected() {
        val nanoCls = FakeMessageNano::class.java
        assertTrue(PetCareProtocolClient.validateFeedPbSchema(FakeHostT64B::class.java, nanoCls))
        assertFalse(PetCareProtocolClient.validateFeedPbSchema(FakeHostO64P::class.java, nanoCls))
        assertFalse(PetCareProtocolClient.validateFeedPbSchema(FakeMalformedNoFieldE::class.java, nanoCls))
        assertFalse(PetCareProtocolClient.validateFeedPbSchema(FakeNonNanoClass::class.java, nanoCls))
    }

    @Test
    fun testAmbiguousSchemasFailClosed() {
        val loader = MockClassLoader(
            mapOf(
                "com.google.protobuf.nano.MessageNano" to FakeMessageNano::class.java,
                "t64.b" to FakeHostT64B::class.java,
                "zh5.b" to FakeHostZh5B::class.java
            )
        )
        assertNull(PetCareProtocolClient.findFeedPbClass(loader))
    }

    @Test
    fun testWireFields_selfPet() {
        val loader = MockClassLoader(
            mapOf(
                "com.google.protobuf.nano.MessageNano" to FakeMessageNano::class.java,
                "t64.b" to FakeHostT64B::class.java
            )
        )
        val client = PetCareProtocolClient(OidbChannel(loader))
        val bytes = client.tryReflectFeedBody("pet_self", 9990032L, "")
        assertNotNull(bytes)
        assertNull(ProtoWire.firstString(bytes, 1))
        assertEquals("pet_self", ProtoWire.firstString(bytes, 4))
        assertEquals(9990032L, ProtoWire.firstVarint(bytes, 5))
    }

    @Test
    fun testWireFields_friendPet() {
        val loader = MockClassLoader(
            mapOf(
                "com.google.protobuf.nano.MessageNano" to FakeMessageNano::class.java,
                "t64.b" to FakeHostT64B::class.java
            )
        )
        val client = PetCareProtocolClient(OidbChannel(loader))
        val bytes = client.tryReflectFeedBody("pet_friend", 9990032L, "123456")
        assertNotNull(bytes)
        assertEquals("123456", ProtoWire.firstString(bytes, 1))
        assertEquals("pet_friend", ProtoWire.firstString(bytes, 4))
        assertEquals(9990032L, ProtoWire.firstVarint(bytes, 5))
    }

    @Test
    fun testSafeFailWhenNoCandidateMatches() {
        val client = PetCareProtocolClient(OidbChannel(MockClassLoader(emptyMap())))
        var callbackInvoked = false
        var resultCode = 0
        var resultData: ByteArray? = null

        client.feed("pet_none", 9990032L) { code, data, _ ->
            callbackInvoked = true
            resultCode = code
            resultData = data
        }

        assertTrue(callbackInvoked)
        assertEquals(-1, resultCode)
        assertNull(resultData)
    }
}

package com.copilot.qqpet.protocol

import java.io.ByteArrayOutputStream

/**
 * 极简流式 Protobuf 序列化与轻量反序列化工具
 */
class ProtoWire {
    private val output = ByteArrayOutputStream()

    fun writeVarint(tag: Int, value: Long): ProtoWire {
        writeTag(tag, 0)
        writeRawVarint(value)
        return this
    }

    fun writeString(tag: Int, str: String): ProtoWire {
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeTag(tag, 2)
        writeRawVarint(bytes.size.toLong())
        output.write(bytes)
        return this
    }

    fun writeBytes(tag: Int, bytes: ByteArray): ProtoWire {
        writeTag(tag, 2)
        writeRawVarint(bytes.size.toLong())
        output.write(bytes)
        return this
    }

    private fun writeTag(fieldNumber: Int, wireType: Int) {
        writeRawVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeRawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            output.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        output.write(v.toInt())
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    companion object {
        fun message(): ProtoWire = ProtoWire()

        fun firstString(data: ByteArray?, targetField: Int): String? {
            val bytes = firstBytes(data, targetField) ?: return null
            return String(bytes, Charsets.UTF_8)
        }

        fun firstVarint(data: ByteArray?, targetField: Int): Long? {
            if (data == null) return null
            val pos = intArrayOf(0)
            while (pos[0] < data.size) {
                val tag = readVarint(data, pos)
                val field = (tag ushr 3).toInt()
                val wireType = (tag and 7L).toInt()
                if (wireType == 0) {
                    val v = readVarint(data, pos)
                    if (field == targetField) return v
                } else {
                    skipField(data, pos, wireType)
                }
            }
            return null
        }

        fun firstBytes(data: ByteArray?, targetField: Int): ByteArray? {
            if (data == null) return null
            val pos = intArrayOf(0)
            while (pos[0] < data.size) {
                val tag = readVarint(data, pos)
                val field = (tag ushr 3).toInt()
                val wireType = (tag and 7L).toInt()
                if (wireType == 2) {
                    val length = readVarint(data, pos).toInt()
                    val start = pos[0]
                    pos[0] += length
                    if (field == targetField && start + length <= data.size) {
                        val result = ByteArray(length)
                        System.arraycopy(data, start, result, 0, length)
                        return result
                    }
                } else {
                    skipField(data, pos, wireType)
                }
            }
            return null
        }

        fun firstFloat(data: ByteArray?, targetField: Int): Float? {
            if (data == null) return null
            val pos = intArrayOf(0)
            try {
                while (pos[0] < data.size) {
                    val tag = readVarint(data, pos)
                    val field = (tag ushr 3).toInt()
                    val wireType = (tag and 7L).toInt()
                    if (wireType == 5) {
                        val start = pos[0]
                        pos[0] += 4
                        if (field == targetField && start + 4 <= data.size) {
                            val bits = (data[start].toInt() and 0xFF) or
                                ((data[start + 1].toInt() and 0xFF) shl 8) or
                                ((data[start + 2].toInt() and 0xFF) shl 16) or
                                ((data[start + 3].toInt() and 0xFF) shl 24)
                            return Float.fromBits(bits)
                        }
                    } else if (wireType == 0) {
                        val v = readVarint(data, pos)
                        if (field == targetField) return v.toFloat()
                    } else {
                        skipField(data, pos, wireType)
                    }
                }
            } catch (_: Throwable) {}
            return null
        }

        fun allBytes(data: ByteArray?, targetField: Int): List<ByteArray> {
            if (data == null) return emptyList()
            val list = mutableListOf<ByteArray>()
            val pos = intArrayOf(0)
            try {
                while (pos[0] < data.size) {
                    val tag = readVarint(data, pos)
                    val field = (tag ushr 3).toInt()
                    val wireType = (tag and 7L).toInt()
                    if (wireType == 2) {
                        val length = readVarint(data, pos).toInt()
                        val start = pos[0]
                        pos[0] += length
                        if (field == targetField && start + length <= data.size) {
                            val result = ByteArray(length)
                            System.arraycopy(data, start, result, 0, length)
                            list.add(result)
                        }
                    } else {
                        skipField(data, pos, wireType)
                    }
                }
            } catch (_: Throwable) {}
            return list
        }

        fun dumpFields(data: ByteArray?): String {
            if (data == null) return "null"
            val sb = StringBuilder()
            val pos = intArrayOf(0)
            try {
                while (pos[0] < data.size) {
                    val tag = readVarint(data, pos)
                    val field = (tag ushr 3).toInt()
                    val wireType = (tag and 7L).toInt()
                    when (wireType) {
                        0 -> {
                            val v = readVarint(data, pos)
                            sb.append(" [t$field(v)=$v]")
                        }
                        1 -> {
                            pos[0] += 8
                            sb.append(" [t$field(64b)]")
                        }
                        2 -> {
                            val len = readVarint(data, pos).toInt()
                            val start = pos[0]
                            pos[0] += len
                            val str = try { String(data, start, len, Charsets.UTF_8) } catch (_: Throwable) { "" }
                            val isPrintable = str.isNotEmpty() && str.all { it >= ' ' }
                            if (isPrintable) {
                                sb.append(" [t$field(s)='$str']")
                            } else {
                                sb.append(" [t$field(b,len=$len)]")
                            }
                        }
                        5 -> {
                            val start = pos[0]
                            pos[0] += 4
                            if (start + 4 <= data.size) {
                                val bits = (data[start].toInt() and 0xFF) or
                                    ((data[start + 1].toInt() and 0xFF) shl 8) or
                                    ((data[start + 2].toInt() and 0xFF) shl 16) or
                                    ((data[start + 3].toInt() and 0xFF) shl 24)
                                sb.append(" [t$field(f)=${Float.fromBits(bits)}]")
                            } else {
                                sb.append(" [t$field(32b)]")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                sb.append(" (err: ${t.message})")
            }
            return sb.toString()
        }

        fun extractAllStrings(data: ByteArray?, maxDepth: Int = 4): List<String> {
            if (data == null || maxDepth < 0) return emptyList()
            val result = mutableListOf<String>()
            val pos = intArrayOf(0)
            try {
                while (pos[0] < data.size) {
                    val tag = readVarint(data, pos)
                    val wireType = (tag and 7L).toInt()
                    if (wireType == 2) {
                        val len = readVarint(data, pos).toInt()
                        val start = pos[0]
                        pos[0] += len
                        if (len > 0 && start + len <= data.size) {
                            val sub = ByteArray(len)
                            System.arraycopy(data, start, sub, 0, len)
                            val str = try { String(sub, Charsets.UTF_8) } catch (_: Throwable) { "" }
                            val isReadable = str.isNotEmpty() && str.none { it < ' ' && it != '\n' && it != '\r' && it != '\t' } && !str.contains('\uFFFD')
                            if (isReadable) {
                                result.add(str)
                            }
                            if (maxDepth > 0) {
                                result.addAll(extractAllStrings(sub, maxDepth - 1))
                            }
                        }
                    } else {
                        skipField(data, pos, wireType)
                    }
                }
            } catch (_: Throwable) {}
            return result
        }

        private fun skipField(data: ByteArray, pos: IntArray, wireType: Int) {
            when (wireType) {
                0 -> readVarint(data, pos)
                1 -> pos[0] += 8
                2 -> {
                    val len = readVarint(data, pos).toInt()
                    pos[0] += len
                }
                5 -> pos[0] += 4
                else -> throw IllegalArgumentException("未知的 wireType: $wireType")
            }
        }

        private fun readVarint(data: ByteArray, pos: IntArray): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos[0] >= data.size) {
                    throw IllegalArgumentException("数据流提前结束")
                }
                val b = data[pos[0]++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) {
                    return result
                }
                shift += 7
            }
            throw IllegalArgumentException("Varint 溢出")
        }
    }
}

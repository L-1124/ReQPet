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
        private val HEX = "0123456789abcdef".toCharArray()

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

        fun outline(data: ByteArray?, maxChars: Int = 900, maxDepth: Int = 2, maxCount: Int = 24): String {
            if (data == null) return "null"
            val sb = StringBuilder()
            try {
                appendOutline(data, sb, 0, maxChars, maxDepth, maxCount)
            } catch (t: Throwable) {
                sb.append(" err=").append(t.message)
            }
            return if (sb.length <= maxChars) sb.toString() else sb.substring(0, maxChars)
        }

        private fun appendOutline(
            data: ByteArray,
            sb: StringBuilder,
            depth: Int,
            maxChars: Int,
            maxDepth: Int,
            maxCount: Int
        ) {
            if (depth > maxDepth || sb.length >= maxChars) return
            val pos = intArrayOf(0)
            var count = 0
            while (pos[0] < data.size && sb.length < maxChars && count < maxCount) {
                val tag = readVarint(data, pos)
                val field = (tag ushr 3).toInt()
                val wireType = (tag and 7L).toInt()
                count++
                when (wireType) {
                    0 -> sb.append(" f").append(field).append('=').append(readVarint(data, pos))
                    1 -> {
                        val start = pos[0]
                        pos[0] += 8
                        if (start + 8 <= data.size) {
                            sb.append(" f").append(field).append("=i64:").append(littleEndianLong(data, start))
                        } else {
                            sb.append(" f").append(field).append("=i64")
                        }
                    }
                    5 -> {
                        pos[0] += 4
                        sb.append(" f").append(field).append("=i32")
                    }
                    2 -> {
                        val len = readVarint(data, pos).toInt()
                        val start = pos[0]
                        pos[0] += len
                        if (len < 0 || start + len > data.size) return
                        val slice = data.copyOfRange(start, start + len)
                        val text = try { String(slice, Charsets.UTF_8) } catch (_: Throwable) { "" }
                        val readable = text.isNotEmpty() && text.length <= 160 && text.none { it < ' ' }
                        if (readable) {
                            sb.append(" f").append(field).append("=\"").append(text).append('"')
                        } else if (depth < maxDepth && len > 0) {
                            sb.append(" f").append(field).append('{')
                            appendOutline(slice, sb, depth + 1, maxChars, maxDepth, maxCount)
                            sb.append('}')
                        } else {
                            sb.append(" f").append(field).append("=b").append(len)
                        }
                    }
                    else -> return
                }
            }
        }

        /**
         * 按顶层字段拆开，并把解析不了的二进制、64 位数字和像 QQ 号的数字单独列出来。
         * 每一行控制在调用方的单行上限之内。
         */
        fun suspiciousLines(data: ByteArray?): List<String> {
            if (data == null) return listOf("null")
            if (data.isEmpty()) return listOf("空包")
            val lines = mutableListOf<String>()
            val pos = intArrayOf(0)
            try {
                while (pos[0] < data.size && lines.size < 80) {
                    val tag = readVarint(data, pos)
                    val field = (tag ushr 3).toInt()
                    val wire = (tag and 7L).toInt()
                    when (wire) {
                        0 -> {
                            val value = readVarint(data, pos)
                            lines.add("f$field=$value${uinMark(value)}")
                        }
                        1 -> {
                            val start = pos[0]
                            pos[0] += 8
                            if (start + 8 > data.size) break
                            val value = littleEndianLong(data, start)
                            lines.add("f$field=i64:$value${uinMark(value)} hex=${hexHead(data, start, 8)}")
                        }
                        5 -> {
                            val start = pos[0]
                            pos[0] += 4
                            if (start + 4 > data.size) break
                            lines.add("f$field=i32 hex=${hexHead(data, start, 4)}")
                        }
                        2 -> {
                            val len = readVarint(data, pos).toInt()
                            if (len < 0 || pos[0] + len > data.size) {
                                lines.add("f$field 长度异常")
                                break
                            }
                            val slice = data.copyOfRange(pos[0], pos[0] + len)
                            pos[0] += len
                            lines.add(describeSlice("f$field", slice))
                            collectOpaque("f$field", slice, lines, 1)
                        }
                        else -> {
                            lines.add("f$field wire=$wire 停止")
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                lines.add("解析中断 ${t.message ?: t.javaClass.simpleName}")
            }
            val ascii = asciiDigitRuns(data).filter { looksLikeUin(it.toLongOrNull() ?: 0L) }.distinct()
            lines.add("整包${data.size}字节 原文数字=${ascii.joinToString(",")}")
            return lines
        }

        private fun describeSlice(path: String, slice: ByteArray): String {
            if (slice.isEmpty()) return "$path 空"
            val digits = asciiDigitRuns(slice).filter { looksLikeUin(it.toLongOrNull() ?: 0L) }.distinct()
            val text = try { String(slice, Charsets.UTF_8) } catch (_: Throwable) { "" }
            val readable = text.isNotEmpty() && text.none { it < ' ' }
            val body = when {
                readable && text.length <= 500 -> "\"$text\""
                readable -> "文本${text.length}字 \"${text.take(220)}\" 数字=${digits.joinToString(",")}"
                else -> {
                    val tree = outline(slice, 3200, 4, 40)
                    val opaque = tree.contains("=b") || tree.contains("{}") || tree.length < 12
                    val hex = if (opaque) " hex=${hexHead(slice, 0, 96)}" else ""
                    "$tree$hex 数字=${digits.joinToString(",")}"
                }
            }
            return "$path b${slice.size} $body"
        }

        private fun collectOpaque(path: String, data: ByteArray, lines: MutableList<String>, depth: Int) {
            if (depth > 4 || lines.size >= 80) return
            val pos = intArrayOf(0)
            try {
                while (pos[0] < data.size && lines.size < 80) {
                    val tag = readVarint(data, pos)
                    val field = (tag ushr 3).toInt()
                    val wire = (tag and 7L).toInt()
                    val here = "$path.$field"
                    when (wire) {
                        0 -> {
                            val value = readVarint(data, pos)
                            if (looksLikeUin(value)) lines.add("$here=$value 疑似号码")
                        }
                        1 -> {
                            val start = pos[0]
                            pos[0] += 8
                            if (start + 8 > data.size) return
                            val value = littleEndianLong(data, start)
                            lines.add("$here=i64:$value${uinMark(value)} hex=${hexHead(data, start, 8)}")
                        }
                        5 -> pos[0] += 4
                        2 -> {
                            val len = readVarint(data, pos).toInt()
                            if (len < 0 || pos[0] + len > data.size) return
                            val start = pos[0]
                            pos[0] += len
                            if (len == 0) continue
                            val slice = data.copyOfRange(start, start + len)
                            val text = try { String(slice, Charsets.UTF_8) } catch (_: Throwable) { "" }
                            val readable = text.isNotEmpty() && text.none { it < ' ' }
                            val tree = if (readable) "" else outline(slice, 400, 2, 12)
                            val opaque = !readable && (tree.isEmpty() || tree.contains("=b") || tree.contains("{}") || tree.startsWith(" err"))
                            if (opaque || (!readable && len >= 24)) {
                                val digits = asciiDigitRuns(slice).filter { (it.toLongOrNull() ?: 0L).let { n -> n in 100_000L..9_999_999_999L } }.distinct()
                                lines.add("$here 可疑 b$len hex=${hexHead(slice, 0, 96)} 数字=${digits.joinToString(",")} 树=${tree.take(180)}")
                            } else if (readable && text.length <= 80 && (text.any { it.code > 127 } || text.any { it.isDigit() })) {
                                lines.add("$here \"$text\"")
                            }
                            if (!readable) collectOpaque(here, slice, lines, depth + 1)
                        }
                        else -> return
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun uinMark(value: Long): String = if (looksLikeUin(value)) " 疑似号码" else ""

        private fun littleEndianLong(data: ByteArray, start: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = value or ((data[start + i].toLong() and 0xFF) shl (8 * i))
            }
            return value
        }

        private fun hexHead(data: ByteArray, start: Int, count: Int): String {
            val end = minOf(data.size, start + count)
            val sb = StringBuilder((end - start) * 2)
            for (i in start until end) {
                val v = data[i].toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        /**
         * 雇佣详情排查：顶层字段清单，以及路径上像 QQ 号的数字、短文本。
         * 时间戳（约 16 亿到 20 亿）不记入号码。
         */
        fun hireScan(data: ByteArray?): String {
            if (data == null) return "null"
            val census = StringBuilder()
            val pos = intArrayOf(0)
            try {
                while (pos[0] < data.size && census.length < 360) {
                    val tag = readVarint(data, pos)
                    val field = (tag ushr 3).toInt()
                    val wire = (tag and 7L).toInt()
                    when (wire) {
                        0 -> census.append(' ').append(field).append('=').append(readVarint(data, pos))
                        1 -> {
                            pos[0] += 8
                            census.append(' ').append(field).append(":i64")
                        }
                        5 -> {
                            pos[0] += 4
                            census.append(' ').append(field).append(":i32")
                        }
                        2 -> {
                            val len = readVarint(data, pos).toInt()
                            if (len < 0 || pos[0] + len > data.size) break
                            census.append(' ').append(field).append(":b").append(len)
                            pos[0] += len
                        }
                        else -> break
                    }
                }
            } catch (_: Throwable) {
            }
            val hits = mutableListOf<String>()
            collectHireHits(data, "", hits, 0)
            val ascii = asciiDigitRuns(data).filter { looksLikeUin(it.toLongOrNull() ?: 0L) }
            return "顶层$census 号码=${hits.joinToString(",")} 原文数字=${ascii.distinct().joinToString(",")}"
        }

        private fun collectHireHits(data: ByteArray, path: String, hits: MutableList<String>, depth: Int) {
            if (depth > 5 || hits.size >= 24) return
            val pos = intArrayOf(0)
            try {
                while (pos[0] < data.size && hits.size < 24) {
                    val tag = readVarint(data, pos)
                    val field = (tag ushr 3).toInt()
                    val wire = (tag and 7L).toInt()
                    val here = if (path.isEmpty()) field.toString() else "$path.$field"
                    when (wire) {
                        0 -> {
                            val value = readVarint(data, pos)
                            if (looksLikeUin(value)) hits.add("$here=$value")
                        }
                        1 -> pos[0] += 8
                        5 -> pos[0] += 4
                        2 -> {
                            val len = readVarint(data, pos).toInt()
                            if (len < 0 || pos[0] + len > data.size) return
                            val start = pos[0]
                            pos[0] += len
                            if (len == 0) continue
                            val slice = data.copyOfRange(start, start + len)
                            val text = try { String(slice, Charsets.UTF_8) } catch (_: Throwable) { "" }
                            val readable = text.isNotEmpty() && text.none { it < ' ' }
                            if (readable) {
                                val digits = Regex("\\d{8,10}").findAll(text).map { it.value }.filter { looksLikeUin(it.toLongOrNull() ?: 0L) }
                                digits.forEach { hits.add("$here\"$it\"") }
                                if (text.length in 2..16 && !text.contains("http") && !text.startsWith("#") && text.any { it.code > 127 }) {
                                    hits.add("$here:$text")
                                }
                            }
                            collectHireHits(slice, here, hits, depth + 1)
                        }
                        else -> return
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun looksLikeUin(value: Long): Boolean {
            if (value !in 10_000_000L..9_999_999_999L) return false
            return value !in 1_600_000_000L..2_000_000_000L
        }

        private fun asciiDigitRuns(data: ByteArray): List<String> {
            val runs = mutableListOf<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.length in 8..10) runs.add(current.toString())
                current.setLength(0)
            }
            for (b in data) {
                if (b.toInt() in 48..57) current.append(b.toInt().toChar()) else flush()
            }
            flush()
            return runs
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

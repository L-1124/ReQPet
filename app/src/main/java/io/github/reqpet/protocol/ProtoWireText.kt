package io.github.reqpet.protocol

/**
 * ProtoWire 的文本诊断扩展：雇佣详情排查与全量字符串抽取
 *
 * 从 ProtoWire 拆出：这些函数只服务于调试日志，不参与协议编解码主链路
 */
object ProtoWireText {

    /**
     * 雇佣详情排查：顶层字段清单，以及路径上像 QQ 号的数字、短文本。
     * 时间戳（约 16 亿到 20 亿）不记入号码。
     */
    fun hireScan(data: ByteArray?): String {
        if (data == null) return "null"
        val census = StringBuilder()
        val pos = intArrayOf(0)
        try {
            while (pos[0] < data.size) {
                val tag = readVarint(data, pos)
                val fieldNumber = (tag ushr 3).toInt()
                val wireType = (tag and 7L).toInt()
                when (wireType) {
                    2 -> {
                        val len = readVarint(data, pos).toInt()
                        val start = pos[0]
                        pos[0] += len
                        if (len > 0 && start + len <= data.size) {
                            val sub = data.copyOfRange(start, start + len)
                            census.append("[field ").append(fieldNumber).append("] bytes(").append(len).append(")")
                            val runs = asciiDigitRuns(sub)
                            if (runs.isNotEmpty()) {
                                census.append(" digits=").append(runs.joinToString(","))
                            }
                            val strings = extractAllStrings(sub)
                            if (strings.isNotEmpty()) {
                                census.append(" strings=").append(strings.take(8).joinToString("|"))
                            }
                            census.append('\n')
                        }
                    }

                    0 -> census.append("[field ").append(fieldNumber).append("] varint=")
                        .append(readVarint(data, pos)).append('\n')

                    else -> skipField(data, pos, wireType)
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        return census.toString().ifEmpty { "empty" }
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
                        val str = try {
                            String(sub, Charsets.UTF_8)
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            ""
                        }
                        val isReadable =
                            str.isNotEmpty() && str.none { it < ' ' && it != '\n' && it != '\r' && it != '\t' } && !str.contains(
                                '\uFFFD'
                            )
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
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        return result
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

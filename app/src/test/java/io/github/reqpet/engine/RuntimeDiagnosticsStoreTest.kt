package io.github.reqpet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RuntimeDiagnosticsStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val run = "0123456789abcdef0123456789abcdef"

    @Test
    fun `records expose UTC milliseconds run monotonic clock and persisted sequence`() {
        val directory = temporary.newFolder()
        val buffer = RuntimeDiagnosticBuffer(run, wallClock = { 1_700_000_000_123L }, monotonicClock = { 456L })
        RuntimeDiagnosticFileWriter(directory).use { writer ->
            buffer.writeControl(writer, "run_start", "schema" to 1, "pid" to 123)
            buffer.event("scheduler_tick", arrayOf("pending" to 2))
            assertTrue(buffer.writeNext(writer))
        }
        val records = records(directory)
        assertEquals(2, records.size)
        assertEquals("2023-11-14T22:13:20.123Z", records[0]["ts"])
        assertEquals(run, records[0]["run"])
        assertEquals("456", records[0]["mono_ms"])
        assertEquals("1", records[0]["seq"])
        assertEquals("run_start", records[0]["event"])
        assertEquals("1", records[0]["schema"])
        assertEquals("123", records[0]["pid"])
        assertEquals("2", records[1]["seq"])
        assertEquals("2", records[1]["pending"])
    }

    @Test
    fun `quotes backslashes controls and unicode cannot create extra records or fields`() {
        val value = "quote\" slash\\ newline\nreturn\rtab\t nul\u0000 del\u007f 中文\uD83D\uDE00"
        val line = record("escaped", "detail" to value, "tail" to "kept")
        assertEquals(1, line.count { it == '\n' })
        assertTrue(line.dropLast(1).all { it.code in 32..126 })
        val fields = parse(line)
        assertEquals(value, fields["detail"])
        assertEquals("kept", fields["tail"])
        assertEquals(7, fields.size)
    }

    @Test
    fun `forged keys duplicate keys and metadata cannot corrupt context`() {
        val fields =
            parse(record("bad\nevent", "seq" to 999, "run" to "forged", "a b" to "injected", "ok" to 1, "ok" to 2))
        assertEquals("invalid_event", fields["event"])
        assertEquals(run, fields["run"])
        assertEquals("1", fields["seq"])
        assertEquals("1", fields["ok"])
        assertEquals("4", fields["fields_truncated"])
    }

    @Test
    fun `salted identifiers are stable in run different across runs and never stored raw`() {
        val first = RuntimeDiagnosticIds(ByteArray(32) { 1 })
        val second = RuntimeDiagnosticIds(ByteArray(32) { 2 })
        val raw = "1234567890123456789"
        val token = first.id(raw)
        assertTrue(Regex("id_[0-9a-f]{32}").matches(token))
        assertEquals(token, first.id(raw))
        assertNotEquals(token, second.id(raw))
        assertNotEquals(token, first.id("different"))
        assertEquals("none", first.id(null))
        assertEquals("none", first.id(""))
        val line = record(
            "privacy", "uin" to raw, "active_uin" to token, "pet_id" to 123L,
            "storyId" to raw, "nickname" to "private-name", "packet" to byteArrayOf(1, 2),
            "error" to "sensitive failure", "account" to "none"
        )
        val fields = parse(line)
        assertFalse(line.contains(raw))
        assertFalse(line.contains("private-name"))
        assertFalse(line.contains("sensitive failure"))
        assertEquals("[redacted]", fields["uin"])
        assertEquals(token, fields["active_uin"])
        assertEquals("[redacted]", fields["pet_id"])
        assertEquals("[redacted]", fields["storyId"])
        assertEquals("none", fields["account"])
    }

    @Test
    fun `arbitrary values are never asked to stringify`() {
        val hostile = object {
            override fun toString(): String = error("must not be called")
        }
        val fields = parse(
            record(
                "types", "hostile" to hostile, "failure" to IllegalStateException("private"),
                "bytes" to byteArrayOf(1, 2), "enabled" to true, "missing" to null
            )
        )
        assertEquals("[redacted]", fields["hostile"])
        assertEquals("[redacted]", fields["failure"])
        assertEquals("[redacted]", fields["bytes"])
        assertEquals("true", fields["enabled"])
        assertEquals("null", fields["missing"])
    }

    @Test
    fun `error fields expose only class labels and numeric codes never free text`() {
        val fields = parse(
            record(
                "failure", "error_type" to "java.io.IOException", "error_class" to "private failure",
                "error_code" to -1, "error_detail" to "private-detail", "exception" to "private-exception",
                "payload" to "private-payload", "body" to "private-body"
            )
        )
        assertEquals("java.io.IOException", fields["error_type"])
        assertEquals("-1", fields["error_code"])
        for (key in listOf("error_class", "error_detail", "exception", "payload", "body")) {
            assertEquals("[redacted]", fields[key])
        }
    }

    @Test
    fun `long values and many escaped fields stay bounded and retain truncation markers`() {
        val huge = "\u0001".repeat(10_000)
        val fields = Array(100) { "field$it" to huge }
        val line = record("bounded", *fields)
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= RuntimeDiagnosticFormat.MAX_RECORD_BYTES)
        val parsed = parse(line)
        assertTrue(parsed["field0"]!!.endsWith("[truncated]"))
        assertTrue(parsed["fields_truncated"]!!.toInt() > 0)
        assertTrue(parsed.keys.count { it.startsWith("field") && it != "fields_truncated" } <= 24)
        val short = parse(record("bounded", "value" to "x".repeat(257)))
        assertEquals("x".repeat(256) + "[truncated]", short["value"])
    }

    @Test
    fun `restart appends completed history and exposes flushed records before close`() {
        val directory = temporary.newFolder()
        val first = record("first")
        val second = record("second")
        RuntimeDiagnosticFileWriter(directory).use { writer ->
            writer.append(first)
            assertEquals(first, File(directory, "runtime.log").readText())
        }
        RuntimeDiagnosticFileWriter(directory).use { it.append(second) }
        assertEquals(first + second, File(directory, "runtime.log").readText())
        assertEquals(listOf("first", "second"), records(directory).map { it["event"] })
    }

    @Test
    fun `exact boundary is retained and next record rotates newest to oldest`() {
        val directory = temporary.newFolder()
        val first = record("one")
        val second = record("two")
        val fileSize = first.toByteArray().size.toLong() * 2
        RuntimeDiagnosticFileWriter(directory, maxFileBytes = fileSize).use { writer ->
            writer.append(first)
            writer.append(second)
            assertFalse(File(directory, "runtime.1.log").exists())
            writer.append(record("tri"))
        }
        assertEquals(first + second, File(directory, "runtime.1.log").readText())
        assertEquals(listOf("tri"), records(directory).map { it["event"] })
        assertTrue(File(directory, "runtime.log").length() <= fileSize)
    }

    @Test
    fun `rotation preserves three ordered archives including across restarts`() {
        val directory = temporary.newFolder()
        val fileSize = record("e1").toByteArray().size.toLong()
        for (index in 1..6) {
            RuntimeDiagnosticFileWriter(directory, maxFileBytes = fileSize).use { it.append(record("e$index")) }
        }
        assertEquals("e6", records(directory)[0]["event"])
        assertEquals("e5", records(directory, "runtime.1.log")[0]["event"])
        assertEquals("e4", records(directory, "runtime.2.log")[0]["event"])
        assertEquals("e3", records(directory, "runtime.3.log")[0]["event"])
        assertFalse(File(directory, "runtime.4.log").exists())
        val logFiles = directory.listFiles()!!.filter { it.name.endsWith(".log") }
        assertEquals(4, logFiles.size)
        assertTrue(logFiles.all { it.length() <= fileSize })
    }

    @Test
    fun `incomplete old tail is preserved separately rather than corrupting new record`() {
        val directory = temporary.newFolder()
        File(directory, "runtime.log").writeText("interrupted record")
        RuntimeDiagnosticFileWriter(directory).use { it.append(record("fresh")) }
        assertEquals("interrupted record", File(directory, "runtime.1.log").readText())
        assertEquals("fresh", records(directory)[0]["event"])
    }

    @Test
    fun `full queue rejects producer records and emits exact loss count into retained output`() {
        val directory = temporary.newFolder()
        val buffer = RuntimeDiagnosticBuffer(run, capacity = 2, wallClock = { 0L }, monotonicClock = { 9L })
        buffer.event("first")
        buffer.event("second")
        repeat(5) { buffer.event("lost") }
        RuntimeDiagnosticFileWriter(directory).use { writer ->
            buffer.writeControl(writer, "run_start", "schema" to 1)
            assertTrue(buffer.writeNext(writer))
            assertTrue(buffer.writeNext(writer))
            assertFalse(buffer.writeNext(writer))
            buffer.event("resumed")
            assertTrue(buffer.writeNext(writer))
        }
        val records = records(directory)
        assertEquals(listOf("run_start", "records_dropped", "first", "second", "resumed"), records.map { it["event"] })
        assertEquals("5", records[1]["count"])
        assertEquals("queue_full", records[1]["reason"])
        assertEquals(listOf("1", "2", "3", "4", "5"), records.map { it["seq"] })
    }

    @Test
    fun `overlapping writers cannot corrupt history and ownership transfers after close`() {
        val directory = temporary.newFolder()
        RuntimeDiagnosticFileWriter(directory).use { first ->
            first.append(record("first"))
            var rejected = false
            try {
                RuntimeDiagnosticFileWriter(directory).use { it.append(record("overlap")) }
            } catch (_: RuntimeDiagnosticWriterBusy) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(listOf("first"), records(directory).map { it["event"] })
            first.append(record("second"))
        }
        RuntimeDiagnosticFileWriter(directory).use { it.append(record("successor")) }
        assertEquals(listOf("first", "second", "successor"), records(directory).map { it["event"] })
    }

    @Test
    fun `failed startup releases ownership for a later valid writer`() {
        val directory = temporary.newFolder()
        val blocked = File(directory, "runtime.log").apply { mkdir() }
        var rejected = false
        try {
            RuntimeDiagnosticFileWriter(directory).close()
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(blocked.delete())
        RuntimeDiagnosticFileWriter(directory).use { it.append(record("recovered")) }
        assertEquals("recovered", records(directory)[0]["event"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writer rejects embedded newlines instead of appending forged records`() {
        RuntimeDiagnosticFileWriter(temporary.newFolder()).use { it.append("one\ntwo\n") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writer rejects oversized records instead of exceeding configured retention`() {
        RuntimeDiagnosticFileWriter(temporary.newFolder(), maxFileBytes = 32).use { it.append(record("large")) }
    }

    private fun record(name: String, vararg fields: Pair<String, Any?>): String = RuntimeDiagnosticFormat.record(
        run, 1L, 0L, 2L, RuntimeDiagnosticFormat.body(name, fields)
    )

    private fun records(directory: File, name: String = "runtime.log"): List<Map<String, String>> =
        File(directory, name).readLines().map { parse("$it\n") }

    /** Parse entire consumer-visible lines, rejecting malformed escapes, delimiters and duplicate keys. */
    private fun parse(line: String): Map<String, String> {
        assertTrue(line.endsWith('\n'))
        val pattern = Regex("([A-Za-z][A-Za-z0-9_]*)=\"((?:[^\"\\\\\\n\\r]|\\\\(?:[\\\\\"nrt]|u[0-9a-f]{4}))*)\"")
        val result = linkedMapOf<String, String>()
        var offset = 0
        for (match in pattern.findAll(line.dropLast(1))) {
            assertEquals(offset, match.range.first)
            val key = match.groupValues[1]
            assertFalse(result.containsKey(key))
            result[key] = unescape(match.groupValues[2])
            offset = match.range.last + 2
        }
        assertEquals(line.length, offset)
        return result
    }

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char != '\\') append(char) else {
                when (val escaped = value[index++]) {
                    '\\', '"' -> append(escaped)
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> {
                        append(value.substring(index, index + 4).toInt(16).toChar())
                        index += 4
                    }

                    else -> error("invalid escape")
                }
            }
        }
    }
}

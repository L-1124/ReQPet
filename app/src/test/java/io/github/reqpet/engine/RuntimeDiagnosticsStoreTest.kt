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

    @Test
    fun `diagnosticErrorFields redacts messages and paths while exposing classes and frames only when detailed`() {
        val directory = temporary.newFolder()
        val buffer = RuntimeDiagnosticBuffer(run, wallClock = { 1000L }, monotonicClock = { 10L })
        val innerCause = IllegalStateException("secret_token=tok_x9y8z7 private inner message")
        val directCause = IllegalArgumentException("secret_uin=uin_v6u5t4 cause message", innerCause)
        val error = RuntimeException("raw_payload=pay_s3r2q1 secret error message", directCause)
        error.stackTrace = arrayOf(
            StackTraceElement("io.github.reqpet.TestService", "executeAction", "TestService.kt", 42)
        )
        directCause.stackTrace = arrayOf(
            StackTraceElement("io.github.reqpet.CauseService", "causeMethod", "CauseService.kt", 84)
        )

        buffer.event("err_off", diagnosticErrorFields(error, detailed = false))
        buffer.event("err_on", diagnosticErrorFields(error, detailed = true))

        RuntimeDiagnosticFileWriter(directory).use { writer ->
            assertTrue(buffer.writeNext(writer))
            assertTrue(buffer.writeNext(writer))
        }

        val rawContent = File(directory, "runtime.log").readText()
        assertFalse(rawContent.contains("secret"))
        assertFalse(rawContent.contains("tok_x9y8z7"))
        assertFalse(rawContent.contains("uin_v6u5t4"))
        assertFalse(rawContent.contains("pay_s3r2q1"))
        assertFalse(rawContent.contains("TestService.kt"))
        assertFalse(rawContent.contains("CauseService.kt"))
        assertFalse(rawContent.contains("message"))

        val records = records(directory)
        val offRecord = records[0]
        assertEquals("err_off", offRecord["event"])
        assertEquals("RuntimeException", offRecord["error_type"])
        assertFalse(offRecord.containsKey("exception_class"))
        assertFalse(offRecord.containsKey("frame_0"))
        assertFalse(offRecord.containsKey("cause_class"))
        assertFalse(offRecord.containsKey("cause_frame_0"))
        assertFalse(offRecord.containsKey("detail_truncated"))

        val onRecord = records[1]
        assertEquals("err_on", onRecord["event"])
        assertEquals("RuntimeException", onRecord["error_type"])
        assertEquals("java.lang.RuntimeException", onRecord["exception_class"])
        assertEquals("io.github.reqpet.TestService.executeAction:42", onRecord["frame_0"])
        assertEquals("java.lang.IllegalArgumentException", onRecord["cause_class"])
        assertEquals("io.github.reqpet.CauseService.causeMethod:84", onRecord["cause_frame_0"])
        assertEquals("true", onRecord["detail_truncated"])
    }

    @Test
    fun `diagnosticErrorFields bounds frames to eight each and sets detail_truncated for deeper cause`() {
        val directory = temporary.newFolder()
        val buffer = RuntimeDiagnosticBuffer(run, wallClock = { 2000L }, monotonicClock = { 20L })
        val thirdCause = Exception("third")
        val secondCause = Exception("second", thirdCause)
        val firstException = Exception("first", secondCause)
        firstException.stackTrace = Array(9) { i ->
            StackTraceElement("io.github.reqpet.TopClass", "topMethod$i", "TopClass.kt", 100 + i)
        }
        secondCause.stackTrace = Array(9) { i ->
            StackTraceElement("io.github.reqpet.CauseClass", "causeMethod$i", "CauseClass.kt", 200 + i)
        }

        val fields = diagnosticErrorFields(firstException, detailed = true)
        val combinedFields = fields + arrayOf("transaction" to "tx_123", "loop" to "loop_main", "source" to "manual")
        buffer.event("err_bounded", combinedFields)

        RuntimeDiagnosticFileWriter(directory).use { writer ->
            assertTrue(buffer.writeNext(writer))
        }
        val logFile = File(directory, "runtime.log")
        assertTrue(logFile.readBytes().size <= RuntimeDiagnosticFormat.MAX_RECORD_BYTES)
        val records = records(directory)
        val record = records[0]
        assertEquals("err_bounded", record["event"])
        assertEquals("Exception", record["error_type"])
        assertEquals("java.lang.Exception", record["exception_class"])
        for (i in 0 until 8) {
            assertEquals("io.github.reqpet.TopClass.topMethod$i:${100 + i}", record["frame_$i"])
            assertEquals("io.github.reqpet.CauseClass.causeMethod$i:${200 + i}", record["cause_frame_$i"])
        }
        assertFalse(record.containsKey("frame_8"))
        assertFalse(record.containsKey("cause_frame_8"))
        assertEquals("true", record["detail_truncated"])
        assertEquals("tx_123", record["transaction"])
        assertEquals("loop_main", record["loop"])
        assertEquals("manual", record["source"])
        assertFalse(record.containsKey("fields_truncated"))
    }

    @Test
    fun `diagnosticErrorFields captures snapshot at enqueue time rather than during drainage`() {
        val directory = temporary.newFolder()
        val buffer = RuntimeDiagnosticBuffer(run, wallClock = { 3000L }, monotonicClock = { 30L })
        val error = IllegalStateException("snapshot test")
        error.stackTrace = arrayOf(StackTraceElement("io.github.reqpet.Snap", "run", "Snap.kt", 1))

        buffer.event("err_first", diagnosticErrorFields(error, detailed = false))
        buffer.event("err_second", diagnosticErrorFields(error, detailed = true))
        buffer.event("err_third", diagnosticErrorFields(error, detailed = false))

        RuntimeDiagnosticFileWriter(directory).use { writer ->
            assertTrue(buffer.writeNext(writer))
            assertTrue(buffer.writeNext(writer))
            assertTrue(buffer.writeNext(writer))
        }

        val records = records(directory)
        assertEquals(3, records.size)
        assertEquals("IllegalStateException", records[0]["error_type"])
        assertFalse(records[0].containsKey("exception_class"))
        assertFalse(records[0].containsKey("frame_0"))

        assertEquals("IllegalStateException", records[1]["error_type"])
        assertEquals("java.lang.IllegalStateException", records[1]["exception_class"])
        assertEquals("io.github.reqpet.Snap.run:1", records[1]["frame_0"])

        assertEquals("IllegalStateException", records[2]["error_type"])
        assertFalse(records[2].containsKey("exception_class"))
        assertFalse(records[2].containsKey("frame_0"))
    }

    @Test
    fun `diagnosticErrorFields safely handles hostile throwable and cyclic causes without propagating exceptions`() {
        val directory = temporary.newFolder()
        val buffer = RuntimeDiagnosticBuffer(run, wallClock = { 4000L }, monotonicClock = { 40L })

        class HostileException : RuntimeException("hostile") {
            override val message: String
                get() = throw AssertionError("disabled mode must not read message")

            override val cause: Throwable
                get() = throw AssertionError("disabled mode must not read cause")

            override fun getStackTrace(): Array<StackTraceElement> {
                throw java.util.concurrent.CancellationException("simulated cancellation during stack reading")
            }
        }

        val hostile = HostileException()
        val offFields = diagnosticErrorFields(hostile, detailed = false)
        buffer.event("hostile_off", offFields)

        val onFields = diagnosticErrorFields(hostile, detailed = true)
        buffer.event("hostile_on", onFields)

        val directSelfCycleCause = object : Throwable("directSelfCycle") {
            override val cause: Throwable
                get() = this
        }
        directSelfCycleCause.stackTrace =
            arrayOf(StackTraceElement("io.github.reqpet.Cycle", "causeRun", "Cycle.kt", 1))
        val directCycleError = Exception("directCycleError", directSelfCycleCause)
        directCycleError.stackTrace = arrayOf(StackTraceElement("io.github.reqpet.Cycle", "errorRun", "Cycle.kt", 2))
        val directCycleFields = diagnosticErrorFields(directCycleError, detailed = true)
        buffer.event("direct_cycle_event", directCycleFields)

        class TopSelfCycleThrowable : Throwable("topSelfCycle") {
            override val cause: Throwable
                get() = this
        }

        val topSelfCycle = TopSelfCycleThrowable()
        topSelfCycle.stackTrace = arrayOf(StackTraceElement("io.github.reqpet.Cycle", "topRun", "Cycle.kt", 3))
        val topCycleFields = diagnosticErrorFields(topSelfCycle, detailed = true)
        buffer.event("top_cycle_event", topCycleFields)

        val anonymousError = object : Throwable("anon") {}
        val anonFields = diagnosticErrorFields(anonymousError, detailed = false)
        assertEquals("", anonFields.first { it.first == "error_type" }.second)
        buffer.event("anon_event", anonFields)

        RuntimeDiagnosticFileWriter(directory).use { writer ->
            assertTrue(buffer.writeNext(writer))
            assertTrue(buffer.writeNext(writer))
            assertTrue(buffer.writeNext(writer))
            assertTrue(buffer.writeNext(writer))
            assertTrue(buffer.writeNext(writer))
        }

        val records = records(directory)
        val offRecord = records[0]
        assertEquals("HostileException", offRecord["error_type"])
        assertFalse(offRecord.containsKey("detail_unavailable"))
        assertFalse(offRecord.containsKey("frame_0"))

        val onRecord = records[1]
        assertEquals("HostileException", onRecord["error_type"])
        assertEquals("true", onRecord["detail_unavailable"])
        assertFalse(onRecord.containsKey("frame_0"))
        assertFalse(onRecord.containsKey("exception_class"))

        val directCycleRecord = records[2]
        assertEquals("Exception", directCycleRecord["error_type"])
        assertEquals("java.lang.Exception", directCycleRecord["exception_class"])
        assertTrue(directCycleRecord.containsKey("cause_class"))
        assertEquals("true", directCycleRecord["detail_truncated"])

        val topCycleRecord = records[3]
        assertEquals("TopSelfCycleThrowable", topCycleRecord["error_type"])
        assertFalse(topCycleRecord.containsKey("cause_class"))
        assertEquals("false", topCycleRecord["detail_truncated"])

        val anonRecord = records[4]
        assertEquals("[redacted]", anonRecord["error_type"])
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

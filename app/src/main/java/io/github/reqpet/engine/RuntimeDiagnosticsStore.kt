package io.github.reqpet.engine

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Salt never leaves memory; no raw identifiers or unbounded identifier cache are retained. */
internal class RuntimeDiagnosticIds(private val salt: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) {
    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    fun id(value: String?): String {
        if (value.isNullOrEmpty()) return "none"
        val hash = requireNotNull(digest.get()).run {
            reset()
            update(salt)
            digest(value.toByteArray(Charsets.UTF_8))
        }
        val hex = "0123456789abcdef"
        return buildString(35) {
            append("id_")
            for (index in 0 until 16) {
                val byte = hash[index].toInt() and 0xff
                append(hex[byte ushr 4])
                append(hex[byte and 15])
            }
        }
    }
}

/** ASCII logfmt, quoted values, escaped controls/unicode; reserved metadata cannot be overridden. */
internal object RuntimeDiagnosticFormat {
    const val MAX_RECORD_BYTES = 8 * 1024
    private const val MAX_BODY_BYTES = MAX_RECORD_BYTES - 256
    private const val MAX_FIELDS = 24
    private val runPattern = Regex("[0-9a-f]{32}")
    private const val MAX_VALUE_CHARS = 256
    private const val TRUNCATED = "[truncated]"
    private val keyPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,47}")
    private val tokenPattern = Regex("id_[0-9a-f]{32}")
    private val classPattern = Regex("[A-Za-z][A-Za-z0-9_.\\$]*")
    private val reserved = setOf("ts", "run", "mono_ms", "seq", "event", "fields_truncated")
    private val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun body(name: String, fields: Array<out Pair<String, Any?>>): String {
        val output = StringBuilder("event=\"")
        output.append(if (keyPattern.matches(name)) name else "invalid_event").append('"')
        val seen = HashSet<String>()
        var skipped = 0
        for (index in fields.indices) {
            if (index >= MAX_FIELDS) {
                skipped += fields.size - index
                break
            }
            val (key, value) = fields[index]
            if (!keyPattern.matches(key) || key in reserved || !seen.add(key)) {
                skipped++
                continue
            }
            // Reserve enough space to close the value and report omitted fields, never cut a record.
            val available = MAX_BODY_BYTES - output.length - key.length - 4 - 64
            if (available < TRUNCATED.length) {
                skipped += fields.size - index
                break
            }
            output.append(' ').append(key).append("=\"")
            appendValue(output, safeValue(key, value), available)
            output.append('"')
        }
        if (skipped != 0) output.append(" fields_truncated=\"").append(skipped).append('"')
        return output.toString()
    }

    fun record(runId: String, sequence: Long, wallMs: Long, monoMs: Long, body: String): String {
        require(runPattern.matches(runId))
        return "ts=\"${timestamp.format(Instant.ofEpochMilli(wallMs))}\" run=\"$runId\" mono_ms=\"$monoMs\" seq=\"$sequence\" $body\n"
    }

    private fun safeValue(key: String, value: Any?): String {
        val normalized = key.lowercase().replace("_", "")
        val classLabel = value is String && value.length <= 96 && classPattern.matches(value)
        if (normalized.contains("nickname") || normalized == "nick" || normalized == "petname" ||
            normalized.contains("packet") || normalized.contains("payload") || normalized.contains("message") ||
            normalized == "log" || normalized == "data" || normalized == "body" || normalized == "raw" ||
            normalized.contains("stacktrace")
        ) return "[redacted]"
        if (normalized.contains("error") || normalized == "err" || normalized.contains("exception")) {
            return when {
                normalized.endsWith("code") && (value is Int || value is Long) -> value.toString()
                (normalized.endsWith("type") || normalized.endsWith("class")) && classLabel -> value as String
                else -> "[redacted]"
            }
        }
        if (normalized.contains("uin") || normalized.endsWith("account") || normalized.endsWith("accountid") ||
            normalized == "pet" || normalized.endsWith("petid") || normalized == "story" ||
            normalized.endsWith("storyid") || normalized == "pendingid"
        ) {
            return if (value is String && (value == "none" || tokenPattern.matches(value))) value else "[redacted]"
        }
        return when (value) {
            null -> "null"
            is String -> value
            is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
            is Enum<*> -> value.name
            else -> "[redacted]" // Never invoke arbitrary toString (including packets and Throwable).
        }
    }

    private fun appendValue(output: StringBuilder, value: String, maxEncodedChars: Int) {
        val limit = minOf(value.length, MAX_VALUE_CHARS)
        var encoded = 0
        var index = 0
        while (index < limit) {
            val char = value[index]
            val size = when {
                char == '\\' || char == '"' || char == '\n' || char == '\r' || char == '\t' -> 2
                char < ' ' || char > '~' -> 6
                else -> 1
            }
            val needsMarker = index + 1 < value.length
            if (encoded + size + (if (needsMarker) TRUNCATED.length else 0) > maxEncodedChars) break
            when (char) {
                '\\' -> output.append("\\\\")
                '"' -> output.append("\\\"")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> if (char < ' ' || char > '~') {
                    output.append("\\u")
                    val hex = "0123456789abcdef"
                    for (shift in 12 downTo 0 step 4) output.append(hex[(char.code ushr shift) and 15])
                } else output.append(char)
            }
            encoded += size
            index++
        }
        if (index < value.length) output.append(TRUNCATED)
    }
}

/** Bounded producers, one consumer. Sequence is the persisted order, including overflow records. */
internal class RuntimeDiagnosticBuffer(
    private val runId: String,
    capacity: Int = 256,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val monotonicClock: () -> Long
) {
    private data class Event(val wallMs: Long, val monoMs: Long, val body: String)

    private val queue = ArrayBlockingQueue<Event>(capacity)
    private val dropped = AtomicLong()
    private var sequence = 0L // Writer-thread confined.

    fun event(name: String, fields: Array<out Pair<String, Any?>> = emptyArray()) {
        val event = Event(wallClock(), monotonicClock(), RuntimeDiagnosticFormat.body(name, fields))
        if (!queue.offer(event)) dropped.incrementAndGet()
    }

    fun writeControl(writer: RuntimeDiagnosticFileWriter, name: String, vararg fields: Pair<String, Any?>) {
        write(writer, Event(wallClock(), monotonicClock(), RuntimeDiagnosticFormat.body(name, fields)))
    }

    fun writeNext(writer: RuntimeDiagnosticFileWriter, timeoutMs: Long = 0L): Boolean {
        val event = if (timeoutMs == 0L) queue.poll() else queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        val lost = dropped.getAndSet(0L)
        if (lost != 0L) writeControl(writer, "records_dropped", "count" to lost, "reason" to "queue_full")
        if (event != null) write(writer, event)
        return event != null || lost != 0L
    }

    private fun write(writer: RuntimeDiagnosticFileWriter, event: Event) {
        writer.append(RuntimeDiagnosticFormat.record(runId, ++sequence, event.wallMs, event.monoMs, event.body))
    }
}

internal class RuntimeDiagnosticWriterBusy : IOException("diagnostics writer already active")

/** runtime.log is newest; .1, .2, .3 are progressively older. Reopens append, never wipes a run. */
internal class RuntimeDiagnosticFileWriter(
    private val directory: File,
    private val maxFileBytes: Long = 2L * 1024 * 1024,
    private val rotatedFiles: Int = 3
) : Closeable {
    private val current = File(directory, "runtime.log")
    private var output: FileOutputStream? = null
    private var size = 0L
    private var ownershipFile: RandomAccessFile? = null
    private var ownershipLock: FileLock? = null

    init {
        require(maxFileBytes > 0 && rotatedFiles >= 1)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("diagnostics directory unavailable")
        try {
            ownershipFile = RandomAccessFile(File(directory, ".writer.lock"), "rw")
            ownershipLock = try {
                ownershipFile!!.channel.tryLock() ?: throw RuntimeDiagnosticWriterBusy()
            } catch (_: OverlappingFileLockException) {
                throw RuntimeDiagnosticWriterBusy()
            }
            // 文件锁防止多个写入器同时追加或轮转同一组文件。
            if (current.length() > 0 && RandomAccessFile(current, "r").use { file ->
                    file.seek(file.length() - 1)
                    file.read() != '\n'.code
                }) rotate()
            open()
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    fun append(record: String) {
        require(record.endsWith('\n') && record.indexOf('\n') == record.lastIndex)
        val bytes = record.toByteArray(Charsets.UTF_8)
        require(bytes.size <= RuntimeDiagnosticFormat.MAX_RECORD_BYTES && bytes.size <= maxFileBytes)
        if (size + bytes.size > maxFileBytes) {
            rotate()
            open()
        }
        val stream = checkNotNull(output)
        stream.write(bytes)
        stream.flush() // Completed records reach the OS immediately; no process-local buffered history.
        size += bytes.size
    }

    private fun open() {
        output = FileOutputStream(current, true)
        size = current.length()
    }

    private fun rotate() {
        output?.close()
        output = null
        val oldest = File(directory, "runtime.$rotatedFiles.log")
        if (oldest.exists() && !oldest.delete()) throw IOException("diagnostics rotation delete failed")
        for (index in rotatedFiles - 1 downTo 0) {
            val source = if (index == 0) current else File(directory, "runtime.$index.log")
            val target = File(directory, "runtime.${index + 1}.log")
            if (source.exists() && !source.renameTo(target)) throw IOException("diagnostics rotation rename failed")
        }
    }

    override fun close() {
        try {
            output?.close()
        } finally {
            output = null
            try {
                ownershipLock?.release()
            } finally {
                ownershipLock = null
                ownershipFile?.close()
                ownershipFile = null
            }
        }
    }
}

/** Extract bounded safe error fields. Never reads stack trace or causes when detailed is false. */
internal fun diagnosticErrorFields(error: Throwable, detailed: Boolean): Array<Pair<String, Any?>> {
    val errorType = try {
        error.javaClass.simpleName
    } catch (_: Throwable) {
        "Throwable"
    }
    if (!detailed) return arrayOf("error_type" to errorType)
    return try {
        val fields = ArrayList<Pair<String, Any?>>(20)
        fields.add("error_type" to errorType)
        fields.add("exception_class" to error.javaClass.name)

        var truncated = false
        val frames = error.stackTrace ?: emptyArray()
        val frameCount = minOf(frames.size, 8)
        for (i in 0 until frameCount) {
            val frame = frames[i] ?: continue
            fields.add("frame_$i" to "${frame.className ?: "unknown"}.${frame.methodName ?: "unknown"}:${frame.lineNumber}")
        }
        if (frames.size > 8) truncated = true

        val directCause = error.cause
        if (directCause != null && directCause !== error) {
            fields.add("cause_class" to directCause.javaClass.name)
            val causeFrames = directCause.stackTrace ?: emptyArray()
            val causeFrameCount = minOf(causeFrames.size, 8)
            for (i in 0 until causeFrameCount) {
                val frame = causeFrames[i] ?: continue
                fields.add("cause_frame_$i" to "${frame.className ?: "unknown"}.${frame.methodName ?: "unknown"}:${frame.lineNumber}")
            }
            if (causeFrames.size > 8) truncated = true
            if (directCause.cause != null) {
                truncated = true
            }
        }
        fields.add("detail_truncated" to truncated)
        fields.toTypedArray()
    } catch (_: Throwable) {
        arrayOf("error_type" to errorType, "detail_unavailable" to true)
    }
}

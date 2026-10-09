package io.github.reqpet.engine

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.github.reqpet.ui.PreferencesHelper
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Always-on structured diagnostics; no caller performs storage I/O or waits for the writer. */
object RuntimeDiagnostics {
    private val identifiers = try {
        RuntimeDiagnosticIds()
    } catch (_: Throwable) {
        null
    }
    private val buffer = try {
        RuntimeDiagnosticBuffer(
            runId = UUID.randomUUID().toString().replace("-", ""),
            monotonicClock = { nowMs() }
        )
    } catch (_: Throwable) {
        null
    }
    private val bound = AtomicBoolean()
    private val warned = AtomicBoolean()

    @Volatile
    var detailedMode: Boolean = false

    fun nowMs(): Long = SystemClock.elapsedRealtime()

    fun id(value: String?): String = try {
        identifiers?.id(value) ?: "none"
    } catch (_: Throwable) {
        "none"
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        try {
            buffer?.event(name, fields)
        } catch (_: Throwable) {
            // Diagnostics must never change host behavior, including before bind.
        }
    }

    fun error(name: String, error: Throwable, vararg fields: Pair<String, Any?>) {
        try {
            val detailed = detailedMode
            val errorFields = diagnosticErrorFields(error, detailed)
            val merged = if (fields.isEmpty()) {
                errorFields
            } else {
                Array(fields.size + errorFields.size) { index ->
                    if (index < fields.size) fields[index] else errorFields[index - fields.size]
                }
            }
            event(name, *merged)
        } catch (_: Throwable) {
            // Diagnostics must never change host behavior, including before bind.
        }
    }

    fun bind(context: Context) {
        if (!bound.compareAndSet(false, true)) return
        try {
            val buffer = checkNotNull(this.buffer)
            val appContext = context.applicationContext ?: context
            try {
                val prefs = appContext.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                detailedMode = prefs.getBoolean(PreferencesHelper.KEY_DIAGNOSTICS_DETAIL, false)
            } catch (_: Throwable) {
                detailedMode = false
            }
            val thread = Thread({
                try {
                    val external = try {
                        appContext.getExternalFilesDir("qpet-diagnostics")
                    } catch (_: Throwable) {
                        null
                    }
                    val writer = try {
                        if (external == null) null else RuntimeDiagnosticFileWriter(external)
                    } catch (busy: RuntimeDiagnosticWriterBusy) {
                        throw busy
                    } catch (_: Throwable) {
                        null
                    } ?: RuntimeDiagnosticFileWriter(File(appContext.filesDir, "qpet-diagnostics"))
                    writer.use {
                        buffer.writeControl(
                            it, "run_start", "schema" to 1, "pid" to Process.myPid(),
                            "android_sdk" to Build.VERSION.SDK_INT
                        )
                        while (true) buffer.writeNext(it, timeoutMs = 1_000L)
                    }
                } catch (_: Throwable) {
                    warnOnce()
                }
            }, "qpet-runtime-diagnostics").apply { isDaemon = true }
            thread.start()
        } catch (_: Throwable) {
            warnOnce()
        }
    }

    private fun warnOnce() {
        if (!warned.compareAndSet(false, true)) return
        try {
            // Fixed text only: storage paths, exception messages and business logs stay private.
            EngineLog.w("RuntimeDiagnostics", "Retained diagnostics unavailable: storage writer stopped")
        } catch (_: Throwable) {
            // No recursive logging or propagation into the host.
        }
    }
}

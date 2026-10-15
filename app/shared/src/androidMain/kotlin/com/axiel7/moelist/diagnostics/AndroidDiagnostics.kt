package com.axiel7.moelist.diagnostics

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.encodeToString

class DiagnosticFileProvider : androidx.core.content.FileProvider()

object AndroidDiagnostics : DiagnosticSink {
    private val lock = Any()
    private var storage: AtomicFile? = null
    private var state = DiagnosticState()

    fun initialize(context: Context) = synchronized(lock) {
        if (storage != null) return@synchronized
        storage = AtomicFile(File(context.noBackupFilesDir, "diagnostics/events.json").apply { parentFile?.mkdirs() })
        state = runCatching {
            val bytes = storage!!.readFully()
            require(bytes.size <= DIAGNOSTIC_MAX_BYTES)
            diagnosticJson.decodeFromString<DiagnosticState>(bytes.decodeToString())
        }.getOrDefault(DiagnosticState())
        state = state.copy(records = if (state.enabled) retainedRecords(state.records, System.currentTimeMillis()) else emptyList())
        AppDiagnostics.sink = this
        File(context.cacheDir, "diagnostic-reports").listFiles()?.filter {
            it.lastModified() < System.currentTimeMillis() - 86400000L
        }?.forEach { it.delete() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { AppDiagnostics.record(DiagnosticEvent.APP_CRASH, DiagnosticOutcome.FAILED, error = error) }
            finally { previous?.uncaughtException(thread, error) }
        }
        record(DiagnosticEvent.APP_START, DiagnosticOutcome.OK, emptyMap(), null)
    }

    override fun record(event: DiagnosticEvent, outcome: DiagnosticOutcome,
        metrics: Map<DiagnosticMetric, Long>, error: Throwable?) = synchronized(lock) {
        if (!state.enabled || storage == null) return@synchronized
        val now = System.currentTimeMillis()
        val frames = error?.stackTrace.orEmpty().filter {
            it.className.startsWith("com.axiel7.moelist.") || it.className.startsWith("com.google.mlkit.")
        }.take(4).map { frame ->
            "${frame.className}.${frame.methodName}:${frame.lineNumber}".take(160)
                .filter { it.isLetterOrDigit() || it in ".\$_:-<>" }
        }
        val entry = DiagnosticRecord(now, event, outcome, metrics.mapValues { it.value.coerceIn(-1, 1_000_000_000) },
            safeErrorTypes(error), frames)
        state = state.copy(records = retainedRecords(state.records + entry, now))
        persist()
    }

    fun enabled(): Boolean = synchronized(lock) { state.enabled }
    fun setEnabled(value: Boolean) = synchronized(lock) {
        state = state.copy(enabled = value, records = if (value) state.records else emptyList())
        persist()
    }
    fun clear(context: Context) = synchronized(lock) {
        state = state.copy(records = emptyList())
        persist()
        File(context.cacheDir, "diagnostic-reports").listFiles()?.forEach { it.delete() }
    }
    @Suppress("DEPRECATION")
    fun report(context: Context): String = synchronized(lock) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val now = System.currentTimeMillis()
        state = state.copy(records = retainedRecords(state.records, now))
        persist()
        encodeReport(DiagnosticReport(createdMs = now, device = DiagnosticDevice(
            info.versionName.orEmpty(), if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            Build.MANUFACTURER.take(80), Build.MODEL.take(80), Build.VERSION.RELEASE.take(80), Build.VERSION.SDK_INT),
            records = state.records))
    }
    private fun persist() {
        val file = storage ?: return
        val stream = file.startWrite()
        try { stream.write(diagnosticJson.encodeToString(state).encodeToByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

package com.axiel7.moelist.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.time.TimeSource

// Closed event/metric vocabularies deliberately prevent callers from logging arbitrary text.
@Serializable enum class DiagnosticEvent {
    APP_START, APP_CRASH, NANO_CHECK, NANO_DOWNLOAD, NANO_GENERATE,
    ASSISTANT_REQUEST, ASSISTANT_INTERPRET, ASSISTANT_HISTORY, ASSISTANT_RANK,
    ASSISTANT_SELECTION, ASSISTANT_CANCEL, UPDATE_CHECK, UPDATE_DOWNLOAD, REPORT_UPLOAD,
}
@Serializable enum class DiagnosticOutcome { STARTED, OK, FAILED, TIMED_OUT, CANCELLED, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING }
@Serializable enum class DiagnosticMetric {
    DURATION_MS, INPUT_TOKENS, OUTPUT_CHARACTERS, IMPORTED_COUNT, RATED_COUNT,
    HAS_SEED, MAX_EPISODES, GENRE_COUNT, AVOID_GENRE_COUNT, QUALITY_COUNT,
    CANDIDATE_COUNT, MATCH_COUNT, SHORTEST_MATCH, LONGEST_MATCH, HTTP_STATUS,
}

@Serializable data class DiagnosticRecord(
    val timeMs: Long,
    val event: DiagnosticEvent,
    val outcome: DiagnosticOutcome,
    val metrics: Map<DiagnosticMetric, Long> = emptyMap(),
    val errorTypes: List<String> = emptyList(),
    val frames: List<String> = emptyList(),
)
@Serializable data class DiagnosticState(val enabled: Boolean = true, val records: List<DiagnosticRecord> = emptyList())
@Serializable data class DiagnosticDevice(val appVersion: String, val appCode: Long,
    val manufacturer: String, val model: String, val androidVersion: String, val api: Int)
@Serializable data class DiagnosticReport(val schema: Int = 1, val createdMs: Long,
    val device: DiagnosticDevice, val records: List<DiagnosticRecord>)

val diagnosticJson = Json { encodeDefaults = true; prettyPrint = true }
const val DIAGNOSTIC_MAX_RECORDS = 100
const val DIAGNOSTIC_MAX_BYTES = 262144
const val DIAGNOSTIC_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L

fun retainedRecords(records: List<DiagnosticRecord>, nowMs: Long): List<DiagnosticRecord> = records
    .filter { it.timeMs in (nowMs - DIAGNOSTIC_RETENTION_MS)..nowMs }.takeLast(DIAGNOSTIC_MAX_RECORDS)

fun safeErrorTypes(error: Throwable?): List<String> {
    val result = mutableListOf<String>()
    var current = error
    repeat(4) {
        val value = current ?: return result
        // Exception messages, toString(), suppressed exceptions and prompts must never enter a report.
        result += value::class.simpleName.orEmpty().take(100).filter { it.isLetterOrDigit() || it == '_' }
        current = value.cause.takeUnless { it === value }
    }
    return result
}

interface DiagnosticSink {
    fun record(event: DiagnosticEvent, outcome: DiagnosticOutcome,
        metrics: Map<DiagnosticMetric, Long>, error: Throwable?)
}

object AppDiagnostics {
    var sink: DiagnosticSink? = null
    fun record(event: DiagnosticEvent, outcome: DiagnosticOutcome = DiagnosticOutcome.OK,
        metrics: Map<DiagnosticMetric, Long> = emptyMap(), error: Throwable? = null) {
        // Diagnostics must not interfere with app behavior, especially during a crash.
        runCatching { sink?.record(event, outcome, metrics, error) }
    }
}

fun encodeReport(report: DiagnosticReport): String = diagnosticJson.encodeToString(report).also {
    require(it.encodeToByteArray().size <= DIAGNOSTIC_MAX_BYTES)
}

fun validateDiagnosticsServer(server: String): String {
    val clean = server.trim().removeSuffix("/")
    require(clean.startsWith("https://")) { "Use an HTTPS address." }
    val host = clean.removePrefix("https://")
    require(host.length in 3..253 && host.contains('.') && host.split('.').all {
        it.length in 1..63 && Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?").matches(it)
    }) { "Enter only the server domain, such as https://logs.example.com." }
    return "https://${host.lowercase()}"
}

fun validUploadKey(key: String) = Regex("[A-Za-z0-9_-]{32,128}").matches(key)

suspend fun <T> diagnosticOperation(event: DiagnosticEvent, block: suspend () -> T): T {
    val started = TimeSource.Monotonic.markNow()
    AppDiagnostics.record(event, DiagnosticOutcome.STARTED)
    try {
        return block().also { AppDiagnostics.record(event, metrics = mapOf(
            DiagnosticMetric.DURATION_MS to started.elapsedNow().inWholeMilliseconds)) }
    } catch (error: Exception) {
        val outcome = when (error) {
            is TimeoutCancellationException -> DiagnosticOutcome.TIMED_OUT
            is CancellationException -> DiagnosticOutcome.CANCELLED
            else -> DiagnosticOutcome.FAILED
        }
        AppDiagnostics.record(event, outcome, mapOf(DiagnosticMetric.DURATION_MS to started.elapsedNow().inWholeMilliseconds), error)
        throw error
    }
}

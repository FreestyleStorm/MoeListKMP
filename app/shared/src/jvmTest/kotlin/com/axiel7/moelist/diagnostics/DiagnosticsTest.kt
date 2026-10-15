package com.axiel7.moelist.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @After fun resetSink() { AppDiagnostics.sink = null }

    @Test fun exceptionMessagesAndPromptsCannotEnterAReport() {
        val secret = "Bearer private-token user@example.com My private chat"
        val error = IllegalStateException(secret, IllegalArgumentException(secret))
        val report = encodeReport(DiagnosticReport(createdMs = 1000,
            device = DiagnosticDevice("4.1.8", 413, "samsung", "test", "16", 36),
            records = listOf(DiagnosticRecord(999, DiagnosticEvent.NANO_GENERATE, DiagnosticOutcome.FAILED,
                errorTypes = safeErrorTypes(error)))))
        assertFalse(report.contains(secret))
        assertFalse(report.contains("private-token"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("IllegalArgumentException"))
        assertEquals(2, safeErrorTypes(error).size)
    }

    @Test fun historyIsBoundedExpiresAndRejectsFutureEvents() {
        val now = DIAGNOSTIC_RETENTION_MS + 1000
        val records = (0L..150L).map { DiagnosticRecord(now - it, DiagnosticEvent.APP_START, DiagnosticOutcome.OK) }
        val expired = DiagnosticRecord(0, DiagnosticEvent.APP_START, DiagnosticOutcome.OK)
        val future = DiagnosticRecord(now + 1, DiagnosticEvent.APP_START, DiagnosticOutcome.OK)
        val retained = retainedRecords(listOf(expired) + records + future, now)
        assertEquals(100, retained.size)
        assertTrue(retained.none { it == expired || it == future })
    }

    @Test fun onlyHttpsDomainAndStrongUploadKeysAreAccepted() {
        assertEquals("https://logs.example.com", validateDiagnosticsServer(" https://LOGS.example.com/ "))
        for (invalid in listOf("http://logs.example.com", "https://key@logs.example.com", "https://logs.example.com/path",
            "https://logs.example.com?token=secret", "https://logs.example.com#fragment", "https://logs..example.com",
            "https://logs.example.com:1234", "https://-logs.example.com", "https://localhost")) {
            assertTrue(invalid, runCatching { validateDiagnosticsServer(invalid) }.isFailure)
        }
        assertTrue(validUploadKey("a".repeat(64)))
        assertFalse(validUploadKey("short"))
        assertFalse(validUploadKey("a".repeat(64) + "\r\nHeader"))
    }

    @Test fun diagnosticFailuresCannotBreakAppOperationsAndErrorsAreRethrown() = runBlocking {
        val events = mutableListOf<DiagnosticOutcome>()
        AppDiagnostics.sink = object : DiagnosticSink {
            override fun record(event: DiagnosticEvent, outcome: DiagnosticOutcome, metrics: Map<DiagnosticMetric, Long>, error: Throwable?) {
                events += outcome
                throw IllegalStateException("storage unavailable")
            }
        }
        assertEquals(42, diagnosticOperation(DiagnosticEvent.NANO_CHECK) { 42 })
        assertEquals(listOf(DiagnosticOutcome.STARTED, DiagnosticOutcome.OK), events)
        events.clear()
        assertTrue(runCatching { diagnosticOperation(DiagnosticEvent.NANO_GENERATE) { error("private error") } }.isFailure)
        assertEquals(listOf(DiagnosticOutcome.STARTED, DiagnosticOutcome.FAILED), events)
    }

    @Test fun timeoutIsRecordedWithoutTurningCancellationIntoSuccess() = runBlocking {
        val events = mutableListOf<DiagnosticOutcome>()
        AppDiagnostics.sink = object : DiagnosticSink {
            override fun record(event: DiagnosticEvent, outcome: DiagnosticOutcome, metrics: Map<DiagnosticMetric, Long>, error: Throwable?) { events += outcome }
        }
        val result = runCatching { diagnosticOperation(DiagnosticEvent.NANO_GENERATE) { withTimeout(1) { delay(100) } } }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(DiagnosticOutcome.TIMED_OUT, events.last())
    }
}

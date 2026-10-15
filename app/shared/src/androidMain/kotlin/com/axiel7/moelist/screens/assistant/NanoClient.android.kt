package com.axiel7.moelist.screens.assistant

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.axiel7.moelist.diagnostics.*
import kotlinx.coroutines.withTimeout

actual fun createNanoClient(): NanoClient = AndroidNanoClient()

private class AndroidNanoClient : NanoClient {
    private val client = lazy { Generation.getClient() }
    private val model get() = client.value

    override suspend fun availability(): NanoAvailability = withTimeout(15000) {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoAvailability.READY
            FeatureStatus.DOWNLOADABLE -> NanoAvailability.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoAvailability.DOWNLOADING
            else -> NanoAvailability.UNAVAILABLE
        }
    }

    override suspend fun download(onProgress: (String) -> Unit) {
        withTimeout(15 * 60 * 1000L) {
            model.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> onProgress("Preparing Gemini Nano…")
                    is DownloadStatus.DownloadProgress -> onProgress(
                        "Downloading Gemini Nano: ${status.totalBytesDownloaded / 1_000_000} MB")
                    DownloadStatus.DownloadCompleted -> onProgress("Gemini Nano is ready.")
                    is DownloadStatus.DownloadFailed -> throw status.e
                }
            }
        }
    }

    override suspend fun generate(prompt: String): String = diagnosticOperation(DiagnosticEvent.NANO_GENERATE) { withTimeout(60000) {
        check(availability() == NanoAvailability.READY) { "Gemini Nano is not ready. Check availability and try again." }
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = .1f
            candidateCount = 1
            maxOutputTokens = 500
        }
        val tokenCount = model.countTokens(request).totalTokens
        AppDiagnostics.record(DiagnosticEvent.NANO_GENERATE, metrics = mapOf(DiagnosticMetric.INPUT_TOKENS to tokenCount.toLong()))
        check(tokenCount <= minOf(3500, model.getTokenLimit() - 500)) {
            "This request is too long for the on-device model. Try a shorter request."
        }
        val result = model.generateContent(request).candidates.firstOrNull()?.text
            ?.takeIf { it.isNotBlank() } ?: error("Gemini Nano returned an empty response. Please retry.")
        AppDiagnostics.record(DiagnosticEvent.NANO_GENERATE, metrics = mapOf(DiagnosticMetric.OUTPUT_CHARACTERS to result.length.toLong()))
        result
    } }

    override fun close() { if (client.isInitialized()) model.close() }
}

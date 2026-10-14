package com.axiel7.moelist.screens.assistant

enum class NanoAvailability { CHECKING, READY, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

interface NanoClient {
    suspend fun availability(): NanoAvailability
    suspend fun download(onProgress: (String) -> Unit)
    suspend fun generate(prompt: String): String
    fun close()
}

expect fun createNanoClient(): NanoClient

internal class UnsupportedNanoClient : NanoClient {
    override suspend fun availability() = NanoAvailability.UNAVAILABLE
    override suspend fun download(onProgress: (String) -> Unit) = Unit
    override suspend fun generate(prompt: String): String = error("Gemini Nano requires a supported Android phone.")
    override fun close() = Unit
}

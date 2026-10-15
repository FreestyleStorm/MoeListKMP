package com.axiel7.moelist.diagnostics

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable data class DiagnosticsDestination(val server: String, val uploadKey: String)
class DiagnosticsUploadFailure(val status: Int) : Exception()

class DiagnosticsDestinationStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "diagnostics/destination.bin").apply { parentFile?.mkdirs() })
    private val alias = "moelist.diagnostics.upload.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): DiagnosticsDestination? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 29..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return diagnosticJson.decodeFromString<DiagnosticsDestination>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
    }
    fun save(server: String, uploadKey: String) {
        val destination = DiagnosticsDestination(validateDiagnosticsServer(server), uploadKey.trim())
        require(validUploadKey(destination.uploadKey)) { "Enter the upload key from your server setup." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(diagnosticJson.encodeToString(destination).encodeToByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun clear() { file.delete() }
}

// Upload exactly the previewed snapshot. Never follow redirects with an Authorization header.
fun uploadDiagnosticReport(destination: DiagnosticsDestination, report: String): String {
    val server = validateDiagnosticsServer(destination.server)
    require(validUploadKey(destination.uploadKey))
    val data = report.encodeToByteArray()
    require(data.size in 1..DIAGNOSTIC_MAX_BYTES)
    val connection = URL("$server/v1/reports").openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 25000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${destination.uploadKey}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setFixedLengthStreamingMode(data.size)
        connection.outputStream.use { it.write(data) }
        val status = connection.responseCode
        AppDiagnostics.record(DiagnosticEvent.REPORT_UPLOAD, if (status == 201) DiagnosticOutcome.OK else DiagnosticOutcome.FAILED,
            mapOf(DiagnosticMetric.HTTP_STATUS to status.toLong()))
        if (status != 201) throw DiagnosticsUploadFailure(status)
        val response = connection.inputStream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            while (buffer.size() <= 2048) {
                val next = input.read()
                if (next < 0) break
                buffer.write(next)
            }
            buffer.toByteArray()
        }
        require(response.size <= 2048)
        val result = diagnosticJson.decodeFromString<JsonObject>(response.decodeToString())
        return result["id"]?.jsonPrimitive?.content?.takeIf { Regex("[a-f0-9]{32}").matches(it) }
            ?: error("The server returned an invalid report receipt.")
    } finally { connection.disconnect() }
}

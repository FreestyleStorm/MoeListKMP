package com.axiel7.moelist.screens.more.settings.diagnostics

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.axiel7.moelist.diagnostics.*
import com.axiel7.moelist.ui.composables.preferences.PlainPreferenceView
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun DiagnosticsSetting() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember(app) { DiagnosticsDestinationStore(app) }
    var open by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(AndroidDiagnostics.enabled()) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf("") }
    var uploadKey by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = null; block() }
            catch (error: CancellationException) { throw error }
            catch (error: DiagnosticsUploadFailure) { message = "Server returned HTTP ${error.status}. Check the server and upload key. Your report is still on this phone." }
            catch (_: Exception) { message = "Couldn't complete that action. Check the server address, upload key and connection, then retry. Your report is still on this phone." }
            finally { busy = false }
        }
    }
    PlainPreferenceView(title = "Diagnostics", subtitle = "Preview, share or send a private report", onClick = {
        open = true
        work {
            configured = withContext(Dispatchers.IO) { store.load()?.server }
            server = configured.orEmpty()
            enabled = AndroidDiagnostics.enabled()
        }
    })
    if (open) Dialog(onDismissRequest = { if (!busy) { open = false; uploadKey = ""; preview = null } },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            DiagnosticsContent(enabled, busy, preview, server, uploadKey, configured, message,
                onEnabled = { value -> work { withContext(Dispatchers.IO) {
                    AndroidDiagnostics.setEnabled(value)
                    if (!value) AndroidDiagnostics.clear(app)
                }; enabled = value; preview = null } },
                onPreview = { work { preview = withContext(Dispatchers.IO) { AndroidDiagnostics.report(app) } } },
                onShare = { work {
                    val snapshot = preview ?: return@work
                    val file = withContext(Dispatchers.IO) {
                        val directory = File(app.cacheDir, "diagnostic-reports").apply { mkdirs() }
                        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000L }?.forEach { it.delete() }
                        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }
                        File(directory, "moelist-report-${java.util.UUID.randomUUID()}.json").apply { writeText(snapshot) }
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("MoeList diagnostic report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share diagnostic report"))
                } },
                onUpload = { work {
                    val snapshot = preview ?: return@work
                    val receipt = withContext(Dispatchers.IO) {
                        diagnosticOperation(DiagnosticEvent.REPORT_UPLOAD) {
                            uploadDiagnosticReport(store.load() ?: error("Server not configured"), snapshot)
                        }
                    }
                    message = "Sent to $configured. Report ID: $receipt. Share this ID in our chat so the report can be retrieved."
                } },
                onClear = { work { withContext(Dispatchers.IO) { AndroidDiagnostics.clear(app) }; preview = null; message = "Local diagnostics cleared." } },
                onServer = { server = it.take(300) }, onKey = { uploadKey = it.take(128) },
                onSave = { work {
                    withContext(Dispatchers.IO) { store.save(server, uploadKey) }
                    configured = validateDiagnosticsServer(server); server = configured.orEmpty(); uploadKey = ""
                    message = "Server saved. Review a report before sending."
                } },
                onRemove = { work { withContext(Dispatchers.IO) { store.clear() }; configured = null; server = ""; uploadKey = "" } },
                onClose = { if (!busy) { open = false; uploadKey = ""; preview = null } })
        }
    }
}

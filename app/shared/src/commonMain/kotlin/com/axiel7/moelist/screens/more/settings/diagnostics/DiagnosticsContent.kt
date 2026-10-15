package com.axiel7.moelist.screens.more.settings.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.axiel7.moelist.ui.composables.DefaultScaffoldWithTopAppBar
import com.axiel7.moelist.ui.composables.preferences.SwitchPreferenceView

@Composable
fun DiagnosticsContent(enabled: Boolean, busy: Boolean, preview: String?, server: String,
    uploadKey: String, configuredServer: String?, message: String?,
    onEnabled: (Boolean) -> Unit, onPreview: () -> Unit, onShare: () -> Unit, onUpload: () -> Unit,
    onClear: () -> Unit, onServer: (String) -> Unit, onKey: (String) -> Unit,
    onSave: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    DefaultScaffoldWithTopAppBar(title = "Diagnostics", navigateBack = onClose, canCollapseTopBar = false) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Share a report when something goes wrong", style = MaterialTheme.typography.titleLarge)
            Text("Reports include app and Android versions, device model, Nano status, timings, error types and app code locations. Recent activity includes counts and numeric limits.")
            Text("MAL credentials, account names, anime titles, chat text and raw error messages are excluded. Reports stay on this phone until you share or send one.")
            SwitchPreferenceView(title = "Keep recent diagnostics", subtitle = "Up to 100 events for 7 days. Turning this off clears them.",
                value = enabled, onValueChange = { if (!busy) onEnabled(it) })
            Button(onClick = onPreview, enabled = !busy) { Text(if (preview == null) "Preview report" else "Refresh preview") }
            if (preview != null) {
                Text("This exact report will be shared or uploaded.", style = MaterialTheme.typography.labelLarge)
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer {
                        Text(preview, Modifier.fillMaxWidth().height(240.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedButton(onClick = onShare, enabled = !busy) { Text("Share report file") }
            }
            Text("Private server", style = MaterialTheme.typography.titleLarge)
            Text("Optional. Send reports to your own server after reviewing the preview. Nothing uploads automatically.")
            OutlinedTextField(value = server, onValueChange = onServer, label = { Text("Server address") },
                placeholder = { Text("https://logs.example.com") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = uploadKey, onValueChange = onKey, label = { Text("Upload key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onSave, enabled = !busy && server.isNotBlank() && uploadKey.isNotBlank()) { Text("Save server") }
            if (configuredServer != null) {
                Text("Saved server: $configuredServer", style = MaterialTheme.typography.bodySmall)
                Text("Sent reports stay on the server for up to 7 days. Clearing this phone does not delete server copies.")
                Button(onClick = onUpload, enabled = !busy && preview != null) { Text("Send preview to server") }
                TextButton(onClick = onRemove, enabled = !busy) { Text("Remove server") }
            }
            if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClear, enabled = !busy) { Text("Clear local diagnostics") }
        }
    }
}

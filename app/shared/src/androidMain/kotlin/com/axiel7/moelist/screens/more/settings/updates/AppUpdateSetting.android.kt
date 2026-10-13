package com.axiel7.moelist.screens.more.settings.updates

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
actual fun AppUpdateSetting() {
    val context = LocalContext.current
    val model = koinViewModel<AppUpdateViewModel> { parametersOf(context.applicationContext) }
    val state by model.state.collectAsState()
    var installerOpen by rememberSaveable { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installerOpen = false
    }
    fun install() {
        val file = model.state.value.ready ?: return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            installerOpen = true
            installer.launch(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            installerOpen = false
            model.installerError()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) install()
    }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleLarge)
            Text("Installed: ${model.installedVersion}", style = MaterialTheme.typography.bodySmall)
            Text(state.message)
            if (state.progress != null) LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            when {
                state.ready != null -> {
                    Text("Android may ask you to allow updates from MoeList. Approve the update prompt to continue.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !installerOpen, onClick = {
                        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                            try {
                                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")))
                            } catch (_: Exception) { model.installerError() }
                        } else install()
                    }) { Text("Install update") }
                }
                state.available != null -> {
                    Text("${state.available!!.apk.sizeBytes / (1024 * 1024)} MB • Your login and saved data will be kept.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !state.busy, onClick = model::download) { Text("Download update") }
                }
                else -> Button(enabled = !state.busy, onClick = model::check) { Text("Check for updates") }
            }
        }
    }
}

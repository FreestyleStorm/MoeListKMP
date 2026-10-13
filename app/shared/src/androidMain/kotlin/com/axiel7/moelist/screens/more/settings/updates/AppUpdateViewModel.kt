package com.axiel7.moelist.screens.more.settings.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class UpdateState(
    val busy: Boolean = false,
    val message: String = "Check for a new version of MoeList.",
    val available: AvailableUpdate? = null,
    val progress: Float? = null,
    val ready: File? = null,
)

class AppUpdateViewModel(context: Context) : ViewModel() {
    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private val info = app.packageManager.getPackageInfo(app.packageName, flags)
    val installedVersion: String = info.versionName.orEmpty()
    private val installed = identity(info)

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): AppIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures
        return AppIdentity(info.packageName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }

    fun check() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutableState.value = UpdateState(busy = true, message = "Checking for updates…")
            try {
                val update = withContext(Dispatchers.IO) {
                    val releases = json.decodeFromString<List<GithubRelease>>(readText(
                        "https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=30", 2_000_000))
                    releases.filter(UpdatePolicy::isManifestRelease).mapNotNull { release ->
                        val metadata = release.assets.single { it.name == UPDATE_MANIFEST_NAME }
                        require(metadata.downloadUrl.startsWith(UPDATE_DOWNLOAD_PREFIX) && metadata.size in 1..65536)
                        val manifest = json.decodeFromString<UpdateManifest>(readText(metadata.downloadUrl, 65536))
                        val apk = UpdatePolicy.select(manifest, installed, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
                        apk?.let { UpdatePolicy.bind(manifest, it, release) }
                    }.maxByOrNull { it.manifest.versionCode }
                }
                mutableState.value = UpdateState(available = update,
                    message = if (update == null) "You’re up to date." else "Version ${update.manifest.versionName} is available.")
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = UpdateState(message = "Could not check for updates. ${error.message.orEmpty()}")
            }
        }
    }

    fun download() {
        if (job?.isActive == true) return
        val update = state.value.available ?: return
        job = viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, progress = 0f, ready = null,
                message = "Downloading update…")
            try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(app.cacheDir, "updates").apply { mkdirs() }
                    val partial = File(directory, "${update.apk.fileName}.part")
                    val target = File(directory, update.apk.fileName)
                    val connection = connect(update.asset.downloadUrl)
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        connection.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var lastPercent = -1
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read
                                    require(count <= update.apk.sizeBytes) { "The download is larger than expected." }
                                    digest.update(buffer, 0, read)
                                    output.write(buffer, 0, read)
                                    val percent = (count * 100 / update.apk.sizeBytes).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        mutableState.value = mutableState.value.copy(progress = count.toFloat() / update.apk.sizeBytes)
                                    }
                                }
                            }
                        }
                        require(count == update.apk.sizeBytes && hex(digest.digest()).equals(update.apk.sha256, true)) {
                            "The download could not be verified. Please try again."
                        }
                        @Suppress("DEPRECATION")
                        val archive = app.packageManager.getPackageArchiveInfo(partial.absolutePath, flags)
                            ?: error("Android could not read the downloaded update.")
                        UpdatePolicy.verifyArchive(update.manifest, installed, identity(archive))
                        if (target.exists()) require(target.delete())
                        require(partial.renameTo(target)) { "Could not save the update." }
                        target
                    } finally {
                        connection.disconnect()
                        partial.delete()
                    }
                }
                mutableState.value = state.value.copy(busy = false, progress = null, ready = file,
                    message = "Update verified and ready to install. Your app data will be kept.")
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = state.value.copy(busy = false, progress = null,
                    message = "Could not download the update. ${error.message.orEmpty()}")
            }
        }
    }

    fun installerError() {
        mutableState.value = state.value.copy(message = "Android could not open the installer. Try again or download the APK from our GitHub release.")
    }

    private fun readText(url: String, limit: Int): String {
        val connection = connect(url)
        try {
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                require(bytes.size <= limit) { "Update information is too large." }
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private fun connect(address: String): HttpsURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) &&
                url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "The update server address is not trusted."
            }
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MoeList-Android-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                url = URL(url, location ?: error("The update server returned an invalid redirect."))
            } else {
                if (code != 200) {
                    connection.disconnect()
                    error(if (code == 403 || code == 429) "GitHub is busy. Please try again later." else "The update server returned $code.")
                }
                return connection
            }
        }
        error("Too many redirects from the update server.")
    }

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

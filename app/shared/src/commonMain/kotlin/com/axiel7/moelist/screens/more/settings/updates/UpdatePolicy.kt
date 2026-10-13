package com.axiel7.moelist.screens.more.settings.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val UPDATE_REPOSITORY = "FreestyleStorm/MoeListKMP"
const val UPDATE_MANIFEST_NAME = "moelist-update.json"
const val UPDATE_DOWNLOAD_PREFIX = "https://github.com/$UPDATE_REPOSITORY/releases/download/"

@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val signingCertificateSha256: String,
    val apks: List<UpdateApk>,
)

@Serializable
data class UpdateApk(val abi: String, val fileName: String, val sha256: String, val sizeBytes: Long)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val pageUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
    val digest: String? = null,
)

data class AppIdentity(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)
data class AvailableUpdate(val manifest: UpdateManifest, val apk: UpdateApk, val asset: GithubAsset,
    val pageUrl: String)

object UpdatePolicy {
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val hash = Regex("[a-fA-F0-9]{64}")
    private val apkName = Regex("[a-zA-Z0-9_-][a-zA-Z0-9._-]{0,180}\\.apk")

    fun isManifestRelease(release: GithubRelease) = !release.draft &&
            release.tag.startsWith("v") && release.tag.contains("-personal.") &&
            release.assets.any { it.name == UPDATE_MANIFEST_NAME }

    fun select(manifest: UpdateManifest, installed: AppIdentity, abis: List<String>, sdk: Int): UpdateApk? {
        if (manifest.versionCode <= installed.versionCode) return null
        require(manifest.schemaVersion == 1) { "This update format is not supported." }
        require(manifest.packageName == installed.packageName) { "This update belongs to a different app." }
        require(hash.matches(manifest.signingCertificateSha256) &&
                installed.signerSha256.size == 1 &&
                manifest.signingCertificateSha256.lowercase() in installed.signerSha256.map { it.lowercase() }) {
            "This update uses a different signing key. Your installed app will be kept."
        }
        require(manifest.minSdk in 1..sdk) { "This update needs a newer Android version." }
        require(manifest.versionName.isNotBlank() && manifest.versionName.length <= 80) { "Invalid update version." }
        val apk = abis.firstNotNullOfOrNull { abi -> manifest.apks.find { it.abi == abi } }
            ?: manifest.apks.find { it.abi == "universal" }
            ?: error("This release has no update for your device.")
        require(apkName.matches(apk.fileName) && !apk.fileName.contains("..")) { "Invalid update filename." }
        require(hash.matches(apk.sha256) && apk.sizeBytes in 1..MAX_APK_BYTES) { "Invalid update file information." }
        return apk
    }

    fun bind(manifest: UpdateManifest, apk: UpdateApk, release: GithubRelease): AvailableUpdate {
        val asset = release.assets.single { it.name == apk.fileName }
        require(asset.downloadUrl.startsWith(UPDATE_DOWNLOAD_PREFIX) && asset.size == apk.sizeBytes) {
            "The release does not match its update information."
        }
        require(asset.digest == null || asset.digest.lowercase() == "sha256:${apk.sha256.lowercase()}") {
            "The release checksum does not match."
        }
        require(release.pageUrl.startsWith("https://github.com/$UPDATE_REPOSITORY/releases/tag/")) {
            "Invalid release page."
        }
        return AvailableUpdate(manifest, apk, asset, release.pageUrl)
    }

    fun verifyArchive(update: UpdateManifest, installed: AppIdentity, archive: AppIdentity) {
        require(archive.packageName == installed.packageName && archive.packageName == update.packageName) {
            "The downloaded file belongs to a different app."
        }
        require(archive.versionCode == update.versionCode && archive.versionCode > installed.versionCode) {
            "The downloaded version does not match the update."
        }
        require(archive.signerSha256.map { it.lowercase() }.toSet() ==
                installed.signerSha256.map { it.lowercase() }.toSet()) {
            "The downloaded app has a different signing key."
        }
    }
}

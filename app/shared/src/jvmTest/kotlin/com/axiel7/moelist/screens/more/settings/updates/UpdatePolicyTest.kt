package com.axiel7.moelist.screens.more.settings.updates

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val signer = "a".repeat(64)
    private val installed = AppIdentity("com.axiel7.moelist.debug", 409, setOf(signer))
    private val arm = UpdateApk("arm64-v8a", "moelist-4.1.5-arm64-v8a-personal.apk", "b".repeat(64), 3000)
    private val update = UpdateManifest(1, installed.packageName, 410, "4.1.5", 24, signer, listOf(arm))
    private val release = GithubRelease("v4.1.5-personal.1", "https://github.com/$UPDATE_REPOSITORY/releases/tag/v4.1.5-personal.1",
        prerelease = true, assets = listOf(
            GithubAsset(UPDATE_MANIFEST_NAME, 600, UPDATE_DOWNLOAD_PREFIX + "v4.1.5-personal.1/$UPDATE_MANIFEST_NAME"),
            GithubAsset(arm.fileName, arm.sizeBytes, UPDATE_DOWNLOAD_PREFIX + "v4.1.5-personal.1/${arm.fileName}", "sha256:${arm.sha256}"),
        ))

    @Test fun personalPrereleasesCanUpdate() {
        assertTrue(UpdatePolicy.isManifestRelease(release))
        assertFalse(UpdatePolicy.isManifestRelease(release.copy(draft = true)))
        assertEquals(arm, UpdatePolicy.select(update, installed, listOf("arm64-v8a"), 35))
        assertEquals(arm, UpdatePolicy.bind(update, arm, release).apk)
        UpdatePolicy.verifyArchive(update, installed, installed.copy(versionCode = 410))
    }

    @Test fun currentOrOlderVersionsAreIgnored() {
        assertNull(UpdatePolicy.select(update.copy(versionCode = 409), installed, listOf("arm64-v8a"), 35))
        assertNull(UpdatePolicy.select(update.copy(versionCode = 408), installed, listOf("arm64-v8a"), 35))
    }

    @Test fun wrongPackageOrSigningKeyCannotReplaceTheInstalledApp() {
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.select(update.copy(packageName = "other.app"), installed, listOf("arm64-v8a"), 35) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.select(update.copy(signingCertificateSha256 = "c".repeat(64)), installed, listOf("arm64-v8a"), 35) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verifyArchive(update, installed, installed.copy(versionCode = 410, signerSha256 = setOf("c".repeat(64)))) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verifyArchive(update, installed, installed.copy(versionCode = 411)) }
    }

    @Test fun deviceRequirementsAndFileLimitsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.select(update.copy(minSdk = 36), installed, listOf("arm64-v8a"), 35) }
        assertThrows(IllegalStateException::class.java) { UpdatePolicy.select(update, installed, listOf("x86_64"), 35) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.select(update.copy(apks = listOf(arm.copy(fileName = "../bad.apk"))), installed, listOf("arm64-v8a"), 35) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.select(update.copy(apks = listOf(arm.copy(sizeBytes = UpdatePolicy.MAX_APK_BYTES + 1))), installed, listOf("arm64-v8a"), 35) }
    }

    @Test fun mismatchedAssetsAndForeignDownloadUrlsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.bind(update, arm, release.copy(assets = listOf(release.assets.last().copy(downloadUrl = "https://other.example/app.apk")))) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.bind(update, arm, release.copy(assets = listOf(release.assets.last().copy(digest = "sha256:" + "c".repeat(64))))) }
    }
}

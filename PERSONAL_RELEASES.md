# Personal Android releases

Personal builds use `com.axiel7.moelist.debug`, preserving the identity of this fork's earlier APKs. They are not debuggable. The first permanently signed release replaces the previous temporary debug certificate and requires one final reinstall. Thereafter, install updates over the existing app. Do not uninstall or clear app data.

The personal workflow builds unsigned APKs and runs profile interaction and update-policy tests. An unsigned workflow artifact is not a distributable release. Sign it with the original permanent private key, using Android zipalign and apksigner, before publishing. Never generate a replacement signing key for an existing installation, or upload the private key/password to source control, release assets, or workflow artifacts.

Every release must increase `version.properties` code, retain the package name, and use the same signing certificate. Publish a `vX.Y.Z-personal.N` GitHub release containing the signed APK and `moelist-update.json`. Personal prereleases are supported. The updater reads this fork's public releases, verifies the manifest, file size, SHA-256, package/version, and installed signing certificate, then opens Android's installer. It never reads, sends, or resets MAL credentials.

Manifest schema 1: `schemaVersion`, `packageName`, `versionCode`, `versionName`, `minSdk`, `signingCertificateSha256`, and `apks` entries with `abi`, `fileName`, `sha256`, `sizeBytes`. Assets must belong to the release in this fork. ARM64 is used on the S26 Ultra. Supply other ABI assets or a universal APK if supporting other devices.

The permanent signing files are kept outside the repository and protected by Windows DPAPI for the original Windows account. They must be preserved for future releases. Copying the protected files to another Windows account or reinstalling Windows does not guarantee recovery. Before migrating computers, export a password-protected recovery copy and store its password separately. Never publish that backup.

Settings → App updates → Check for updates → Download update → Install update. Android may require the user to allow installs from MoeList. Users approve Android's update prompt; the app cannot silently install itself. Updates preserve app data, but an expired or revoked MAL session can still require sign-in independently of an app update.

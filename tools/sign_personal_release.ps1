param([Parameter(Mandatory=$true)][string]$InputApk, [Parameter(Mandatory=$true)][string]$OutputApk,
    [string]$Abi = 'arm64-v8a', [int]$VersionCode = 409, [string]$VersionName = '4.1.4',
    [Parameter(Mandatory=$true)][string]$ToolsJson, [Parameter(Mandatory=$true)][string]$PrivateDirectory, [switch]$Initialize)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$toolPaths = Get-Content -LiteralPath $ToolsJson -Raw | ConvertFrom-Json
$privatePath = [IO.Path]::GetFullPath($PrivateDirectory)
$encryptedKeyPath = Join-Path $privatePath 'release-key.dpapi'
$passwordPath = Join-Path $privatePath 'release-password.dpapi'
$certificatePath = Join-Path $privatePath 'release-certificate.pem'
$pinnedCertificatePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'personal-signing-certificate.sha256'
Add-Type -AssemblyName System.Security
$temporaryPath = Join-Path $privatePath ([Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $temporaryPath -Force | Out-Null
$keyPath = Join-Path $temporaryPath 'key.p12'
$alignedPath = Join-Path $temporaryPath 'aligned.apk'
try {
    $hasKey = Test-Path -LiteralPath $encryptedKeyPath
    $hasPassword = Test-Path -LiteralPath $passwordPath
    if (!$hasKey -or !$hasPassword) {
        if (!$Initialize -or $hasKey -or $hasPassword -or (Test-Path -LiteralPath $certificatePath) -or (Test-Path -LiteralPath $pinnedCertificatePath)) {
            throw 'Permanent signing files are missing or incomplete. Restore the original key; never replace it for an existing app.'
        }
        $random = New-Object byte[] 48
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($random)
        $signingPassword = [Convert]::ToBase64String($random)
        $env:MOELIST_SIGNING_PASSWORD = $signingPassword
        & $toolPaths.keytool -genkeypair -keystore $keyPath -storetype PKCS12 -alias moelist-personal -keyalg RSA -keysize 4096 -validity 36500 -dname 'CN=MoeList Personal' -storepass:env MOELIST_SIGNING_PASSWORD -keypass:env MOELIST_SIGNING_PASSWORD 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate the permanent key.' }
        [IO.File]::WriteAllBytes($encryptedKeyPath, [Security.Cryptography.ProtectedData]::Protect([IO.File]::ReadAllBytes($keyPath), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser))
        [IO.File]::WriteAllBytes($passwordPath, [Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($signingPassword), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser))
        & $toolPaths.keytool -exportcert -rfc -keystore $keyPath -alias moelist-personal -storepass:env MOELIST_SIGNING_PASSWORD -file $certificatePath 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not export the public certificate.' }
    } else {
        [IO.File]::WriteAllBytes($keyPath, [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($encryptedKeyPath), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser))
        $env:MOELIST_SIGNING_PASSWORD = [Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($passwordPath), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser))
    }
    & $toolPaths.zipalign -P 16 -f 4 $InputApk $alignedPath
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    & $toolPaths.java -jar $toolPaths.apksigner sign --ks $keyPath --ks-key-alias moelist-personal --ks-pass env:MOELIST_SIGNING_PASSWORD --key-pass env:MOELIST_SIGNING_PASSWORD --out $OutputApk $alignedPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    $verification = & $toolPaths.java -jar $toolPaths.apksigner verify --verbose --print-certs $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificateMatches = [regex]::Matches(($verification -join "`n"), 'certificate SHA-256 digest: ([a-f0-9]{64})')
    if ($certificateMatches.Count -ne 1) { throw 'Expected exactly one verified signing certificate.' }
    $signer = $certificateMatches[0].Groups[1].Value
    $publicText = Get-Content -LiteralPath $certificatePath -Raw
    $der = [Convert]::FromBase64String(($publicText -replace '-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----|\s', ''))
    $expected = ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($der))).Replace('-', '').ToLowerInvariant()
    if ($signer -ne $expected) { throw 'Unexpected signing certificate.' }
    if ((Test-Path -LiteralPath $pinnedCertificatePath) -and (Get-Content -LiteralPath $pinnedCertificatePath -Raw).Trim() -ne $expected) { throw 'The signing key does not match the original personal release certificate.' }
    $badging = & $toolPaths.aapt2 dump badging $OutputApk
    if ($LASTEXITCODE -ne 0 -or $badging[0] -notmatch "name='com.axiel7.moelist.debug' versionCode='$VersionCode' versionName='$VersionName-PERSONAL'") { throw 'APK identity/version did not match the requested release.' }
    if (($badging -join "`n") -match 'application-debuggable') { throw 'Personal release must not be debuggable.' }
    $minimumSdkMatch = [regex]::Match(($badging -join "`n"), "(?m)^sdkVersion:'([0-9]+)'")
    if (!$minimumSdkMatch.Success) { throw 'Could not verify APK minimum Android version.' }
    $metadata = [ordered]@{schemaVersion=1;packageName='com.axiel7.moelist.debug';versionCode=$VersionCode;versionName=$VersionName;minSdk=[int]$minimumSdkMatch.Groups[1].Value;signingCertificateSha256=$expected;apks=@(@{abi=$Abi;fileName=(Split-Path $OutputApk -Leaf);sha256=(Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash.ToLowerInvariant();sizeBytes=(Get-Item -LiteralPath $OutputApk).Length})}
    [IO.File]::WriteAllText((Join-Path (Split-Path $OutputApk -Parent) 'moelist-update.json'), ($metadata | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
    Write-Output "Signed and verified $OutputApk with permanent certificate $expected"
} finally {
    Remove-Item Env:MOELIST_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $resolved = [IO.Path]::GetFullPath($temporaryPath)
    $expectedRoot = [IO.Path]::GetFullPath($privatePath) + [IO.Path]::DirectorySeparatorChar
    if ($resolved.StartsWith($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}

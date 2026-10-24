param([string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'UrbanMood\signing'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$storeFile = Join-Path $SigningDirectory 'urbanmood-release.p12'
$passwordFile = Join-Path $SigningDirectory 'release-password.dpapi'
New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
if ((Test-Path -LiteralPath $storeFile) -ne (Test-Path -LiteralPath $passwordFile)) {
    throw 'Signing files are incomplete. Restore the existing key/password pair; do not replace the signing identity.'
}
try {
    if (-not (Test-Path -LiteralPath $storeFile)) {
        $randomBytes = [byte[]]::new(32)
        [Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
        $releasePassword = [Convert]::ToBase64String($randomBytes)
        $releasePassword | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString |
            Set-Content -LiteralPath $passwordFile -Encoding utf8
        $env:URBANMOOD_STORE_PASSWORD = $releasePassword
        $keytoolPath = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        & $keytoolPath -genkeypair -keystore $storeFile -storetype PKCS12 -alias urbanmood-release -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=UrbanMood Prototype' -storepass:env URBANMOOD_STORE_PASSWORD -keypass:env URBANMOOD_STORE_PASSWORD -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Release key creation failed.' }
    } else {
        $protected = (Get-Content -LiteralPath $passwordFile -Raw).Trim() | ConvertTo-SecureString
        $credential = [pscredential]::new('urbanmood-release', $protected)
        $env:URBANMOOD_STORE_PASSWORD = $credential.GetNetworkCredential().Password
    }
    $env:URBANMOOD_STORE_FILE = $storeFile
    Push-Location $projectRoot
    try {
        & .\gradlew.bat :app:assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally { Pop-Location }
    Write-Output 'Signed release: app/build/outputs/apk/release/app-release.apk'
    Write-Output 'Signing files are stored outside the repository.'
} finally {
    Remove-Item Env:URBANMOOD_STORE_PASSWORD,Env:URBANMOOD_STORE_FILE -ErrorAction SilentlyContinue
    $releasePassword = $null; $credential = $null; $protected = $null
}

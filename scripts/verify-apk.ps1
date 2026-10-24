param(
    [string]$Apk = (Join-Path (Split-Path $PSScriptRoot -Parent) 'app\build\outputs\apk\release\app-release.apk'),
    [string]$Sdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)
$ErrorActionPreference = 'Stop'
$buildTools = Join-Path $Sdk 'build-tools\36.0.0'
& (Join-Path $buildTools 'apksigner.bat') verify --verbose $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK 16KB ZIP alignment verification failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($Apk)
$alignments = @()
try {
    foreach ($entry in $archive.Entries | Where-Object { $_.FullName -match '^lib/.+\.so$' }) {
        $stream = $entry.Open()
        $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
        if ($bytes[0] -ne 127 -or $bytes[1] -ne 69 -or $bytes[2] -ne 76 -or $bytes[3] -ne 70 -or $bytes[5] -ne 1) { throw 'Unexpected ELF encoding' }
        $is64 = $bytes[4] -eq 2
        $table = if ($is64) { [BitConverter]::ToUInt64($bytes, 32) } else { [BitConverter]::ToUInt32($bytes, 28) }
        $entrySize = [BitConverter]::ToUInt16($bytes, $(if ($is64) { 54 } else { 42 }))
        $entryCount = [BitConverter]::ToUInt16($bytes, $(if ($is64) { 56 } else { 44 }))
        $loadAlignments = @()
        for ($index = 0; $index -lt $entryCount; $index++) {
            $offset = [int]($table + $index * $entrySize)
            if ([BitConverter]::ToUInt32($bytes, $offset) -eq 1) {
                $alignment = if ($is64) { [BitConverter]::ToUInt64($bytes, $offset + 48) } else { [BitConverter]::ToUInt32($bytes, $offset + 28) }
                if ($alignment -lt 16384) { throw "ELF page alignment below 16KB: $($entry.FullName)" }
                $loadAlignments += $alignment
            }
        }
        $alignments += [pscustomobject]@{ library = $entry.FullName; minimumLoadAlignment = ($loadAlignments | Measure-Object -Minimum).Minimum }
    }
} finally { $archive.Dispose() }
$hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
[pscustomobject]@{ apk = (Split-Path -Leaf $Apk); sha256 = $hash; signatureVerified = $true; zip16KBAligned = $true; nativeLibraries = $alignments } | ConvertTo-Json -Depth 4

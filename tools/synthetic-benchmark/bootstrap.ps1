$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$toolchain = Join-Path $repoRoot 'build\synthetic-benchmark\toolchain'
New-Item -ItemType Directory -Force -Path $toolchain | Out-Null

# Pinned official releases. Local to this repository; no global PATH changes.
function Get-VerifiedArchive($Name, $Url, $Sha256) {
    $archivePath = Join-Path $toolchain $Name
    if (!(Test-Path -LiteralPath $archivePath)) {
        Invoke-WebRequest -Uri $Url -OutFile $archivePath
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash
    if ($actual -ne $Sha256) { throw "Checksum mismatch for $Name; remove the archive and retry." }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolchain -Force
}
Get-VerifiedArchive 'jdk.zip' 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
Get-VerifiedArchive 'kotlin.zip' 'https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip' 'eb7b68e01029fa67bc8d060ee54c12018f2c60ddc438cf21db14517229aa693b'
Write-Output "Portable compilers ready in $toolchain"
Write-Output 'Run: python tools/synthetic-benchmark/run.py'

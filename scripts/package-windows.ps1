param(
    [switch]$SkipTests = $false
)

$ErrorActionPreference = "Stop"
$rootDir = (Get-Item "$PSScriptRoot\..").FullName
Set-Location $rootDir

$versionLine = Get-Content "$rootDir\version.properties" | Where-Object { $_ -match "^versionName=" }
if (-not $versionLine) {
    throw "versionName not found in version.properties"
}
$version = $versionLine.Trim().Substring("versionName=".Length)

Write-Host "==> Packaging Windows Zefir $version (Portable + Custom Go Installer)..." -ForegroundColor Cyan

$gradleArgs = @(":desktopApp:packagePortable", ":desktopApp:packageInstaller", "--no-daemon", "--console=plain")
if (-not $SkipTests) {
    $gradleArgs = @(":shared:desktopTest") + $gradleArgs
}

& "$rootDir\gradlew.bat" @gradleArgs
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$portableSrc = "$rootDir\desktopApp\build\compose\binaries\main\portable\Zefir-$version-windows-x64-portable.zip"
$setupSrc = "$rootDir\desktopApp\build\compose\binaries\main\installer\Zefir-$version-windows-x64-setup.exe"

if (-not (Test-Path -LiteralPath $portableSrc)) {
    throw "Portable zip not found: $portableSrc"
}
if (-not (Test-Path -LiteralPath $setupSrc)) {
    throw "Custom setup.exe not found: $setupSrc"
}

$tmpDir = "$rootDir\tmp"
$releaseDir = "$rootDir\tmp\release-$version"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

$portableDst = "$tmpDir\Zefir-$version-windows-x64-portable.zip"
$setupDst = "$tmpDir\Zefir-$version-windows-x64-setup.exe"

Copy-Item -LiteralPath $portableSrc -Destination $portableDst -Force
Copy-Item -LiteralPath $setupSrc -Destination $setupDst -Force

Copy-Item -LiteralPath $portableSrc -Destination "$releaseDir\Zefir-$version-windows-x64-portable.zip" -Force
Copy-Item -LiteralPath $setupSrc -Destination "$releaseDir\Zefir-$version-windows-x64-setup.exe" -Force

Write-Host "==> Done! Windows packages created:" -ForegroundColor Green
Get-Item $portableDst, $setupDst | ForEach-Object {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLower()
    Write-Host ("  {0} ({1:N0} bytes)`n    SHA256: {2}" -f $_.Name, $_.Length, $hash) -ForegroundColor Yellow
}

param(
    [string]$Repository = 'YouRooni/Zefir'
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$propertiesPath = Join-Path $projectPath 'keystore.properties'
if (!(Test-Path -LiteralPath $propertiesPath)) { throw 'Create keystore.properties first. See docs/BUILDING.md.' }
gh auth status
if ($LASTEXITCODE -ne 0) { throw 'Run gh auth login first. Git SSH access does not grant access to the Actions Secrets API.' }

$properties = ConvertFrom-StringData -StringData ([IO.File]::ReadAllText($propertiesPath))
foreach ($name in 'KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD') {
    if (!$properties[$name]) { throw "Missing property: $name" }
}
$storePath = $properties['KEYSTORE_FILE']
if (![IO.Path]::IsPathRooted($storePath)) { $storePath = Join-Path $projectPath $storePath }
$secretsToUpload = @{
    ANDROID_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($storePath))
    ANDROID_KEYSTORE_PASSWORD = $properties['KEYSTORE_PASSWORD']
    ANDROID_KEY_ALIAS = $properties['KEY_ALIAS']
    ANDROID_KEY_PASSWORD = $properties['KEY_PASSWORD']
}

foreach ($name in $secretsToUpload.Keys) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new('gh')
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in 'secret', 'set', $name, '--repo', $Repository) {
        $startInfo.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::Start($startInfo)
    $process.StandardInput.Write($secretsToUpload[$name])
    $process.StandardInput.Close()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Could not set $name" }
    $process.Dispose()
    Write-Output "Configured $name"
}

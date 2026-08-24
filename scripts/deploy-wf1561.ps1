[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F:.]+$')]
    [string]$FrameIp,

    [string]$ApkPath = "$env:LOCALAPPDATA\Temp\ImmichFrame-debug.apk",

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
$Serial = "${FrameIp}:5555"
$Package = 'com.immichframe.immichframe.debug'
$Component = "$Package/com.immichframe.immichframe.MainActivity"

if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "adb.exe was not found at $AdbPath"
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK was not found at $ApkPath"
}

& $AdbPath connect $Serial
if ($LASTEXITCODE -ne 0) { throw "ADB connection failed for $Serial" }

& $AdbPath -s $Serial install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed' }

# Package replacement can clear Android 8.1's preferred-HOME record. Restore it immediately,
# before sending HOME or interacting with the resolver UI.
& $AdbPath -s $Serial shell cmd package set-home-activity --user 0 $Component
if ($LASTEXITCODE -ne 0) { throw 'Unable to restore preferred HOME' }

# If PackageManager displayed a resolver while the APK was being replaced, setting HOME does not
# dismiss that already-running resolver. Bring the now-preferred singleTask activity forward once.
& $AdbPath -s $Serial shell am start -n $Component
if ($LASTEXITCODE -ne 0) { throw 'Unable to bring ImmichFrame Debug to the foreground' }

$Resolved = (& $AdbPath -s $Serial shell `
    cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
    Out-String).Trim()

if ($Resolved -notmatch [regex]::Escape($Component)) {
    throw "Preferred HOME verification failed. Resolved: $Resolved"
}

Write-Host "Deployment complete. Preferred HOME: $Resolved"

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$ExpectedModel,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$ExpectedDeviceSerial,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe",
    [string]$ReleaseId = ''
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "adb was not found at $AdbPath" }
$model = (& $AdbPath -s $Serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
$hardwareSerial = (& $AdbPath -s $Serial shell getprop ro.serialno 2>&1 | Out-String).Trim()
if ($model -ne $ExpectedModel -or $hardwareSerial -ne $ExpectedDeviceSerial) {
    throw 'Frame identity mismatch; refusing screenshot capture'
}
$parent = Split-Path -Parent $OutputPath
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$capture = Start-Process -FilePath $AdbPath -ArgumentList @('-s', $Serial, 'exec-out', 'screencap', '-p') `
    -RedirectStandardOutput $OutputPath -Wait -PassThru -NoNewWindow
if ($capture.ExitCode -ne 0) { throw 'ADB screenshot capture failed' }
$hash = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash.ToLowerInvariant()
$image = [System.Drawing.Image]::FromFile($OutputPath)
try { $width = $image.Width; $height = $image.Height } finally { $image.Dispose() }
[pscustomobject]@{ schemaVersion = 1; releaseId = $ReleaseId; model = $model;
    deviceSerial = $hardwareSerial; width = $width; height = $height;
    screenshotSha256 = $hash; path = $OutputPath } | ConvertTo-Json

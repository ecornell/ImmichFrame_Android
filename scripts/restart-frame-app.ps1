[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ManifestPath,

    [Parameter(Mandatory = $true)]
    [string]$FrameName,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedModel,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$ExpectedDeviceSerial,

    [Parameter(Mandatory = $true)]
    [string]$OutputJsonPath,

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'DeploymentPolicy.psm1') -Force
$Package = 'com.immichframe.immichframe.debug'
$Component = "$Package/com.immichframe.immichframe.MainActivity"

if (-not (Test-Path -LiteralPath $AdbPath)) { throw "adb.exe was not found at $AdbPath" }
if (-not (Test-Path -LiteralPath $ManifestPath)) { throw "Frame manifest was not found at $ManifestPath" }

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$matchingFrames = @($manifest.frames | Where-Object { [string]$_.name -ceq $FrameName })
if ($matchingFrames.Count -ne 1) { throw "Manifest must contain exactly one frame named '$FrameName'" }
$frame = $matchingFrames[0]
if ([string]$frame.expectedModel -cne $ExpectedModel -or
    [string]$frame.expectedDeviceSerial -cne $ExpectedDeviceSerial) {
    throw "Manifest identity does not match the explicitly expected model and hardware serial"
}
if ([string]::IsNullOrWhiteSpace([string]$frame.serial)) {
    throw "Manifest frame '$FrameName' requires a serial endpoint hint"
}

function Invoke-Adb {
    param([string]$Serial, [string[]]$Arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $rawOutput = & $AdbPath -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $output = ($rawOutput | Out-String).Trim()
    if ($exitCode -ne 0) { throw "adb -s $Serial $($Arguments -join ' ') failed: $output" }
    return $output
}

function Invoke-AdbOptional {
    param([string]$Serial, [string[]]$Arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $rawOutput = & $AdbPath -s $Serial @Arguments 2>&1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return ($rawOutput | Out-String).Trim()
}

function Get-AppState {
    param([string]$Serial)
    $processId = Invoke-AdbOptional -Serial $Serial -Arguments @('shell', 'pidof', $Package)
    $activities = Invoke-Adb -Serial $Serial -Arguments @('shell', 'dumpsys', 'activity', 'activities')
    $power = Invoke-Adb -Serial $Serial -Arguments @('shell', 'dumpsys', 'power')
    $wakefulness = [regex]::Match($power, '(?m)mWakefulness=([^\s]+)').Groups[1].Value
    $displayState = [regex]::Match($power, '(?m)Display Power: state=([^\s]+)').Groups[1].Value
    return [ordered]@{
        pid = $processId
        running = -not [string]::IsNullOrWhiteSpace($processId)
        resumed = $activities -match "mResumedActivity.*$([regex]::Escape($Package))"
        wakefulness = if ($wakefulness) { $wakefulness } else { $null }
        displayState = if ($displayState) { $displayState } else { $null }
    }
}

$record = [ordered]@{
    schemaVersion = 1
    requestedAt = (Get-Date).ToUniversalTime().ToString('o')
    frame = $FrameName
    package = $Package
    component = $Component
    endpoint = $null
    model = $null
    deviceSerial = $null
    before = $null
    after = $null
    stopCompleted = $false
    startCompleted = $false
    status = 'preflight'
    error = $null
}

function Write-Record {
    Write-DeploymentResultsJson -Items @([pscustomobject]$record) -OutputPath $OutputJsonPath
}

Write-Record
try {
    Write-Host "[$FrameName] Resolving identity-verified frame from manifest"
    $serial = Resolve-AuthorizedFrameSerial -Frame $frame -AdbPath $AdbPath
    if (-not $serial) { throw "[$FrameName] No matching authorized frame was found" }

    # Re-read both immutable identity properties immediately before the first mutation.
    $observedModel = Invoke-Adb -Serial $serial -Arguments @('shell', 'getprop', 'ro.product.model')
    $observedDeviceSerial = Invoke-Adb -Serial $serial -Arguments @('shell', 'getprop', 'ro.serialno')
    if ($observedModel -cne $ExpectedModel -or $observedDeviceSerial -cne $ExpectedDeviceSerial) {
        throw "[$FrameName] Device identity changed during preflight; refusing restart"
    }
    $installedPath = Invoke-Adb -Serial $serial -Arguments @('shell', 'pm', 'path', $Package)
    if ($installedPath -notmatch '(?m)^package:\S+$') { throw "[$FrameName] $Package is not installed" }

    $record.endpoint = $serial
    $record.model = $observedModel
    $record.deviceSerial = $observedDeviceSerial
    $record.before = Get-AppState -Serial $serial
    $record.status = 'preflight-verified'
    Write-Record

    Write-Host "[$FrameName] Stopping only $Package"
    Invoke-Adb -Serial $serial -Arguments @('shell', 'am', 'force-stop', $Package) | Out-Null
    $record.stopCompleted = $true
    $record.status = 'app-stopped'
    Write-Record

    Write-Host "[$FrameName] Starting explicit activity $Component"
    Invoke-Adb -Serial $serial -Arguments @('shell', 'am', 'start', '-n', $Component) | Out-Null
    $record.startCompleted = $true
    Start-Sleep -Seconds 2
    $record.after = Get-AppState -Serial $serial
    if (-not $record.after.running) { throw "[$FrameName] Restarted app has no running process" }
    if (-not $record.after.resumed) {
        throw "[$FrameName] Restarted app is not resumed; scheduled inactivity or screen state may be relevant"
    }
    $record.status = 'restarted-and-verified'
    Write-Record
    [pscustomobject]$record
} catch {
    $message = [string]$_.Exception.Message
    if ($message.Length -gt 500) { $message = $message.Substring(0, 500) }
    $record.error = $message
    $record.status = if ($record.stopCompleted -and -not $record.startCompleted) {
        'restart-failed-after-stop'
    } elseif ($record.stopCompleted) {
        'restart-verification-failed'
    } else {
        'preflight-failed-no-mutation'
    }
    Write-Record
    throw
}

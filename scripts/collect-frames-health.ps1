[CmdletBinding()]
param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot 'frames.local.json'),
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe",
    [string]$OutputDirectory = "$env:LOCALAPPDATA\ImmichFrame\health"
)
$ErrorActionPreference = 'Stop'
$collector = Join-Path $PSScriptRoot 'collect-frame-health.ps1'
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$results = @()
foreach ($frame in $manifest.frames) {
    $endpoint = [string]$frame.serial
    $ip = $endpoint -replace ':5555$', ''
    $isWf = [string]$frame.expectedModel -eq 'WF1561'
    $panelPackage = if ($frame.health -and $null -ne $frame.health.panelPackage) {
        [string]$frame.health.panelPackage
    } elseif ($isWf) { 'com.zeasn.frame' } else { '' }
    $requirePanel = if ($frame.health -and $null -ne $frame.health.requirePanelProcess) {
        [bool]$frame.health.requirePanelProcess
    } else { $isWf }
    $safeName = ([string]$frame.name -replace '[^A-Za-z0-9._-]', '_').ToLowerInvariant()
    $output = Join-Path $OutputDirectory "$safeName.json"
    $monitor = Join-Path $OutputDirectory "monitor-$safeName"
    $arguments = @{ FrameIp = $ip; ExpectedDeviceSerial = [string]$frame.expectedDeviceSerial;
        ExpectedModel = [string]$frame.expectedModel; PanelPackage = $panelPackage;
        RequirePanelProcess = $requirePanel; AdbPath = $AdbPath; MonitorDirectory = $monitor;
        OutputPath = $output }
    & $collector @arguments | Out-Null
    $results += Get-Content -LiteralPath $output -Raw | ConvertFrom-Json
}
$results

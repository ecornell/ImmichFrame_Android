[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F:.]+$')]
    [string]$FrameIp,

    [ValidatePattern('^[0-9a-fA-F]+$')]
    [string]$ExpectedDeviceSerial = 'c3d9b8674f4b94f6',

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe",

    [ValidateRange(1, 1440)]
    [int]$IntervalMinutes = 5
)

$ErrorActionPreference = 'Stop'
$TaskName = 'ImmichFrame WF1561 Monitor'
$InstallDirectory = "$env:LOCALAPPDATA\ImmichFrame"
$InstalledScript = Join-Path $InstallDirectory 'monitor-wf1561.ps1'
$SourceScript = Join-Path $PSScriptRoot 'monitor-wf1561.ps1'

if (-not (Test-Path -LiteralPath $SourceScript)) { throw "Missing $SourceScript" }
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "Missing $AdbPath" }

New-Item -ItemType Directory -Force -Path $InstallDirectory | Out-Null
Copy-Item -LiteralPath $SourceScript -Destination $InstalledScript -Force

$arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$InstalledScript`" " +
    "-FrameIp $FrameIp -ExpectedDeviceSerial $ExpectedDeviceSerial -AdbPath `"$AdbPath`""
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 2) `
    -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) `
    -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Description 'Read-only health sampling for the WF1561 photo frame.' -Force | Out-Null

# Establish an immediate baseline rather than waiting for the first trigger.
& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $InstalledScript `
    -FrameIp $FrameIp -ExpectedDeviceSerial $ExpectedDeviceSerial -AdbPath $AdbPath

Write-Host "Installed scheduled task '$TaskName' (every $IntervalMinutes minutes)."
Write-Host "Status: $InstallDirectory\monitor\latest.json"
Write-Host "Alert:  $InstallDirectory\monitor\alert.txt (present only when unhealthy)"

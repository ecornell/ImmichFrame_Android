[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F:.]+$')]
    [string]$FrameIp,

    [ValidatePattern('^[0-9a-fA-F]+$')]
    [string]$ExpectedDeviceSerial = 'c3d9b8674f4b94f6',

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe",

    [string]$LogDirectory = "$env:LOCALAPPDATA\ImmichFrame\monitor",

    [ValidateRange(1, 365)]
    [int]$RetentionDays = 14,

    [ValidateRange(1, 1048576)]
    [int]$MaxPssKb = 120000,

    [ValidateRange(10000, 150000)]
    [int]$MaxTemperatureMilliC = 80000
)

$ErrorActionPreference = 'Stop'
$Serial = "${FrameIp}:5555"
$Package = 'com.immichframe.immichframe.debug'
$Component = "$Package/com.immichframe.immichframe.MainActivity"
$PanelPackage = 'com.zeasn.frame'
$FotaPackage = 'com.adups.fota'

if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "adb.exe was not found at $AdbPath"
}

New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$Now = Get-Date
$Record = [ordered]@{
    timestamp = $Now.ToString('o')
    serial = $Serial
    status = 'offline'
    bootCompleted = $null
    appPid = $null
    panelPid = $null
    fotaPid = $null
    fotaInstalled = $null
    homeCorrect = $null
    resumedApp = $null
    pssKb = $null
    cpuPercent = $null
    maxTemperatureMilliC = $null
    notes = ''
}

function Invoke-AdbText {
    param([string[]]$Arguments)
    $output = (& $AdbPath @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw $output }
    return $output
}

function Invoke-AdbOptionalText {
    param([string[]]$Arguments)
    # pidof returns 1 when a process is absent; absence is monitor data, not a transport error.
    return (& $AdbPath @Arguments 2>&1 | Out-String).Trim()
}

function Test-TcpPort {
    param([string]$Ip, [int]$Port = 5555, [int]$TimeoutMs = 300)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($Ip, $Port)
        return $task.Wait($TimeoutMs) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Test-FrameEndpoint {
    param([string]$Ip)
    if (-not (Test-TcpPort -Ip $Ip)) { return $false }
    & $AdbPath connect "${Ip}:5555" 2>&1 | Out-Null
    $state = Invoke-AdbOptionalText -Arguments @('-s', "${Ip}:5555", 'get-state')
    if ($state -ne 'device') { return $false }
    $deviceSerial = Invoke-AdbOptionalText -Arguments @('-s', "${Ip}:5555", 'shell', 'getprop', 'ro.serialno')
    return $deviceSerial -eq $ExpectedDeviceSerial
}

function Find-OpenAdbAddresses {
    param([string]$Prefix)
    # Discovery only runs after known endpoints fail. Concurrent 300 ms probes keep a /24 scan
    # short and avoid keeping a resident watcher on either the PC or the frame.
    $probes = @()
    foreach ($hostNumber in 1..254) {
        $ip = "$Prefix.$hostNumber"
        $client = New-Object System.Net.Sockets.TcpClient
        $probes += [pscustomobject]@{ Ip = $ip; Client = $client; Task = $client.ConnectAsync($ip, 5555) }
    }
    Start-Sleep -Milliseconds 350
    $hits = @()
    foreach ($probe in $probes) {
        try {
            if ($probe.Task.Status -eq 'RanToCompletion' -and $probe.Client.Connected) {
                $hits += $probe.Ip
            }
        } catch {
            # Closed and unreachable addresses are expected during discovery.
        } finally {
            $probe.Client.Dispose()
        }
    }
    return $hits
}

function Resolve-FrameIp {
    $endpointPath = Join-Path $LogDirectory 'endpoint.txt'
    $candidates = New-Object System.Collections.Generic.List[string]
    if (Test-Path -LiteralPath $endpointPath) {
        $cached = (Get-Content -LiteralPath $endpointPath -First 1).Trim()
        if ($cached) { $candidates.Add($cached) }
    }
    if (-not $candidates.Contains($FrameIp)) { $candidates.Add($FrameIp) }

    # Reuse any endpoint that this adb server already knows before scanning the LAN.
    $devices = Invoke-AdbOptionalText -Arguments @('devices')
    foreach ($match in [regex]::Matches($devices, '(?m)^(\d+\.\d+\.\d+\.\d+):5555\s+device$')) {
        $ip = $match.Groups[1].Value
        if (-not $candidates.Contains($ip)) { $candidates.Add($ip) }
    }

    foreach ($candidate in $candidates) {
        if (Test-FrameEndpoint -Ip $candidate) { return $candidate }
    }

    $prefixMatch = [regex]::Match($FrameIp, '^(\d+\.\d+\.\d+)\.\d+$')
    if (-not $prefixMatch.Success) { return $null }
    foreach ($candidate in (Find-OpenAdbAddresses -Prefix $prefixMatch.Groups[1].Value)) {
        if (Test-FrameEndpoint -Ip $candidate) { return $candidate }
    }
    return $null
}

try {
    $resolvedIp = Resolve-FrameIp
    if (-not $resolvedIp) { throw "WF1561 was not found on $FrameIp/24" }
    $Serial = "${resolvedIp}:5555"
    $Record.serial = $Serial
    $endpointPath = Join-Path $LogDirectory 'endpoint.txt'
    Set-Content -LiteralPath "$endpointPath.tmp" -Value $resolvedIp -Encoding ASCII
    Move-Item -LiteralPath "$endpointPath.tmp" -Destination $endpointPath -Force

    if ((Invoke-AdbText -Arguments @('-s', $Serial, 'get-state')) -ne 'device') {
        throw 'ADB endpoint is not in the device state'
    }

    $Record.bootCompleted = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'getprop', 'sys.boot_completed')
    $Record.appPid = Invoke-AdbOptionalText -Arguments @('-s', $Serial, 'shell', 'pidof', $Package)
    $Record.panelPid = Invoke-AdbOptionalText -Arguments @('-s', $Serial, 'shell', 'pidof', $PanelPackage)
    $Record.fotaPid = Invoke-AdbOptionalText -Arguments @('-s', $Serial, 'shell', 'pidof', $FotaPackage)

    $fotaPackages = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'pm', 'list', 'packages', '--user', '0', $FotaPackage)
    $Record.fotaInstalled = $fotaPackages -match [regex]::Escape($FotaPackage)

    $resolvedHome = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'cmd', 'package', 'resolve-activity',
        '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    $Record.homeCorrect = $resolvedHome -match [regex]::Escape($Component)

    $resumed = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'activity', 'activities')
    $Record.resumedApp = $resumed -match "mResumedActivity.*$([regex]::Escape($Package))"

    $meminfo = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'meminfo', $Package)
    $totalMatch = [regex]::Match($meminfo, '(?m)^\s*TOTAL\s+(\d+)')
    if ($totalMatch.Success) { $Record.pssKb = [int]$totalMatch.Groups[1].Value }

    $cpuinfo = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'cpuinfo')
    $cpuMatch = [regex]::Match($cpuinfo, "(?m)^\s*([0-9.]+)%\s+\d+/$([regex]::Escape($Package))")
    if ($cpuMatch.Success) { $Record.cpuPercent = [double]$cpuMatch.Groups[1].Value }

    $thermal = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'cat', '/sys/class/thermal/thermal_zone*/temp')
    $temperatures = [regex]::Matches($thermal, '\d+') | ForEach-Object { [int]$_.Value }
    if ($temperatures.Count -gt 0) {
        $Record.maxTemperatureMilliC = ($temperatures | Measure-Object -Maximum).Maximum
    }

    $problems = @()
    if ($Record.bootCompleted -ne '1') { $problems += 'boot incomplete' }
    if (-not $Record.appPid) { $problems += 'ImmichFrame process missing' }
    if (-not $Record.panelPid) { $problems += 'OEM panel process missing' }
    if ($Record.bootCompleted -eq '1' -and -not $Record.resumedApp) { $problems += 'ImmichFrame not foreground' }
    if ($Record.fotaInstalled -or $Record.fotaPid) { $problems += 'ADUPS FOTA returned' }
    if (-not $Record.homeCorrect) { $problems += 'preferred HOME changed' }
    if ($Record.pssKb -and $Record.pssKb -gt $MaxPssKb) { $problems += "high PSS ($($Record.pssKb) KB)" }
    if ($Record.maxTemperatureMilliC -and $Record.maxTemperatureMilliC -gt $MaxTemperatureMilliC) {
        $problems += "high temperature ($($Record.maxTemperatureMilliC) mC)"
    }

    if ($problems.Count -eq 0) {
        $Record.status = 'healthy'
    } else {
        $Record.status = 'degraded'
        $Record.notes = $problems -join '; '
    }
} catch {
    $Record.notes = $_.Exception.Message -replace '[\r\n]+', ' '
}

$latestPath = Join-Path $LogDirectory 'latest.json'
$temporaryPath = "$latestPath.tmp"
$Record | ConvertTo-Json | Set-Content -LiteralPath $temporaryPath -Encoding UTF8
Move-Item -LiteralPath $temporaryPath -Destination $latestPath -Force

$historyPath = Join-Path $LogDirectory ("history-{0}.csv" -f $Now.ToString('yyyy-MM-dd'))
[pscustomobject]$Record | Export-Csv -LiteralPath $historyPath -Append -NoTypeInformation

$alertPath = Join-Path $LogDirectory 'alert.txt'
if ($Record.status -eq 'healthy') {
    Remove-Item -LiteralPath $alertPath -Force -ErrorAction SilentlyContinue
} else {
    "{0} [{1}] {2}" -f $Record.timestamp, $Record.status, $Record.notes |
        Set-Content -LiteralPath $alertPath -Encoding UTF8
}

Get-ChildItem -LiteralPath $LogDirectory -Filter 'history-*.csv' -File |
    Where-Object LastWriteTime -lt $Now.AddDays(-$RetentionDays) |
    Remove-Item -Force

[pscustomobject]$Record

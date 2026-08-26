[CmdletBinding()]
param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot 'frames.local.json'),

    [string]$ApkPath = "$env:LOCALAPPDATA\Temp\ImmichFrame-debug.apk",

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe",

    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string]$ExpectedApkSha256 = '',

    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$Package = 'com.immichframe.immichframe.debug'
$Component = "$Package/com.immichframe.immichframe.MainActivity"

if (-not (Test-Path -LiteralPath $AdbPath)) { throw "adb.exe was not found at $AdbPath" }
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK was not found at $ApkPath" }
if (-not (Test-Path -LiteralPath $ManifestPath)) {
    throw "Frame manifest was not found at $ManifestPath. Copy scripts\frames.example.json to scripts\frames.local.json and update it."
}

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
if (-not $manifest.frames -or $manifest.frames.Count -eq 0) { throw 'Frame manifest contains no frames' }
$apkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($ExpectedApkSha256 -and $apkHash -ne $ExpectedApkSha256.ToLowerInvariant()) {
    throw "APK SHA-256 mismatch. Expected $ExpectedApkSha256, got $apkHash"
}
$results = @()

function Invoke-Adb {
    param([string]$Serial, [string[]]$Arguments)
    $output = (& $AdbPath -s $Serial @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "adb -s $Serial $($Arguments -join ' ') failed: $output" }
    return $output
}

function Invoke-AdbOptional {
    param([string[]]$Arguments)
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

function Find-OpenAdbAddresses {
    param([string]$Prefix)
    if ($Prefix -notmatch '^\d{1,3}\.\d{1,3}\.\d{1,3}$') {
        throw "Invalid scanSubnet '$Prefix'; expected the first three IPv4 octets"
    }
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

function Test-FrameCandidate {
    param(
        [string]$Serial,
        [string]$ExpectedModel,
        [string]$ExpectedDeviceSerial
    )
    if ($Serial -match '^(\d+\.\d+\.\d+\.\d+):5555$') {
        if (-not (Test-TcpPort -Ip $Matches[1])) { return $false }
        & $AdbPath connect $Serial 2>&1 | Out-Null
    }
    if ((Invoke-AdbOptional -Arguments @('-s', $Serial, 'get-state')) -ne 'device') { return $false }
    $model = Invoke-AdbOptional -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')
    if ($model -ne $ExpectedModel) { return $false }
    if ($ExpectedDeviceSerial) {
        $deviceSerial = Invoke-AdbOptional -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.serialno')
        if ($deviceSerial -ne $ExpectedDeviceSerial) { return $false }
    }
    return $true
}

function Resolve-FrameSerial {
    param($Frame)
    $requested = [string]$Frame.serial
    $expectedModel = [string]$Frame.expectedModel
    $expectedDeviceSerial = [string]$Frame.expectedDeviceSerial
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($requested) { $candidates.Add($requested) }

    $devices = Invoke-AdbOptional -Arguments @('devices')
    foreach ($match in [regex]::Matches($devices, '(?m)^(\S+)\s+device$')) {
        $candidate = $match.Groups[1].Value
        if (-not $candidates.Contains($candidate)) { $candidates.Add($candidate) }
    }
    foreach ($candidate in $candidates) {
        if (Test-FrameCandidate -Serial $candidate -ExpectedModel $expectedModel `
                -ExpectedDeviceSerial $expectedDeviceSerial) {
            return $candidate
        }
    }

    $scanSubnet = [string]$Frame.scanSubnet
    if ($scanSubnet) {
        Write-Host "[$($Frame.name)] Known endpoints failed; scanning $scanSubnet.0/24 for ADB"
        foreach ($ip in (Find-OpenAdbAddresses -Prefix $scanSubnet)) {
            $candidate = "${ip}:5555"
            if (Test-FrameCandidate -Serial $candidate -ExpectedModel $expectedModel `
                    -ExpectedDeviceSerial $expectedDeviceSerial) {
                return $candidate
            }
        }
    }
    return $null
}

foreach ($frame in $manifest.frames) {
    $name = [string]$frame.name
    $requestedSerial = [string]$frame.serial
    $expectedModel = [string]$frame.expectedModel
    if ([string]::IsNullOrWhiteSpace($name) -or [string]::IsNullOrWhiteSpace($requestedSerial) -or
        [string]::IsNullOrWhiteSpace($expectedModel)) {
        throw 'Every frame requires name, serial, and expectedModel'
    }

    Write-Host "[$name] Resolving device from hint $requestedSerial"
    $serial = Resolve-FrameSerial -Frame $frame
    if (-not $serial) { throw "[$name] No matching authorized frame was found" }
    Write-Host "[$name] Using verified endpoint $serial"
    $model = Invoke-Adb -Serial $serial -Arguments @('shell', 'getprop', 'ro.product.model')
    if ($model -ne $expectedModel) {
        throw "[$name] Expected model '$expectedModel' but $serial reported '$model'; refusing installation"
    }

    if (-not $VerifyOnly) {
        Write-Host "[$name] Installing APK $apkHash"
        $install = Invoke-Adb -Serial $serial -Arguments @('install', '-r', $ApkPath)
        if ($install -notmatch '(?m)^Success$') { throw "[$name] Installation did not report Success: $install" }

        Invoke-Adb -Serial $serial -Arguments @(
            'shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $Component
        ) | Out-Null
        Invoke-Adb -Serial $serial -Arguments @('shell', 'am', 'start', '-n', $Component) | Out-Null
    } else {
        Write-Host "[$name] Verify-only mode; no device changes"
    }

    $resolvedHome = Invoke-Adb -Serial $serial -Arguments @(
        'shell', 'cmd', 'package', 'resolve-activity', '--brief',
        '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'
    )
    if ($resolvedHome -notmatch [regex]::Escape($Component)) {
        throw "[$name] Preferred HOME verification failed: $resolvedHome"
    }

    $packageInfo = Invoke-Adb -Serial $serial -Arguments @('shell', 'dumpsys', 'package', $Package)
    $versionCode = [regex]::Match($packageInfo, 'versionCode=(\d+)').Groups[1].Value
    $versionName = [regex]::Match($packageInfo, 'versionName=([^\s]+)').Groups[1].Value
    $resumed = Invoke-Adb -Serial $serial -Arguments @('shell', 'dumpsys', 'activity', 'activities')
    if ($resumed -notmatch "mResumedActivity.*$([regex]::Escape($Package))") {
        throw "[$name] ImmichFrame is not the resumed application"
    }
    $processId = Invoke-Adb -Serial $serial -Arguments @('shell', 'pidof', $Package)

    $results += [pscustomobject]@{
        name = $name
        serial = $serial
        model = $model
        versionCode = $versionCode
        versionName = $versionName
        pid = $processId
        homeCorrect = $true
        resumed = $true
        apkSha256 = $apkHash
    }
}

$results | Format-Table -AutoSize

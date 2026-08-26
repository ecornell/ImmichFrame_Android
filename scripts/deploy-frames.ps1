[CmdletBinding()]
param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot 'frames.local.json'),

    [string]$ApkPath = "$env:LOCALAPPDATA\Temp\ImmichFrame-debug.apk",

    [string]$DeploymentMetadataPath = '',

    [string]$RollbackApkPath = '',

    [string]$ApkAnalyzerPath = '',

    [string]$ApkSignerPath = '',

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe",

    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string]$ExpectedApkSha256 = '',

    [string]$ApprovalReleaseId = '',

    [string]$OutputJsonPath = '',

    [switch]$Rollback,

    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$Package = 'com.immichframe.immichframe.debug'
$Component = "$Package/com.immichframe.immichframe.MainActivity"

if (-not $ApkAnalyzerPath -and $env:ANDROID_HOME) {
    $ApkAnalyzerPath = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\apkanalyzer.bat'
}
if (-not $ApkSignerPath -and $env:ANDROID_HOME) {
    $ApkSignerPath = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\apksigner.bat'
}

function Get-ApkFacts {
    param([string]$Path)
    $applicationId = (& $ApkAnalyzerPath manifest application-id $Path 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect APK application ID: $applicationId" }
    $versionCode = (& $ApkAnalyzerPath manifest version-code $Path 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $versionCode -notmatch '^\d+$') { throw "Unable to inspect APK version: $versionCode" }
    $versionName = (& $ApkAnalyzerPath manifest version-name $Path 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $versionName) { throw "Unable to inspect APK version name: $versionName" }
    $signerOutput = (& $ApkSignerPath verify --print-certs $Path 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Unable to verify APK signature: $signerOutput" }
    $signer = [regex]::Match($signerOutput, '(?m)^Signer #1 certificate SHA-256 digest: (\S+)').Groups[1].Value
    if (-not $signer) { throw 'APK signer SHA-256 was unavailable' }
    return [pscustomobject]@{ applicationId = $applicationId; versionCode = [int64]$versionCode; versionName = $versionName; signerSha256 = $signer }
}

if (-not (Test-Path -LiteralPath $AdbPath)) { throw "adb.exe was not found at $AdbPath" }
if (-not (Test-Path -LiteralPath $ManifestPath)) {
    throw "Frame manifest was not found at $ManifestPath. Copy scripts\frames.example.json to scripts\frames.local.json and update it."
}

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
if (-not $manifest.frames -or $manifest.frames.Count -eq 0) { throw 'Frame manifest contains no frames' }
$apkHash = $null
$deployment = $null
if (-not $VerifyOnly) {
    if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK was not found at $ApkPath" }
    if (-not $DeploymentMetadataPath -or -not (Test-Path -LiteralPath $DeploymentMetadataPath)) {
        throw 'Mutable deployment requires -DeploymentMetadataPath from prepare-deployment.sh'
    }
    $deployment = Get-Content -LiteralPath $DeploymentMetadataPath -Raw | ConvertFrom-Json
    if ($deployment.schemaVersion -ne 1 -or -not $deployment.releaseId) {
        throw 'Unsupported deployment metadata'
    }
    $requiredApproval = if ($Rollback) { "rollback-$($deployment.releaseId)" } else { [string]$deployment.releaseId }
    if ($ApprovalReleaseId -cne $requiredApproval) {
        throw "Explicit approval required. Re-run with -ApprovalReleaseId '$requiredApproval' only after reviewing the plan."
    }
    if (-not $ApkAnalyzerPath -or -not $ApkSignerPath -or
        -not (Test-Path -LiteralPath $ApkAnalyzerPath) -or -not (Test-Path -LiteralPath $ApkSignerPath)) {
        throw 'Mutable deployment requires valid -ApkAnalyzerPath and -ApkSignerPath'
    }
    if (-not $deployment.rollback -or -not $RollbackApkPath -or -not (Test-Path -LiteralPath $RollbackApkPath)) {
        throw 'A verified rollback APK is required before deployment'
    }
    if ($deployment.rollback.signerSha256 -ne $deployment.signerSha256) {
        throw 'Rollback and target signer metadata differ'
    }
    $rollbackHash = (Get-FileHash -LiteralPath $RollbackApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($rollbackHash -ne ([string]$deployment.rollback.apkSha256).ToLowerInvariant()) {
        throw 'Rollback APK SHA-256 does not match deployment metadata'
    }
    $apkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($apkHash -ne ([string]$deployment.apkSha256).ToLowerInvariant()) {
        throw "APK SHA-256 does not match deployment metadata. Expected $($deployment.apkSha256), got $apkHash"
    }
    if ($ExpectedApkSha256 -and $apkHash -ne $ExpectedApkSha256.ToLowerInvariant()) {
        throw "APK SHA-256 mismatch. Expected $ExpectedApkSha256, got $apkHash"
    }
    $targetFacts = Get-ApkFacts -Path $ApkPath
    $rollbackFacts = Get-ApkFacts -Path $RollbackApkPath
    if ($targetFacts.applicationId -ne $Package -or $targetFacts.applicationId -ne $deployment.applicationId -or
        $targetFacts.versionCode -ne [int64]$deployment.versionCode -or
        $targetFacts.versionName -ne $deployment.versionName -or
        $targetFacts.signerSha256 -ne $deployment.signerSha256) {
        throw 'Target APK identity, version, or signer does not match deployment metadata'
    }
    if ($rollbackFacts.applicationId -ne $Package -or
        $rollbackFacts.versionCode -ne [int64]$deployment.rollback.versionCode -or
        $rollbackFacts.versionName -ne $deployment.rollback.versionName -or
        $rollbackFacts.signerSha256 -ne $deployment.rollback.signerSha256) {
        throw 'Rollback APK identity, version, or signer does not match deployment metadata'
    }
    if ($rollbackFacts.versionCode -ge $targetFacts.versionCode) {
        throw 'Inspected rollback APK version must be older than the inspected target APK version'
    }
    if ($Rollback) {
        $approvedFacts = $rollbackFacts
        $approvedApkPath = $RollbackApkPath
        $approvedApkHash = $rollbackHash
    } else {
        $approvedFacts = $targetFacts
        $approvedApkPath = $ApkPath
        $approvedApkHash = $apkHash
    }
}
$results = @()

function Invoke-Adb {
    param([string]$Serial, [string[]]$Arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # adb writes successful transfer progress to stderr. Capture it without allowing
        # PowerShell's NativeCommandError wrapper to abort an otherwise successful command.
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
    param([string[]]$Arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $rawOutput = & $AdbPath @Arguments 2>&1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return ($rawOutput | Out-String).Trim()
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
    if (-not $VerifyOnly -and [string]::IsNullOrWhiteSpace([string]$frame.expectedDeviceSerial)) {
        throw "[$name] Mutable deployment requires expectedDeviceSerial"
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
        $beforePackageInfo = Invoke-Adb -Serial $serial -Arguments @('shell', 'dumpsys', 'package', $Package)
        $installedVersionCode = [regex]::Match($beforePackageInfo, 'versionCode=(\d+)').Groups[1].Value
        if (-not $installedVersionCode) { throw "[$name] Unable to determine installed version" }
        if (-not $Rollback -and $approvedFacts.versionCode -lt [int64]$installedVersionCode) {
            throw "[$name] Refusing implicit downgrade from $installedVersionCode to $($approvedFacts.versionCode)"
        }
        if ($Rollback -and $approvedFacts.versionCode -ge [int64]$installedVersionCode) {
            throw "[$name] Rollback target $($approvedFacts.versionCode) is not older than installed $installedVersionCode"
        }
        $installedPathOutput = Invoke-Adb -Serial $serial -Arguments @('shell', 'pm', 'path', $Package)
        $installedPath = [regex]::Match($installedPathOutput, '(?m)^package:(\S+)$').Groups[1].Value
        if (-not $installedPath) { throw "[$name] Unable to locate installed APK" }
        $installedCopy = [System.IO.Path]::GetTempFileName()
        try {
            Invoke-Adb -Serial $serial -Arguments @('pull', $installedPath, $installedCopy) | Out-Null
            $installedFacts = Get-ApkFacts -Path $installedCopy
            if ($installedFacts.applicationId -ne $Package -or
                $installedFacts.signerSha256 -ne $approvedFacts.signerSha256) {
                throw "[$name] Installed package identity or signer differs from target; refusing replacement"
            }
        } finally {
            Remove-Item -LiteralPath $installedCopy -Force -ErrorAction SilentlyContinue
        }
        Write-Host "[$name] Installing approved APK $approvedApkHash"
        $installArguments = if ($Rollback) {
            @('install', '-r', '-d', $approvedApkPath)
        } else {
            @('install', '-r', $approvedApkPath)
        }
        $install = Invoke-Adb -Serial $serial -Arguments $installArguments
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
    if ($deployment -and ([int64]$versionCode -ne $approvedFacts.versionCode -or
        $versionName -ne $approvedFacts.versionName)) {
        throw "[$name] Installed version $versionName ($versionCode) does not match approved target"
    }
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
        apkSha256 = if ($deployment) { $approvedApkHash } else { $null }
        releaseId = if ($deployment) { $requiredApproval } else { $null }
    }
}

$results | Format-Table -AutoSize
if ($OutputJsonPath) {
    $parent = Split-Path -Parent $OutputJsonPath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $OutputJsonPath -Encoding UTF8
}

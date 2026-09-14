function Get-NormalDeploymentAction {
    param(
        [int64]$TargetVersionCode,
        [string]$TargetApkSha256,
        [int64]$InstalledVersionCode,
        [string]$InstalledApkSha256
    )
    if ($TargetVersionCode -lt $InstalledVersionCode) { throw 'implicit downgrade is forbidden' }
    if ($TargetVersionCode -eq $InstalledVersionCode) {
        if ($TargetApkSha256 -ne $InstalledApkSha256) {
            throw 'a different APK already uses this versionCode'
        }
        return 'skip'
    }
    return 'install'
}
function Assert-ObservedInstalledArtifact {
    param([string]$ExpectedSha256, [string]$ObservedSha256)
    if ($ExpectedSha256 -ne $ObservedSha256) { throw 'installed APK hash does not match approved artifact' }
}

function Write-DeploymentResultsJson {
    param([object[]]$Items, [Parameter(Mandatory = $true)][string]$OutputPath)
    $parent = Split-Path -Parent $OutputPath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $temporary = "$OutputPath.tmp"
    ConvertTo-Json -InputObject @($Items) -Depth 6 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $OutputPath -Force
}

function Invoke-AdbOptionalText {
    param([Parameter(Mandatory = $true)][string]$AdbPath, [string[]]$Arguments)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $rawOutput = & $AdbPath @Arguments 2>&1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return ($rawOutput | Out-String).Trim()
}

function Test-AdbTcpPort {
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

function Test-AuthorizedFrameCandidate {
    param(
        [Parameter(Mandatory = $true)][string]$AdbPath,
        [string]$Serial,
        [string]$ExpectedModel,
        [string]$ExpectedDeviceSerial
    )
    if ($Serial -match '^(\d+\.\d+\.\d+\.\d+):5555$') {
        if (-not (Test-AdbTcpPort -Ip $Matches[1])) { return $false }
        & $AdbPath connect $Serial 2>&1 | Out-Null
    }
    if ((Invoke-AdbOptionalText -AdbPath $AdbPath -Arguments @('-s', $Serial, 'get-state')) -ne 'device') {
        return $false
    }
    $model = Invoke-AdbOptionalText -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')
    if ($model -ne $ExpectedModel) { return $false }
    if ($ExpectedDeviceSerial) {
        $deviceSerial = Invoke-AdbOptionalText -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.serialno')
        if ($deviceSerial -ne $ExpectedDeviceSerial) { return $false }
    }
    return $true
}

function Resolve-AuthorizedFrameSerial {
    param(
        [Parameter(Mandatory = $true)]$Frame,
        [Parameter(Mandatory = $true)][string]$AdbPath
    )
    $requested = [string]$Frame.serial
    $expectedModel = [string]$Frame.expectedModel
    $expectedDeviceSerial = [string]$Frame.expectedDeviceSerial
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($requested) { $candidates.Add($requested) }

    $devices = Invoke-AdbOptionalText -AdbPath $AdbPath -Arguments @('devices')
    foreach ($match in [regex]::Matches($devices, '(?m)^(\S+)\s+device$')) {
        $candidate = $match.Groups[1].Value
        if (-not $candidates.Contains($candidate)) { $candidates.Add($candidate) }
    }
    foreach ($candidate in $candidates) {
        if (Test-AuthorizedFrameCandidate -AdbPath $AdbPath -Serial $candidate `
                -ExpectedModel $expectedModel -ExpectedDeviceSerial $expectedDeviceSerial) {
            return $candidate
        }
    }

    $scanSubnet = [string]$Frame.scanSubnet
    if ($scanSubnet) {
        Write-Host "[$($Frame.name)] Known endpoints failed; scanning $scanSubnet.0/24 for ADB"
        foreach ($ip in (Find-OpenAdbAddresses -Prefix $scanSubnet)) {
            $candidate = "${ip}:5555"
            if (Test-AuthorizedFrameCandidate -AdbPath $AdbPath -Serial $candidate `
                    -ExpectedModel $expectedModel -ExpectedDeviceSerial $expectedDeviceSerial) {
                return $candidate
            }
        }
    }
    return $null
}

Export-ModuleMember -Function Get-NormalDeploymentAction, Assert-ObservedInstalledArtifact, `
    Write-DeploymentResultsJson, Resolve-AuthorizedFrameSerial

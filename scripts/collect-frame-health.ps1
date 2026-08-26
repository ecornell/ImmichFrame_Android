[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F:.]+$')]
    [string]$FrameIp,

    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$ExpectedDeviceSerial = 'c3d9b8674f4b94f6',

    [string]$ExpectedModel = 'WF1561',

    [string]$PanelPackage = 'com.zeasn.frame',

    [bool]$RequirePanelProcess = $true,

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe",

    [string]$MonitorDirectory = "$env:LOCALAPPDATA\ImmichFrame\monitor",

    [string]$OutputPath = "$env:LOCALAPPDATA\ImmichFrame\health\latest.json",

    [ValidateRange(1, 2000)]
    [int]$LogLines = 500
)

$ErrorActionPreference = 'Stop'
$Package = 'com.immichframe.immichframe.debug'
$monitorScript = Join-Path $PSScriptRoot 'monitor-wf1561.ps1'
if (-not (Test-Path -LiteralPath $monitorScript)) { throw "Monitor script was not found: $monitorScript" }

# The monitor performs only reads against the frame and resolves the current random IP by hardware
# serial. Its host-side JSON/history writes provide the evidence trail used by qualification.
& $monitorScript -FrameIp $FrameIp -ExpectedDeviceSerial $ExpectedDeviceSerial `
    -ExpectedModel $ExpectedModel -PanelPackage $PanelPackage `
    -RequirePanelProcess $RequirePanelProcess -AdbPath $AdbPath -LogDirectory $MonitorDirectory | Out-Null
$monitor = Get-Content -LiteralPath (Join-Path $MonitorDirectory 'latest.json') -Raw | ConvertFrom-Json

$logSignals = [ordered]@{
    matchedLines = 0
    fatalExceptionCount = 0
    anrCount = 0
    outOfMemoryCount = 0
    networkErrorCount = 0
    authenticationErrorCount = 0
}
$packageInfo = ''
$collectionErrors = @()
if ($monitor.serial -and $monitor.status -ne 'offline') {
    $packageInfo = (& $AdbPath -s $monitor.serial shell dumpsys package $Package 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        $collectionErrors += 'package information unavailable'
        $packageInfo = ''
    }
    $rawLogcat = (& $AdbPath -s $monitor.serial logcat -v threadtime -d -t $LogLines '*:V' 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        $collectionErrors += 'filtered logcat unavailable'
    } else {
        # Persist classifications only, never log text. This avoids retaining credentials that an
        # unexpected library or server response may have written in an unfamiliar format.
        $pidPattern = if ($monitor.appPid) { "\s$([regex]::Escape([string]$monitor.appPid))\s" } else { '(?!)' }
        $appLines = @($rawLogcat -split '[\r\n]+' | Where-Object {
            $_ -match $pidPattern -or $_ -match [regex]::Escape($Package)
        })
        $joined = $appLines -join "`n"
        $logSignals.matchedLines = $appLines.Count
        $logSignals.fatalExceptionCount = ([regex]::Matches($joined, '(?i)FATAL EXCEPTION')).Count
        $logSignals.anrCount = ([regex]::Matches($joined, '(?i)ANR in|not responding')).Count
        $logSignals.outOfMemoryCount = ([regex]::Matches($joined, '(?i)OutOfMemoryError|low memory')).Count
        $logSignals.networkErrorCount = ([regex]::Matches($joined, '(?i)UnknownHost|ConnectException|SocketTimeout|SSLException')).Count
        $logSignals.authenticationErrorCount = ([regex]::Matches($joined, '(?i)unauthorized|forbidden|authentication failed|HTTP 40[13]')).Count
    }
}

$versionCode = [regex]::Match($packageInfo, 'versionCode=(\d+)').Groups[1].Value
$versionName = [regex]::Match($packageInfo, 'versionName=([^\s]+)').Groups[1].Value
$installedApkSha256 = ''
if ($monitor.serial -and $monitor.status -ne 'offline') {
    $installedPathText = (& $AdbPath -s $monitor.serial shell pm path $Package 2>&1 | Out-String)
    $installedPath = [regex]::Match($installedPathText, 'package:(\S+)').Groups[1].Value
    if ($installedPath) {
        $temporaryApk = [System.IO.Path]::GetTempFileName()
        try {
            $previousErrorActionPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                & $AdbPath -s $monitor.serial pull $installedPath $temporaryApk 2>&1 | Out-Null
                $pullExitCode = $LASTEXITCODE
            } finally { $ErrorActionPreference = $previousErrorActionPreference }
            if ($pullExitCode -eq 0) {
                $installedApkSha256 = (Get-FileHash -LiteralPath $temporaryApk -Algorithm SHA256).Hash.ToLowerInvariant()
            } else { $collectionErrors += 'installed APK hash unavailable' }
        } finally { Remove-Item -LiteralPath $temporaryApk -Force -ErrorAction SilentlyContinue }
    } else { $collectionErrors += 'installed APK path unavailable' }
}
$bundle = [ordered]@{
    schemaVersion = 1
    collectedAt = (Get-Date).ToUniversalTime().ToString('o')
    readOnly = $true
    monitor = $monitor
    package = [ordered]@{ name = $Package; versionCode = $versionCode; versionName = $versionName;
        apkSha256 = $installedApkSha256 }
    logSignals = $logSignals
    collectionErrors = $collectionErrors
}

$parent = Split-Path -Parent $OutputPath
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$temporary = "$OutputPath.tmp"
$bundle | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $temporary -Encoding UTF8
Move-Item -LiteralPath $temporary -Destination $OutputPath -Force
[pscustomobject]$bundle

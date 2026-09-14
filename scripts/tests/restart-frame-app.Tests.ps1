Describe 'identity-guarded app restart' {
    BeforeEach {
        $script:fakeAdb = Join-Path $TestDrive 'fake-adb.ps1'
        $script:commandLog = Join-Path $TestDrive 'adb-commands.txt'
        Remove-Item -LiteralPath $script:commandLog -Force -ErrorAction SilentlyContinue
        $env:FAKE_ADB_LOG = $script:commandLog
        @'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$ToolArgs)
$global:LASTEXITCODE = 0
$joined = $ToolArgs -join ' '
Add-Content -LiteralPath $env:FAKE_ADB_LOG -Value $joined
switch -Regex ($joined) {
  'devices$' { "List of devices attached`nFAKE device"; break }
  'get-state$' { 'device'; break }
  'getprop ro.product.model' { 'WF1561'; break }
  'getprop ro.serialno' { if ($env:FAKE_WRONG_IDENTITY) { 'OTHER' } else { 'c3d9b8674f4b94f6' }; break }
  'pm path' { 'package:/data/app/base.apk'; break }
  'dumpsys activity activities' { 'mResumedActivity com.immichframe.immichframe.debug'; break }
  'dumpsys power' { "mWakefulness=Awake`nDisplay Power: state=ON"; break }
  'pidof' { '123'; break }
  'am force-stop' { ''; break }
  'am start -n' { 'Starting: Intent'; break }
  default { $global:LASTEXITCODE = 1 }
}
'@ | Set-Content -LiteralPath $script:fakeAdb
        $script:manifest = Join-Path $TestDrive 'frames.json'
        @{ frames = @(@{
            name = 'WF1561'
            serial = 'FAKE'
            expectedModel = 'WF1561'
            expectedDeviceSerial = 'c3d9b8674f4b94f6'
        }) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $script:manifest
        $script:output = Join-Path $TestDrive 'restart.json'
        $env:FAKE_WRONG_IDENTITY = $null
    }

    AfterEach {
        $env:FAKE_ADB_LOG = $null
        $env:FAKE_WRONG_IDENTITY = $null
    }

    It 'restarts only the approved package and retains before and after evidence' {
        & "$PSScriptRoot/../restart-frame-app.ps1" -ManifestPath $manifest -FrameName 'WF1561' `
            -ExpectedModel 'WF1561' -ExpectedDeviceSerial 'c3d9b8674f4b94f6' `
            -AdbPath $fakeAdb -OutputJsonPath $output | Out-Null

        $result = (Get-Content -LiteralPath $output -Raw | ConvertFrom-Json)[0]
        $result.status | Should Be 'restarted-and-verified'
        $result.before.running | Should Be $true
        $result.after.resumed | Should Be $true
        $commands = Get-Content -LiteralPath $commandLog -Raw
        $commands | Should Match 'shell am force-stop com\.immichframe\.immichframe\.debug'
        $commands | Should Match 'shell am start -n com\.immichframe\.immichframe\.debug/com\.immichframe\.immichframe\.MainActivity'
        $commands | Should Not Match 'reboot|pm clear|install|set-home-activity'
    }

    It 'does not mutate a device with the wrong hardware identity' {
        $env:FAKE_WRONG_IDENTITY = '1'
        { & "$PSScriptRoot/../restart-frame-app.ps1" -ManifestPath $manifest -FrameName 'WF1561' `
            -ExpectedModel 'WF1561' -ExpectedDeviceSerial 'c3d9b8674f4b94f6' `
            -AdbPath $fakeAdb -OutputJsonPath $output } | Should Throw

        $result = (Get-Content -LiteralPath $output -Raw | ConvertFrom-Json)[0]
        $result.status | Should Be 'preflight-failed-no-mutation'
        (Get-Content -LiteralPath $commandLog -Raw) | Should Not Match 'force-stop|am start'
    }
}

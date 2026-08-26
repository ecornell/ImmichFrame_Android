Import-Module "$PSScriptRoot/../DeploymentPolicy.psm1" -Force

Describe 'deployment version and hash policy' {
    It 'installs a strictly newer version' {
        Get-NormalDeploymentAction 55 'new' 54 'old' | Should Be 'install'
    }
    It 'skips an identical same-version APK' {
        Get-NormalDeploymentAction 54 'same' 54 'same' | Should Be 'skip'
    }
    It 'rejects a changed same-version APK' {
        { Get-NormalDeploymentAction 54 'new' 54 'old' } | Should Throw
    }
    It 'rejects an implicit rollback' {
        { Get-NormalDeploymentAction 53 'old' 54 'new' } | Should Throw
    }
    It 'rejects an observed installed hash mismatch' {
        { Assert-ObservedInstalledArtifact -ExpectedSha256 'approved' -ObservedSha256 'other' } | Should Throw
    }
    It 'retains completed-frame evidence when a later operation fails' {
        $journal = Join-Path $TestDrive 'partial.json'
        Write-DeploymentResultsJson -Items @([pscustomobject]@{ name='first'; status='verified' }) -OutputPath $journal
        try { throw 'second frame failed' } catch { }
        $saved = Get-Content $journal -Raw | ConvertFrom-Json
        $saved[0].name | Should Be 'first'
        $saved[0].status | Should Be 'verified'
    }
}

Describe 'fake ADB verify-only integration' {
    It 'persists verified fake-device evidence' {
        $fakeAdb = Join-Path $TestDrive 'fake-adb.ps1'
        @'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$ToolArgs)
$global:LASTEXITCODE = 0
$joined = $ToolArgs -join ' '
switch -Regex ($joined) {
  'devices$' { "List of devices attached`nFAKE device"; break }
  'get-state$' { 'device'; break }
  'getprop ro.product.model' { 'fake-model'; break }
  'getprop ro.serialno' { 'FAKE-SERIAL'; break }
  'resolve-activity' { 'com.immichframe.immichframe.debug/com.immichframe.immichframe.MainActivity'; break }
  'dumpsys package' { "versionCode=54`nversionName=1.0.54.0-debug"; break }
  'dumpsys activity' { 'mResumedActivity com.immichframe.immichframe.debug'; break }
  'pidof' { '123'; break }
  default { $global:LASTEXITCODE = 1 }
}
'@ | Set-Content $fakeAdb
        $manifest = Join-Path $TestDrive 'frames.json'
        @{ frames = @(@{ name='fake'; serial='FAKE'; expectedModel='fake-model'; expectedDeviceSerial='FAKE-SERIAL' }) } |
            ConvertTo-Json -Depth 4 | Set-Content $manifest
        $output = Join-Path $TestDrive 'verify.json'
        & "$PSScriptRoot/../deploy-frames.ps1" -ManifestPath $manifest -AdbPath $fakeAdb -VerifyOnly -OutputJsonPath $output
        Test-Path $output | Should Be $true
        (Get-Content $output -Raw | ConvertFrom-Json)[0].homeCorrect | Should Be $true
    }
}

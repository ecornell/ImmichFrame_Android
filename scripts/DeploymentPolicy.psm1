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

Export-ModuleMember -Function Get-NormalDeploymentAction, Assert-ObservedInstalledArtifact, Write-DeploymentResultsJson

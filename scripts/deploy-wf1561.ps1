[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F:.]+$')]
    [string]$FrameIp,

    [string]$ApkPath = "$env:LOCALAPPDATA\Temp\ImmichFrame-debug.apk",

    [string]$AdbPath = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe"
)

throw 'This unsafe legacy deployment path is disabled. Use prepare-deployment.sh, audit-release.py, and deploy-frames.ps1 as documented in docs/DEPLOYMENT.md.'

param(
    [string]$Apk = "out\stride.apk"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }

$apkPath = Join-Path $root $Apk
if (-not (Test-Path $apkPath)) { throw "APK not found: $apkPath. Run .\build.ps1 first." }

& $adb devices
& $adb install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw "Install failed. Is the phone connected with USB debugging enabled?" }

& $adb shell am start -n "com.darsha.steps/.MainActivity"
Write-Output "Installed and launched."

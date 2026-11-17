<#
.SYNOPSIS
  Creates the Android TV emulator used by PDFr's TV checks.

.DESCRIPTION
  Finds the Android SDK, installs the TV system image when it is missing, and
  creates (or recreates) a 1080p TV AVD. The default name is pdfr_tv.

  A TV AVD has no touchscreen, so the app is driven with a D-pad: the arrow
  keys move focus and KEYCODE_DPAD_CENTER activates the focused button. The
  generated config enables the D-pad and a keyboard, which is what the
  accessibility tests use when they Tab through the toolbar.

  The image is large (about 1.5 GB), so the first run downloads it. Use
  -SkipInstall to fail instead of downloading when the image is missing.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\create-tv-avd.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\create-tv-avd.ps1 -Name pdfr_tv -RamMb 2048
#>
[CmdletBinding()]
param(
    [string]$Name = 'pdfr_tv',
    [int]$RamMb = 2048,
    [string]$Api = '34',
    [string]$Abi = 'x86_64',
    [string]$Tag = 'android-tv',
    [string]$Device = 'tv_1080p',
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

function Find-Sdk {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return (Resolve-Path $env:ANDROID_SDK_ROOT).Path
    }
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        return (Resolve-Path $env:ANDROID_HOME).Path
    }
    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $default) { return $default }
    throw 'Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME.'
}

function Find-CommandTool([string]$Sdk, [string]$FileName) {
    $roots = @(
        (Join-Path $Sdk 'cmdline-tools'),
        (Join-Path $Sdk 'tools')
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem $root -Recurse -Filter $FileName -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

$sdk = Find-Sdk
Write-Host "[PDFr] Android SDK: $sdk"

$sdkmanager = Find-CommandTool $sdk 'sdkmanager.bat'
$avdmanager = Find-CommandTool $sdk 'avdmanager.bat'
if (-not $sdkmanager -or -not $avdmanager) {
    throw 'sdkmanager.bat/avdmanager.bat not found. Install Android command-line tools.'
}

$package = "system-images;android-$Api;$Tag;$Abi"
$imageDir = Join-Path $sdk "system-images\android-$Api\$Tag\$Abi"
if (-not (Test-Path $imageDir)) {
    if ($SkipInstall) {
        throw "$package is not installed. Run without -SkipInstall, or install it with sdkmanager."
    }
    Write-Host "[PDFr] Installing $package (large download)..."
    & $sdkmanager $package
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $imageDir)) {
        throw "sdkmanager failed to install $package. Run '$sdkmanager --licenses' and try again."
    }
}

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android\avd' }
$configPath = Join-Path $avdHome "$Name.avd\config.ini"

Write-Host "[PDFr] Creating TV AVD '$Name' ($Device, $package, ${RamMb} MB RAM)..."
# 'no' declines the optional custom-hardware-profile prompt.
'no' | & $avdmanager create avd --force --name $Name --package $package --device $Device --sdcard 512M
if ($LASTEXITCODE -ne 0) {
    throw "avdmanager failed to create '$Name'."
}

if (-not (Test-Path $configPath)) {
    throw "AVD config was not created at $configPath"
}

# avdmanager does not expose RAM or input flags, so update the generated config.
# The D-pad is the TV remote's only pointer; without it the toolbar cannot be
# focused at all. The keyboard makes the Tab-based accessibility checks work on
# the same AVD.
$config = Get-Content $configPath |
    Where-Object {
        $_ -notmatch '^hw\.ramSize=' -and
        $_ -notmatch '^vm\.heapSize=' -and
        $_ -notmatch '^hw\.dPad=' -and
        $_ -notmatch '^hw\.keyboard='
    }
$config += "hw.ramSize=$RamMb"
$config += 'vm.heapSize=256'
$config += 'hw.dPad=yes'
$config += 'hw.keyboard=yes'
Set-Content -Path $configPath -Value $config -Encoding ASCII

Write-Host ''
Write-Host "[PDFr] TV AVD '$Name' is ready."
Write-Host "       Start it with: tools\start-emulator.bat $Name"
Write-Host "       Run the TV checks with: tools\run-instrumented-tests.bat com.karako.pdfr.TvSupportInstrumentedTest"
Write-Host "       Or set PDFR_AVD=$Name before running the other helpers."

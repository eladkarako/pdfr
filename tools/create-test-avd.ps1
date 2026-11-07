<#
.SYNOPSIS
  Creates the Android emulator used by PDFr's manual and instrumented tests.

.DESCRIPTION
  Finds the Android SDK, installs the requested system image when it is
  missing, and creates (or recreates) a Pixel 5 AVD. The default name is
  pdfr_full and the default RAM is 1536 MB, which is enough for API 35 x86_64
  without reserving as much memory as the old 2 GB AVD.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\create-test-avd.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\create-test-avd.ps1 -Name pdfr_full -RamMb 1536
#>
[CmdletBinding()]
param(
    [string]$Name = 'pdfr_full',
    [int]$RamMb = 1536,
    [string]$Api = '35',
    [string]$Abi = 'x86_64',
    [string]$Tag = 'default',
    [string]$Device = 'pixel_5'
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
    Write-Host "[PDFr] Installing $package ..."
    & $sdkmanager $package
    if ($LASTEXITCODE -ne 0) {
        throw "sdkmanager failed to install $package. Run '$sdkmanager --licenses' and try again."
    }
}

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android\avd' }
$configPath = Join-Path $avdHome "$Name.avd\config.ini"

Write-Host "[PDFr] Creating AVD '$Name' ($Device, $package, ${RamMb} MB RAM)..."
# 'no' declines the optional custom-hardware-profile prompt.
'no' | & $avdmanager create avd --force --name $Name --package $package --device $Device --sdcard 512M
if ($LASTEXITCODE -ne 0) {
    throw "avdmanager failed to create '$Name'."
}

if (-not (Test-Path $configPath)) {
    throw "AVD config was not created at $configPath"
}

# avdmanager does not expose a RAM flag, so update the generated config.
$config = Get-Content $configPath |
    Where-Object { $_ -notmatch '^hw\.ramSize=' -and $_ -notmatch '^vm\.heapSize=' }
$config += "hw.ramSize=$RamMb"
$config += 'vm.heapSize=256'
Set-Content -Path $configPath -Value $config -Encoding ASCII

Write-Host ''
Write-Host "[PDFr] AVD '$Name' is ready."
Write-Host "       Start it with: tools\start-emulator.bat $Name"
Write-Host "       Or set PDFR_AVD=$Name before running the other helpers."

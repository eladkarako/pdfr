<#
.SYNOPSIS
  Creates the Android TV emulator used by PDFr's TV checks.

.DESCRIPTION
  Finds the Android SDK, installs the newest TV system image for the requested
  ABI when it is missing, and creates (or recreates) a 1080p TV AVD. The default
  name is pdfr_tv. No API level is hard-coded: the newest image installed or
  offered by sdkmanager is used, so a tag that only ships for some ABI/API
  combinations (such as Android TV) still gets a valid image.

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
    [string]$Api = '',
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

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$InputText
    )

    # sdkmanager and avdmanager write warnings and progress to stderr. The
    # script runs with ErrorActionPreference = 'Stop', which would turn that
    # text into a terminating NativeCommandError (for example the sdkmanager
    # deprecation warning), so stderr is collected as plain text instead.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = if ($PSBoundParameters.ContainsKey('InputText')) {
            $InputText | & $FilePath @Arguments 2>&1
        } else {
            & $FilePath @Arguments 2>&1
        }
        $script:NativeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }

    return $output
}

function Get-LatestSystemImageApi {
    param(
        [string]$Sdk,
        [string]$Tag,
        [string]$Abi,
        [string]$SdkManager,
        [switch]$InstalledOnly
    )

    $apis = New-Object System.Collections.Generic.List[string]

    # Installed images are the fastest source and work offline.
    $root = Join-Path $Sdk 'system-images'
    if (Test-Path $root) {
        Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $api = $_.Name -replace '^android-', ''
            if (Test-Path (Join-Path $_.FullName (Join-Path $Tag $Abi))) {
                $apis.Add($api)
            }
        }
    }

    # Ask sdkmanager for images that can be installed. The tool names are
    # stable across SDK versions, so this follows the newest release without
    # hard-coding an API level.
    if (-not $InstalledOnly -and $SdkManager) {
        $list = (Invoke-Native -FilePath $SdkManager -Arguments @('--list') | Out-String)
        $list = $list -replace "`e\[[0-9;]*m", ''
        $pattern = 'system-images[;/]android-([^;/ ]+)[;/]' +
            [regex]::Escape($Tag) + '[;/]' + [regex]::Escape($Abi) + '(?:\s|$)'
        foreach ($line in ($list -split "`r?`n")) {
            $match = [regex]::Match($line, $pattern)
            if ($match.Success) { $apis.Add($match.Groups[1].Value) }
        }
    }

    if ($apis.Count -eq 0) { return $null }

    return $apis |
        Sort-Object -Unique |
        Sort-Object {
            $version = $_ -replace '[^0-9.].*$', ''
            try { [version]$version } catch { [version]'0.0' }
        } -Descending |
        Select-Object -First 1
}

$sdk = Find-Sdk
Write-Host "[PDFr] Android SDK: $sdk"

$sdkmanager = Find-CommandTool $sdk 'sdkmanager.bat'
$avdmanager = Find-CommandTool $sdk 'avdmanager.bat'
if (-not $sdkmanager -or -not $avdmanager) {
    throw 'sdkmanager.bat/avdmanager.bat not found. Install Android command-line tools.'
}

if (-not $Api) {
    $Api = Get-LatestSystemImageApi -Sdk $sdk -Tag $Tag -Abi $Abi -SdkManager $sdkmanager -InstalledOnly:$SkipInstall
    if (-not $Api) {
        throw "No installed Android $Tag $Abi system image was found. Run without -SkipInstall, or pass -Api."
    }
    Write-Host "[PDFr] Newest $Tag $Abi image: android-$Api"
}

$package = "system-images;android-$Api;$Tag;$Abi"
$imageDir = Join-Path $sdk "system-images\android-$Api\$Tag\$Abi"
if (-not (Test-Path $imageDir)) {
    if ($SkipInstall) {
        throw "$package is not installed. Run without -SkipInstall, or install it with sdkmanager."
    }
    Write-Host "[PDFr] Installing $package (large download)..."
    Invoke-Native -FilePath $sdkmanager -Arguments @($package) | Out-Host
    if ($script:NativeExitCode -ne 0 -or -not (Test-Path $imageDir)) {
        throw "sdkmanager failed to install $package. Run '$sdkmanager --licenses' and try again."
    }
}

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android\avd' }
$configPath = Join-Path $avdHome "$Name.avd\config.ini"

Write-Host "[PDFr] Creating TV AVD '$Name' ($Device, $package, ${RamMb} MB RAM)..."
# 'no' declines the optional custom-hardware-profile prompt.
Invoke-Native -FilePath $avdmanager -InputText 'no' -Arguments @(
    'create', 'avd', '--force', '--name', $Name, '--package', $package,
    '--device', $Device, '--sdcard', '512M'
) | Out-Host
if ($script:NativeExitCode -ne 0) {
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

<#
.SYNOPSIS
  Creates the Android emulator used by PDFr's manual and instrumented tests.

.DESCRIPTION
  Finds the Android SDK, installs the newest system image for the requested
  tag and ABI, and creates (or recreates) a Pixel 5 AVD. The default name is
  pdfr_full and the default RAM is 1536 MB. No API level is hard-coded: the
  newest image installed or offered by sdkmanager is used, so the AVD follows
  the current SDK (currently 37) automatically.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\create-test-avd.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\create-test-avd.ps1 -Name pdfr_full -RamMb 1536
#>
[CmdletBinding()]
param(
    [string]$Name = 'pdfr_full',
    [int]$RamMb = 1536,
    [string]$Api = '',
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

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$InputText,
        [switch]$Stream
    )

    # sdkmanager and avdmanager write warnings and progress to stderr. The
    # script runs with ErrorActionPreference = 'Stop', which would turn that
    # text into a terminating NativeCommandError (for example the sdkmanager
    # deprecation warning), so stderr is handled as plain text instead.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $errorFile = $null
    try {
        if ($Stream) {
            # Long downloads should show progress, so stdout is streamed and
            # stderr is parked in a temp file that is shown only on failure.
            $errorFile = [System.IO.Path]::GetTempFileName()
            if ($PSBoundParameters.ContainsKey('InputText')) {
                $InputText | & $FilePath @Arguments 2> $errorFile |
                    ForEach-Object { Write-Host $_ }
            } else {
                & $FilePath @Arguments 2> $errorFile |
                    ForEach-Object { Write-Host $_ }
            }
            $script:NativeExitCode = $LASTEXITCODE
            if ($script:NativeExitCode -ne 0) {
                Get-Content -LiteralPath $errorFile -ErrorAction SilentlyContinue |
                    ForEach-Object { Write-Host $_ }
            }
            return
        }

        $output = if ($PSBoundParameters.ContainsKey('InputText')) {
            $InputText | & $FilePath @Arguments 2>&1
        } else {
            & $FilePath @Arguments 2>&1
        }
        $script:NativeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        if ($errorFile) {
            Remove-Item -LiteralPath $errorFile -Force -ErrorAction SilentlyContinue
        }
    }

    # Merged stderr arrives as ErrorRecords. Return them as plain text so the
    # caller's output does not look like the script itself failed.
    return @($output) | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { $_ }
    }
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
    $Api = Get-LatestSystemImageApi -Sdk $sdk -Tag $Tag -Abi $Abi -SdkManager $sdkmanager
    if (-not $Api) {
        throw "No Android $Tag $Abi system image is available. Install one with sdkmanager, or pass -Api."
    }
    Write-Host "[PDFr] Newest $Tag $Abi image: android-$Api"
}

$package = "system-images;android-$Api;$Tag;$Abi"
# sdkmanager.bat splits unquoted semicolons into separate arguments, so it gets
# the slash form (the sdk-style path it documents anyway).
$sdkPackage = $package -replace ';', '/'
$imageDir = Join-Path $sdk "system-images\android-$Api\$Tag\$Abi"
if (-not (Test-Path $imageDir)) {
    Write-Host "[PDFr] Installing $package ..."
    Invoke-Native -FilePath $sdkmanager -Arguments @($sdkPackage) -Stream
    if ($script:NativeExitCode -ne 0 -or -not (Test-Path $imageDir)) {
        throw "sdkmanager failed to install $package. Run '$sdkmanager --licenses' and try again."
    }
}

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android\avd' }
$configPath = Join-Path $avdHome "$Name.avd\config.ini"

Write-Host "[PDFr] Creating AVD '$Name' ($Device, $package, ${RamMb} MB RAM)..."
# 'no' declines the optional custom-hardware-profile prompt.
Invoke-Native -FilePath $avdmanager -InputText 'no' -Stream -Arguments @(
    'create', 'avd', '--force', '--name', $Name, '--package', $package,
    '--device', $Device, '--sdcard', '512M'
)
if ($script:NativeExitCode -ne 0) {
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

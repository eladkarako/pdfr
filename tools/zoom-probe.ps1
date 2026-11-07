#Requires -Version 5.1
<#
.SYNOPSIS
  Records a screenshot sequence while pinching or panning, for checking that
  zoom resizes the page live and settles without a jump.

.DESCRIPTION
  The pinch and pan gestures use Linux multitouch events, because `adb shell
  input` cannot press two fingers. They need `adb root`; on an emulator that is
  already rooted, pass -SkipRoot so adbd does not restart and resize the
  viewport between the "before" shot and the gesture.

  Every run writes before/down/mid/late/settled PNGs to build\zoom-probe\. The
  late and settled files are hashed: when they are identical, the debounced
  re-render or reflow landed at exactly the live size.

  Coordinates default to the pdfr_full AVD (1080x2340). Override
  -ScreenWidth/-ScreenHeight and -EventDevice (find it with
  `adb shell getevent -lp`, the device whose name contains multi_touch).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pinch-out

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pinch-out -CenterY 0.17 -SkipRoot

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pan -StartY 1700 -EndY 900
#>
[CmdletBinding()]
param(
    [ValidateSet('pinch-out', 'pinch-in', 'pan', 'double-tap')]
    [string]$Action = 'pinch-out',

    [string]$EventDevice = '/dev/input/event2',
    [int]$ScreenWidth = 1080,
    [int]$ScreenHeight = 2340,

    # Fraction of the screen height where the pinch centroid sits. Use a spot
    # with recognisable content, for example 0.17 for the title of a PDF page.
    [double]$CenterY = 0.45,

    [int]$StartGap = 280,
    [int]$EndGap = 620,
    [int]$Gap = 420,

    [int]$StartY = 1700,
    [int]$EndY = 900,

    [int]$Steps = 8,
    [int]$SettleMs = 700,

    [string]$OutDir,
    [string]$Prefix = 'zoom',
    [switch]$SkipRoot
)

$ErrorActionPreference = 'Stop'

function Resolve-Adb {
    if ($env:ADB -and (Test-Path $env:ADB)) { return $env:ADB }

    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $candidate)) {
        throw 'adb was not found. Set ANDROID_SDK_ROOT or ADB.'
    }
    return $candidate
}

$adb = Resolve-Adb
if (-not $OutDir) {
    $repo = Split-Path -Parent $PSScriptRoot
    $OutDir = Join-Path $repo 'build\zoom-probe'
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

function ConvertTo-Raw([int]$Value, [int]$ScreenSize) {
    return [int][Math]::Round($Value * 32767.0 / $ScreenSize)
}

function Send-Events([System.Collections.Generic.List[string]]$Events) {
    Invoke-Adb shell ($Events -join '; ')
}

function Ensure-Root {
    if ($SkipRoot) {
        $identity = ((& $adb shell id) -join '').Trim()
        if ($identity -notmatch 'uid=0') {
            throw "Raw multitouch needs root. Run 'adb root' first, or omit -SkipRoot."
        }
        return
    }

    & $adb root | Out-Null
    Start-Sleep -Seconds 1
    & $adb wait-for-device | Out-Null

    $identity = ((& $adb shell id) -join '').Trim()
    if ($identity -notmatch 'uid=0') {
        throw "Raw multitouch needs adb root. Use an emulator, or test the pinch by hand."
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Save-Shot([string]$Name) {
    $remote = "/sdcard/pdfr-$Prefix-$Name.png"
    $local = Join-Path $OutDir "$Prefix-$Name.png"
    Invoke-Adb shell "screencap -p $remote"
    Invoke-Adb pull $remote $local | Out-Null
    Invoke-Adb shell rm $remote
    Write-Host "[PDFr] $local"
}

$device = $EventDevice
$centerX = [int]($ScreenWidth / 2)
$centerY = [int]($ScreenHeight * $CenterY)

Ensure-Root
Save-Shot 'before'
Start-Sleep -Milliseconds 150

function Send-Down([int]$LeftX, [int]$RightX, [int]$Y) {
    $rawY = ConvertTo-Raw $Y $ScreenHeight
    $events = [System.Collections.Generic.List[string]]::new()
    $events.Add("sendevent $device 3 47 0")
    $events.Add("sendevent $device 3 57 11")
    $events.Add("sendevent $device 3 53 $(ConvertTo-Raw $LeftX $ScreenWidth)")
    $events.Add("sendevent $device 3 54 $rawY")
    $events.Add("sendevent $device 3 58 512")
    $events.Add("sendevent $device 3 48 100")
    $events.Add("sendevent $device 3 47 1")
    $events.Add("sendevent $device 3 57 12")
    $events.Add("sendevent $device 3 53 $(ConvertTo-Raw $RightX $ScreenWidth)")
    $events.Add("sendevent $device 3 54 $rawY")
    $events.Add("sendevent $device 3 58 512")
    $events.Add("sendevent $device 3 48 100")
    $events.Add("sendevent $device 1 330 1")
    $events.Add("sendevent $device 0 0 0")
    Send-Events $events
}

function Send-Move([int]$LeftX, [int]$RightX, [int]$Y) {
    $rawY = ConvertTo-Raw $Y $ScreenHeight
    $events = [System.Collections.Generic.List[string]]::new()
    $events.Add("sendevent $device 3 47 0")
    $events.Add("sendevent $device 3 53 $(ConvertTo-Raw $LeftX $ScreenWidth)")
    $events.Add("sendevent $device 3 54 $rawY")
    $events.Add("sendevent $device 3 47 1")
    $events.Add("sendevent $device 3 53 $(ConvertTo-Raw $RightX $ScreenWidth)")
    $events.Add("sendevent $device 3 54 $rawY")
    $events.Add("sendevent $device 0 0 0")
    Send-Events $events
}

function Send-Up {
    $events = [System.Collections.Generic.List[string]]::new()
    $events.Add("sendevent $device 3 47 0")
    $events.Add("sendevent $device 3 57 -1")
    $events.Add("sendevent $device 3 47 1")
    $events.Add("sendevent $device 3 57 -1")
    $events.Add("sendevent $device 1 330 0")
    $events.Add("sendevent $device 0 0 0")
    Send-Events $events
}

switch ($Action) {
    'pinch-out' {
        Write-Host "[PDFr] Pinching out (zoom in) around y=$centerY..."
        $startLeft = $centerX - [int]($StartGap / 2)
        $startRight = $centerX + [int]($StartGap / 2)
        $endLeft = $centerX - [int]($EndGap / 2)
        $endRight = $centerX + [int]($EndGap / 2)

        Send-Down $startLeft $startRight $centerY
        Start-Sleep -Milliseconds 120
        Save-Shot 'down'

        $midGap = [int]($StartGap + ($EndGap - $StartGap) * 0.45)
        Send-Move ($centerX - [int]($midGap / 2)) ($centerX + [int]($midGap / 2)) $centerY
        Start-Sleep -Milliseconds 150
        Save-Shot 'mid'

        Send-Move $endLeft $endRight $centerY
        Start-Sleep -Milliseconds 150
        Save-Shot 'late'

        Send-Up
    }
    'pinch-in' {
        Write-Host "[PDFr] Pinching in (zoom out) around y=$centerY..."
        $startLeft = $centerX - [int]($StartGap / 2)
        $startRight = $centerX + [int]($StartGap / 2)
        $endLeft = $centerX - [int]($EndGap / 2)
        $endRight = $centerX + [int]($EndGap / 2)

        Send-Down $startLeft $startRight $centerY
        Start-Sleep -Milliseconds 120
        Save-Shot 'down'

        $midGap = [int]($StartGap + ($EndGap - $StartGap) * 0.45)
        Send-Move ($centerX - [int]($midGap / 2)) ($centerX + [int]($midGap / 2)) $centerY
        Start-Sleep -Milliseconds 150
        Save-Shot 'mid'

        Send-Move $endLeft $endRight $centerY
        Start-Sleep -Milliseconds 150
        Save-Shot 'late'

        Send-Up
    }
    'pan' {
        Write-Host "[PDFr] Two-finger pan from y=$StartY to y=$EndY..."
        $leftX = $centerX - [int]($Gap / 2)
        $rightX = $centerX + [int]($Gap / 2)

        Send-Down $leftX $rightX $StartY
        Start-Sleep -Milliseconds 120
        Save-Shot 'down'

        $step = ($EndY - $StartY) / [double]$Steps
        $half = [int]($Steps / 2)
        for ($i = 1; $i -le $half; $i++) {
            Send-Move $leftX $rightX ([int]($StartY + $step * $i))
            Start-Sleep -Milliseconds 25
        }
        Start-Sleep -Milliseconds 100
        Save-Shot 'mid'

        for ($i = $half + 1; $i -le $Steps; $i++) {
            Send-Move $leftX $rightX ([int]($StartY + $step * $i))
            Start-Sleep -Milliseconds 25
        }
        Send-Move $leftX $rightX $EndY
        Start-Sleep -Milliseconds 150
        Save-Shot 'late'

        Send-Up
    }
    'double-tap' {
        Write-Host '[PDFr] Double-tapping the centre of the screen...'
        Invoke-Adb shell input tap $centerX $centerY
        Start-Sleep -Milliseconds 80
        Invoke-Adb shell input tap $centerX $centerY
    }
}

Start-Sleep -Milliseconds $SettleMs
Save-Shot 'settled'

$late = Join-Path $OutDir "$Prefix-late.png"
$settled = Join-Path $OutDir "$Prefix-settled.png"
if ((Test-Path $late) -and (Test-Path $settled)) {
    $lateHash = (Get-FileHash -Algorithm SHA256 $late).Hash
    $settledHash = (Get-FileHash -Algorithm SHA256 $settled).Hash
    if ($lateHash -eq $settledHash) {
        Write-Host '[PDFr] late and settled are identical: no settle jump.'
    } else {
        Write-Host '[PDFr] late and settled differ: compare them for a size or position jump.'
    }
}

Write-Host "[PDFr] Screenshots in $OutDir"

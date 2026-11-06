<#
.SYNOPSIS
  Sends reader gestures to the connected Android device.

.DESCRIPTION
  The pinch gestures use Linux multitouch events, which is the only way adb
  can press two fingers at once. They need `adb root`, which works on an
  emulator (and on debuggable engineering builds), not on a retail phone.

  Coordinates default to a 1080x2340 phone/AVD. Override -ScreenWidth and
  -ScreenHeight for another device. The multitouch device defaults to the
  emulator's /dev/input/event2; use `adb shell getevent -lp` to find another.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/gestures.ps1 pinch-out

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/gestures.ps1 double-tap
#>
[CmdletBinding()]
param(
    [ValidateSet('pinch-out', 'pinch-in', 'double-tap', 'select-word', 'swipe-up')]
    [string]$Gesture = 'pinch-out',

    [string]$EventDevice = '/dev/input/event2',
    [int]$ScreenWidth = 1080,
    [int]$ScreenHeight = 2340,
    [int]$Steps = 12
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

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

function Get-Prop([string]$Name) {
    return ((& $adb shell getprop $Name) -join '').Trim()
}

function Ensure-Root {
    & $adb root | Out-Null
    Start-Sleep -Seconds 1
    & $adb wait-for-device | Out-Null

    $identity = ((& $adb shell id) -join '').Trim()
    if ($identity -notmatch 'uid=0') {
        throw "Raw multitouch needs adb root. Run 'adb root' on an emulator, or test the pinch by hand."
    }
}

function ConvertTo-Raw([int]$Value, [int]$ScreenSize) {
    return [int][Math]::Round($Value * 32767.0 / $ScreenSize)
}

function Send-Events([System.Collections.Generic.List[string]]$Events) {
    Invoke-Adb shell ($Events -join '; ')
}

function Invoke-Pinch([int]$StartGap, [int]$EndGap) {
    Ensure-Root

    $centerX = [int]($ScreenWidth / 2)
    $centerY = [int]($ScreenHeight / 2)

    $startLeft = $centerX - [int]($StartGap / 2)
    $startRight = $centerX + [int]($StartGap / 2)
    $endLeft = $centerX - [int]($EndGap / 2)
    $endRight = $centerX + [int]($EndGap / 2)

    $rawY = ConvertTo-Raw $centerY $ScreenHeight
    $events = [System.Collections.Generic.List[string]]::new()
    $device = $EventDevice

    $events.Add("sendevent $device 3 47 0")
    $events.Add("sendevent $device 3 57 11")
    $events.Add("sendevent $device 3 53 $(ConvertTo-Raw $startLeft $ScreenWidth)")
    $events.Add("sendevent $device 3 54 $rawY")
    $events.Add("sendevent $device 3 58 512")
    $events.Add("sendevent $device 3 48 100")
    $events.Add("sendevent $device 3 47 1")
    $events.Add("sendevent $device 3 57 12")
    $events.Add("sendevent $device 3 53 $(ConvertTo-Raw $startRight $ScreenWidth)")
    $events.Add("sendevent $device 3 54 $rawY")
    $events.Add("sendevent $device 3 58 512")
    $events.Add("sendevent $device 3 48 100")
    $events.Add("sendevent $device 1 330 1")
    $events.Add("sendevent $device 0 0 0")

    for ($step = 1; $step -le $Steps; $step++) {
        $fraction = $step / [double]$Steps
        $left = [int]($startLeft + ($endLeft - $startLeft) * $fraction)
        $right = [int]($startRight + ($endRight - $startRight) * $fraction)

        $events.Add("sendevent $device 3 47 0")
        $events.Add("sendevent $device 3 53 $(ConvertTo-Raw $left $ScreenWidth)")
        $events.Add("sendevent $device 3 54 $rawY")
        $events.Add("sendevent $device 3 47 1")
        $events.Add("sendevent $device 3 53 $(ConvertTo-Raw $right $ScreenWidth)")
        $events.Add("sendevent $device 3 54 $rawY")
        $events.Add("sendevent $device 0 0 0")
        $events.Add('sleep 0.03')
    }

    $events.Add("sendevent $device 3 47 0")
    $events.Add("sendevent $device 3 57 -1")
    $events.Add("sendevent $device 3 47 1")
    $events.Add("sendevent $device 3 57 -1")
    $events.Add("sendevent $device 1 330 0")
    $events.Add("sendevent $device 0 0 0")

    Send-Events $events
}

$abi = Get-Prop 'ro.product.cpu.abi'
if ([string]::IsNullOrWhiteSpace($abi)) {
    throw 'No device connected. Start the emulator first.'
}

$centerX = [int]($ScreenWidth / 2)
$centerY = [int]($ScreenHeight / 2)

switch ($Gesture) {
    'pinch-out' {
        Write-Host "[PDFr] Pinching out (zoom in) on $ScreenWidth x $ScreenHeight..."
        Invoke-Pinch -StartGap 280 -EndGap 680
    }
    'pinch-in' {
        Write-Host "[PDFr] Pinching in (zoom out) on $ScreenWidth x $ScreenHeight..."
        Invoke-Pinch -StartGap 780 -EndGap 120
    }
    'double-tap' {
        Write-Host "[PDFr] Double-tapping the centre of the screen..."
        Invoke-Adb shell input tap $centerX $centerY
        Start-Sleep -Milliseconds 80
        Invoke-Adb shell input tap $centerX $centerY
    }
    'select-word' {
        Write-Host '[PDFr] Long-pressing and dragging to select text...'
        $selectY = [int]($ScreenHeight * 0.55)
        Invoke-Adb shell input motionevent DOWN $centerX $selectY
        Start-Sleep -Milliseconds 900
        Invoke-Adb shell input motionevent MOVE ($centerX + 120) $selectY
        Start-Sleep -Milliseconds 300
        Invoke-Adb shell input motionevent UP ($centerX + 120) $selectY
    }
    'swipe-up' {
        Write-Host '[PDFr] Swiping up to scroll...'
        Invoke-Adb shell input swipe $centerX 1800 $centerX 600 500
    }
}

Write-Host '[PDFr] Gesture sent.'

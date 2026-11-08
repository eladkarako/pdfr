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
  late and settled shots are compared over the reading area (the toolbar and
  the fading page badge are ignored): a stable score means the deferred render
  or reflow landed at exactly the live size, while a large score points at a
  settle jump.

  Coordinates default to the pdfr_full AVD (1080x2340). Override
  -ScreenWidth/-ScreenHeight and -EventDevice (find it with
  `adb shell getevent -lp`, the device whose name contains multi_touch).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pinch-out

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\zoom-probe.ps1 -Action pinch-out -CenterX 0.3 -CenterY 0.17 -SkipRoot

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

    # Fraction of the screen width where the pinch centroid sits. Move it away
    # from the centre to check that the zoom stays anchored to the midpoint
    # rather than to the page's left edge or the first finger.
    [double]$CenterX = 0.5,

    [int]$StartGap = 280,
    [int]$EndGap = 620,
    [int]$Gap = 420,

    [int]$StartY = 1700,
    [int]$EndY = 900,

    [int]$Steps = 8,
    [int]$SettleMs = 700,

    # Time between the last finger move and the "late" screenshot. The input
    # event still has to travel through adb, so capturing immediately raced the
    # final zoom and made the probe report a jump that never happened.
    [int]$LateDelayMs = 400,

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
$centerX = [int]($ScreenWidth * $CenterX)
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
        Start-Sleep -Milliseconds $LateDelayMs
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
        Start-Sleep -Milliseconds $LateDelayMs
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
        Start-Sleep -Milliseconds $LateDelayMs
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

# Compare only the reading area: the toolbar and the fading page badge must not
# count as movement. A real settle jump shifts a large fraction of the page,
# while a sharper re-render only changes edge pixels by a small amount.
function Get-SettleDifference([string]$LatePath, [string]$SettledPath) {
    Add-Type -AssemblyName System.Drawing

    $lateBitmap = [System.Drawing.Bitmap]::FromFile($LatePath)
    $settledBitmap = [System.Drawing.Bitmap]::FromFile($SettledPath)
    try {
        if ($lateBitmap.Width -ne $settledBitmap.Width -or
            $lateBitmap.Height -ne $settledBitmap.Height) {
            return [pscustomobject]@{ Mean = 255.0; Changed = 1.0; Comparable = $false }
        }

        $width = $lateBitmap.Width
        $height = $lateBitmap.Height
        $left = 0
        $right = $width
        $top = [int]($height * 0.14)
        $bottom = [int]($height * 0.90)
        $step = 4

        $total = 0.0
        $count = 0
        $changed = 0
        for ($y = $top; $y -lt $bottom; $y += $step) {
            for ($x = $left; $x -lt $right; $x += $step) {
                $a = $lateBitmap.GetPixel($x, $y)
                $b = $settledBitmap.GetPixel($x, $y)
                $difference = ([Math]::Abs($a.R - $b.R) +
                    [Math]::Abs($a.G - $b.G) +
                    [Math]::Abs($a.B - $b.B)) / 3.0
                $total += $difference
                $count++
                if ($difference -gt 24) { $changed++ }
            }
        }

        return [pscustomobject]@{
            Mean = if ($count -gt 0) { $total / $count } else { 0.0 }
            Changed = if ($count -gt 0) { $changed / [double]$count } else { 0.0 }
            Comparable = $true
        }
    } finally {
        $lateBitmap.Dispose()
        $settledBitmap.Dispose()
    }
}

if ((Test-Path $late) -and (Test-Path $settled)) {
    $difference = Get-SettleDifference $late $settled
    if (-not $difference.Comparable) {
        Write-Host '[PDFr] late and settled have different sizes: compare them for a jump.'
    } elseif ($difference.Mean -lt 1.5 -and $difference.Changed -lt 0.02) {
        Write-Host ('[PDFr] late and settled are stable: no settle jump ' +
            "(mean {0:N2}, changed {1:P1})." -f $difference.Mean, $difference.Changed)
    } elseif ($difference.Mean -lt 6.0 -and $difference.Changed -lt 0.15) {
        Write-Host ('[PDFr] late and settled differ slightly, likely a sharper ' +
            "re-render rather than a jump (mean {0:N2}, changed {1:P1})." -f
            $difference.Mean, $difference.Changed)
    } else {
        Write-Host ('[PDFr] late and settled differ substantially: compare them ' +
            "for a size or position jump (mean {0:N2}, changed {1:P1})." -f
            $difference.Mean, $difference.Changed)
    }
}

Write-Host "[PDFr] Screenshots in $OutDir"

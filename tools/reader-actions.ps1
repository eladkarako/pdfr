#Requires -Version 5.1
<#
.SYNOPSIS
  Drives the reader's tap and long-press menus and captures screenshots.

.DESCRIPTION
  Single-finger actions are sent with `adb shell input`, so this script does not
  need root and works on a normal device. It is the companion to zoom-probe.ps1:
  that one covers two-finger gestures, this one covers the page menu, selection
  and the overflow/info dialogs.

  Every run writes a PNG (and, for menu actions, the uiautomator XML used to find
  the button) to build\reader-actions\. Use -Action list to print the visible
  text/content descriptions when a button cannot be found.

  Coordinates are fractions of the screen by default, so the same command works
  on the pdfr_full AVD and on a phone. Override them with -X/-Y and
  -MenuX/-MenuY. Use -Prefix to keep several runs side by side.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action long-press

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action select-all

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\reader-actions.ps1 -Action info -Prefix sample-info

.NOTES
  Run tools\open-fixture.bat first so a document is open. The long-press point
  should be over readable text; the default (centre of the screen) is fine for
  the first page of sample.pdf.
#>
[CmdletBinding()]
param(
    [ValidateSet(
        'long-press',
        'tap',
        'select-all',
        'copy',
        'menu',
        'info',
        'scroll',
        'screenshot',
        'list'
    )]
    [string]$Action = 'long-press',

    # Tap/long-press point, as fractions of the screen.
    [double]$X = 0.5,
    [double]$Y = 0.45,

    # Overflow-menu button, as fractions of the screen. The default is the
    # centre of the three-dot icon on the pdfr_full toolbar.
    [double]$MenuX = 0.95,
    [double]$MenuY = 0.085,

    [int]$ScreenWidth = 1080,
    [int]$ScreenHeight = 2340,

    [int]$LongPressMs = 750,
    [int]$WaitMs = 600,

    [ValidateSet('up', 'down')]
    [string]$ScrollDirection = 'up',
    [double]$ScrollDistance = 0.45,

    [string]$OutDir,
    [string]$Prefix = 'actions'
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
    $OutDir = Join-Path $repo 'build\reader-actions'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

function Save-Shot([string]$Name) {
    $remote = "/sdcard/pdfr-$Prefix-$Name.png"
    $local = Join-Path $OutDir "$Prefix-$Name.png"
    Invoke-Adb shell "screencap -p $remote"
    Invoke-Adb pull $remote $local | Out-Null
    Invoke-Adb shell rm $remote
    Write-Host "[PDFr] $local"
}

function Get-UiXml {
    Invoke-Adb shell 'uiautomator dump /sdcard/pdfr-window.xml' | Out-Null
    $local = Join-Path $OutDir 'window.xml'
    Invoke-Adb pull /sdcard/pdfr-window.xml $local | Out-Null
    Invoke-Adb shell rm /sdcard/pdfr-window.xml
    return [xml](Get-Content -Raw -Encoding UTF8 $local)
}

function Get-NodeCenter([string]$Pattern) {
    $xml = Get-UiXml
    $node = $xml.SelectNodes('//node') | Where-Object {
        ($_.text -match $Pattern) -or ($_.'content-desc' -match $Pattern)
    } | Select-Object -First 1

    if (-not $node) {
        throw "No UI node matched '$Pattern'. Run -Action list to see visible text."
    }
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        throw "Could not parse bounds '$($node.bounds)' for '$Pattern'."
    }

    $left = [int]$Matches[1]
    $top = [int]$Matches[2]
    $right = [int]$Matches[3]
    $bottom = [int]$Matches[4]
    return [pscustomobject]@{
        X = [int](($left + $right) / 2)
        Y = [int](($top + $bottom) / 2)
    }
}

function Tap-Node([string]$Pattern) {
    $centre = Get-NodeCenter $Pattern
    Write-Host "[PDFr] Tapping '$Pattern' at $($centre.X),$($centre.Y)"
    Invoke-Adb shell input tap $centre.X $centre.Y
    Start-Sleep -Milliseconds $WaitMs
}

function Long-PressAt([int]$PointX, [int]$PointY) {
    # A zero-length swipe with a long duration is the most portable way to
    # long-press through `adb shell input`; `input tap` is too short.
    Invoke-Adb shell input swipe $PointX $PointY $PointX $PointY $LongPressMs
    Start-Sleep -Milliseconds $WaitMs
}

function Show-Text {
    $xml = Get-UiXml
    Write-Host '[PDFr] Visible text:'
    $seen = @{}
    foreach ($node in $xml.SelectNodes('//node')) {
        foreach ($value in @($node.text, $node.'content-desc')) {
            if ($value -and -not $seen.ContainsKey($value)) {
                $seen[$value] = $true
                Write-Host "  $value"
            }
        }
    }
}

$pointX = [int]($ScreenWidth * $X)
$pointY = [int]($ScreenHeight * $Y)
$menuX = [int]($ScreenWidth * $MenuX)
$menuY = [int]($ScreenHeight * $MenuY)

switch ($Action) {
    'screenshot' {
        Save-Shot 'screen'
    }
    'long-press' {
        Write-Host "[PDFr] Long-pressing $pointX,$pointY..."
        Long-PressAt $pointX $pointY
        Save-Shot 'long-press'
    }
    'tap' {
        Write-Host "[PDFr] Tapping $pointX,$pointY..."
        Invoke-Adb shell input tap $pointX $pointY
        Start-Sleep -Milliseconds $WaitMs
        Save-Shot 'tap'
    }
    'select-all' {
        Write-Host "[PDFr] Long-pressing $pointX,$pointY..."
        Long-PressAt $pointX $pointY
        Tap-Node '^Select all$'
        Save-Shot 'select-all'
    }
    'copy' {
        Write-Host "[PDFr] Long-pressing $pointX,$pointY..."
        Long-PressAt $pointX $pointY
        Tap-Node '^Select all$'
        Tap-Node '^Copy$'
        Save-Shot 'copy'
    }
    'menu' {
        Write-Host "[PDFr] Opening the overflow menu..."
        Invoke-Adb shell input tap $menuX $menuY
        Start-Sleep -Milliseconds $WaitMs
        Save-Shot 'menu'
    }
    'info' {
        Write-Host "[PDFr] Opening document info from the toolbar..."
        # Document info has its own toolbar button (the circled i); it was
        # never part of the overflow menu. Dismiss any open popup first so
        # uiautomator can see the toolbar's content descriptions again.
        Invoke-Adb shell input keyevent BACK
        Start-Sleep -Milliseconds 300
        Tap-Node '^Document info$'
        Save-Shot 'info'
    }
    'scroll' {
        $fromY = if ($ScrollDirection -eq 'up') {
            [int]($ScreenHeight * ($Y + $ScrollDistance / 2))
        } else {
            [int]($ScreenHeight * ($Y - $ScrollDistance / 2))
        }
        $toY = if ($ScrollDirection -eq 'up') {
            [int]($ScreenHeight * ($Y - $ScrollDistance / 2))
        } else {
            [int]($ScreenHeight * ($Y + $ScrollDistance / 2))
        }
        Write-Host "[PDFr] Scrolling $ScrollDirection from $fromY to $toY..."
        Invoke-Adb shell input swipe $pointX $fromY $pointX $toY 350
        Start-Sleep -Milliseconds $WaitMs
        Save-Shot 'scroll'
    }
    'list' {
        Show-Text
    }
}

Write-Host "[PDFr] Output in $OutDir"

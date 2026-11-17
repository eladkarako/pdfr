#Requires -Version 5.1
<#
.SYNOPSIS
    Capture PDFr screenshots and a short promotional MP4 from a connected
    emulator or device, then compose a poster.

.DESCRIPTION
    The script builds the debug APK first (Gradle and the emulator are both
    memory-hungry), generates the fixtures, installs the app, and drives a
    small tour of the reader:

      01-empty.png       empty state with the disabled toolbar
      02-pdf-cover.png   the colourful Project Gutenberg sampler
      03-pdf-colour.png  a second, colour-heavy page
      04-overflow.png    the overflow menu with the mirrored toolbar entries
      05-info.png        the document-info editor
      06-epub.png        a reflowable EPUB
      07-print.png       the system print dialog, when a print service exists
      pdfr-promo.mp4     scrolling and the overflow menu
      poster.png         3-4 screenshots composed into a shareable poster

    Use -SkipBuild when the APKs are already current. Use -StartEmulator to
    start the configured AVD only after the build finishes.

.EXAMPLE
    tools\capture-promo.ps1 -StartEmulator

.EXAMPLE
    tools\capture-promo.ps1 -SkipBuild -SkipVideo
#>
param(
    [string]$OutDir = (Join-Path $PSScriptRoot '..\build\promo'),
    [switch]$StartEmulator,
    [switch]$SkipBuild,
    [switch]$SkipVideo,
    [switch]$SkipPrint,
    [switch]$KeepEmulator
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Gradlew = Join-Path $Root 'gradlew.bat'
$Fixtures = if ($env:PDFR_FIXTURES) { $env:PDFR_FIXTURES } else { Join-Path $Root 'build\test-fixtures' }
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
$script:StartedEmulator = $false

function Write-Step([string]$Message) {
    Write-Host "[PDFr] $Message" -ForegroundColor Cyan
}

function Resolve-AndroidTool([string]$Name) {
    if ($Name -eq 'adb' -and $env:ADB -and (Test-Path $env:ADB)) { return $env:ADB }
    if ($Name -eq 'emulator' -and $env:EMULATOR -and (Test-Path $env:EMULATOR)) { return $env:EMULATOR }

    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

    $relative = if ($Name -eq 'adb') { 'platform-tools\adb.exe' } else { 'emulator\emulator.exe' }
    $candidate = Join-Path $sdk $relative
    if (Test-Path $candidate) { return $candidate }
    throw "$Name was not found. Set ANDROID_SDK_ROOT or ADB/EMULATOR."
}

$Adb = Resolve-AndroidTool 'adb'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    # adb writes progress such as "1 file pulled" to stderr. The script runs with
    # ErrorActionPreference = 'Stop', which would turn that text into a
    # terminating NativeCommandError, so stderr is collected as plain text.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Adb @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }

    if ($code -ne 0) {
        throw "adb failed ($code): $($Arguments -join ' ')`n$output"
    }
    return $output
}

function Get-ConnectedDevice {
    $lines = Invoke-Adb devices
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device$') { return $matches[1] }
    }
    return $null
}

function Start-EmulatorIfNeeded {
    if (Get-ConnectedDevice) { return }

    if (-not $StartEmulator) {
        throw "No device or emulator is connected. Re-run with -StartEmulator, or start tools\start-emulator.bat first."
    }

    Write-Step 'Starting the emulator...'
    & cmd.exe /c "`"$PSScriptRoot\start-emulator.bat`""
    if ($LASTEXITCODE -ne 0) { throw 'The emulator did not start.' }
    $script:StartedEmulator = $true

    if (-not (Get-ConnectedDevice)) {
        throw 'The emulator started but adb still reports no device.'
    }
}

function Build-Apk {
    if ($SkipBuild) {
        Write-Step 'Skipping the Gradle build.'
        return
    }

    Write-Step 'Building the debug APK before touching the emulator...'
    & $Gradlew ':app:assembleDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
}

function New-Fixtures {
    Write-Step "Generating fixtures in $Fixtures..."
    $node = Get-Command node -ErrorAction SilentlyContinue
    if ($node) {
        & $node.Source (Join-Path $PSScriptRoot 'make_test_files.js') '--out' $Fixtures '--no-gutenberg'
        if ($LASTEXITCODE -ne 0) { throw 'Fixture generation failed.' }
    } else {
        & cmd.exe /c "`"$PSScriptRoot\make_test_files.cmd`" --out `"$Fixtures`" --no-gutenberg"
        if ($LASTEXITCODE -ne 0) { throw 'Fixture generation failed.' }
    }
}

function Install-DebugApk {
    $abi = (Invoke-Adb shell getprop ro.product.cpu.abi | Out-String).Trim()
    if (-not $abi) { $abi = 'x86_64' }

    $apk = Join-Path $Root "build\app\outputs\apk\debug\app-$abi-debug.apk"
    if (-not (Test-Path $apk)) {
        throw "APK not found: $apk. Run without -SkipBuild."
    }

    Write-Step "Installing $abi debug APK..."
    & $Adb install -r -t $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed.' }

    # Reset the stored theme and language so every run starts in the system
    # language with the system theme, and the first screenshot shows the empty
    # state rather than the document left open by the previous run.
    Invoke-Adb shell pm clear com.karako.pdfr | Out-Null
}

function Save-Shot([string]$Name) {
    $remote = "/sdcard/pdfr-promo-$Name.png"
    Invoke-Adb shell "screencap -p $remote" | Out-Null
    Invoke-Adb pull $remote (Join-Path $OutDir "$Name.png") | Out-Null
    Invoke-Adb shell rm $remote | Out-Null
    Write-Host "  saved $Name.png"
}

function Get-UiDocument {
    $remote = '/sdcard/pdfr-promo-ui.xml'
    $local = Join-Path $OutDir 'window.xml'
    try {
        # Dump only the active window. uiautomator would otherwise include the
        # window behind an open menu, where the toolbar button carries the same
        # label as the menu entry; the first match would then tap the toolbar
        # and close the menu instead of choosing the entry.
        Invoke-Adb shell "uiautomator dump $remote" | Out-Null
        Invoke-Adb pull $remote $local | Out-Null
    } catch {
        return $null
    } finally {
        try { Invoke-Adb shell rm $remote | Out-Null } catch { }
    }
    if (-not (Test-Path $local)) { return $null }
    try {
        return [xml](Get-Content $local -Raw)
    } catch {
        return $null
    }
}

function Find-NodeCenter([string]$Label) {
    $doc = Get-UiDocument
    if (-not $doc) { return $null }

    $node = $doc.SelectNodes('//node') | Where-Object {
        $_.text -eq $Label -or $_.'content-desc' -eq $Label
    } | Select-Object -First 1
    if (-not $node) { return $null }

    $match = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $match.Success) { return $null }

    return @{
        X = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
        Y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
    }
}

function Wait-NodeCenter([string]$Label, [int]$Attempts = 10) {
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $center = Find-NodeCenter $Label
        if ($center) { return $center }
        Start-Sleep -Milliseconds 600
    }
    return $null
}

function Tap-Node([string]$Label) {
    $center = Wait-NodeCenter $Label
    if (-not $center) {
        throw "Could not find '$Label' in the UI hierarchy."
    }
    Invoke-Adb shell input tap $center.X $center.Y | Out-Null
}

function Get-ScreenSize {
    $output = (Invoke-Adb shell wm size | Out-String)
    $sizeMatches = [regex]::Matches($output, '(\d+)x(\d+)')
    if ($sizeMatches.Count -eq 0) { return @{ Width = 1080; Height = 2340 } }
    $last = $sizeMatches[$sizeMatches.Count - 1]
    return @{ Width = [int]$last.Groups[1].Value; Height = [int]$last.Groups[2].Value }
}

function Swipe-Up([int]$FractionStart = 75, [int]$FractionEnd = 30) {
    $size = Get-ScreenSize
    $x = [int]($size.Width / 2)
    $start = [int]($size.Height * $FractionStart / 100)
    $end = [int]($size.Height * $FractionEnd / 100)
    Invoke-Adb shell input swipe $x $start $x $end 350 | Out-Null
}

function Swipe-Down([int]$FractionStart = 30, [int]$FractionEnd = 75) {
    $size = Get-ScreenSize
    $x = [int]($size.Width / 2)
    $start = [int]($size.Height * $FractionStart / 100)
    $end = [int]($size.Height * $FractionEnd / 100)
    Invoke-Adb shell input swipe $x $start $x $end 350 | Out-Null
}

function Open-Fixture([string]$Name) {
    $env:PDFR_FIXTURES = $Fixtures
    & cmd.exe /c "`"$PSScriptRoot\open-fixture.bat`" `"$Name`""
    if ($LASTEXITCODE -ne 0) { throw "Could not open fixture $Name." }
}

function Restart-App {
    Invoke-Adb shell am force-stop com.karako.pdfr | Out-Null
    Invoke-Adb shell am start -n com.karako.pdfr/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
}

function Capture-Screenshots {
    Write-Step 'Capturing screenshots...'

    Restart-App
    Start-Sleep -Seconds 1
    Save-Shot '01-empty'

    Open-Fixture 'gutenberg\gutenberg-color.pdf'
    Start-Sleep -Seconds 5
    Save-Shot '02-pdf-cover'

    Swipe-Up 76 32
    Start-Sleep -Seconds 2
    Save-Shot '03-pdf-colour'

    Tap-Node 'More options'
    Start-Sleep -Seconds 1
    Save-Shot '04-overflow'

    Tap-Node 'Document info'
    Start-Sleep -Seconds 2
    Save-Shot '05-info'
    Invoke-Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Seconds 1

    Open-Fixture 'alice.epub'
    Start-Sleep -Seconds 4
    Save-Shot '06-epub'

    if (-not $SkipPrint) {
        $spooler = (Invoke-Adb shell pm list packages | Out-String)
        if ($spooler -match 'printspooler') {
            Tap-Node 'Print document'
            # The print spooler starts on first use and can take a few seconds,
            # so wait for its window instead of a fixed delay.
            $printOpen = $false
            for ($attempt = 1; $attempt -le 20; $attempt++) {
                $windows = (Invoke-Adb shell dumpsys window windows | Out-String)
                if ($windows -match 'printspooler') {
                    $printOpen = $true
                    break
                }
                Start-Sleep -Milliseconds 500
            }
            if ($printOpen) {
                Save-Shot '07-print'
            } else {
                Write-Host '  the print dialog did not appear; skipped 07-print.png'
            }
            Invoke-Adb shell input keyevent 4 | Out-Null
            Start-Sleep -Seconds 1
        } else {
            Write-Host '  no print spooler on this image; skipped 07-print.png'
        }
    }
}

function Capture-Video {
    if ($SkipVideo) {
        Write-Step 'Skipping the MP4 capture.'
        return
    }

    Write-Step 'Recording a short MP4...'
    Restart-App
    Open-Fixture 'gutenberg\gutenberg-color.pdf'
    Start-Sleep -Seconds 4

    $menu = Wait-NodeCenter 'More options'
    if (-not $menu) { throw 'Could not find the overflow button for the video.' }

    $remote = '/sdcard/pdfr-promo.mp4'
    try { Invoke-Adb shell rm $remote | Out-Null } catch { }

    # Portrait phones exceed screenrecord's encoder limits at full resolution,
    # so scale to 720 px wide while keeping the display aspect ratio.
    $screen = Get-ScreenSize
    $videoWidth = 720
    $videoHeight = [int]([Math]::Round($screen.Height * $videoWidth / $screen.Width / 2) * 2)
    $record = Start-Process -FilePath $Adb `
        -ArgumentList @(
            'shell', 'screenrecord',
            '--bit-rate', '8000000',
            '--time-limit', '30',
            '--size', "${videoWidth}x${videoHeight}",
            $remote
        ) `
        -PassThru -WindowStyle Hidden

    Start-Sleep -Seconds 1
    Swipe-Up 76 34
    Start-Sleep -Seconds 2
    Swipe-Up 76 34
    Start-Sleep -Seconds 2
    Invoke-Adb shell input tap $menu.X $menu.Y | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Seconds 1
    Swipe-Down 34 76
    Start-Sleep -Seconds 2

    try { Invoke-Adb shell pkill -INT screenrecord | Out-Null } catch { }
    Wait-Process -Id $record.Id -Timeout 15 -ErrorAction SilentlyContinue
    Invoke-Adb pull $remote (Join-Path $OutDir 'pdfr-promo.mp4') | Out-Null
    Invoke-Adb shell rm $remote | Out-Null
    Write-Host '  saved pdfr-promo.mp4'
}

function Write-Readme {
    $lines = @(
        'PDFr promo capture',
        '==================',
        '',
        'Generated by tools\capture-promo.ps1.',
        '',
        'Screenshots:',
        '  01-empty.png       empty state, disabled document actions',
        '  02-pdf-cover.png   colourful Project Gutenberg sampler',
        '  03-pdf-colour.png  another colour-heavy PDF page',
        '  04-overflow.png    mirrored toolbar entries in the overflow menu',
        '  05-info.png        document-info editor',
        '  06-epub.png        reflowable EPUB',
        '  07-print.png       system print dialog (when available)',
        '',
        'Video:',
        '  pdfr-promo.mp4     scrolling and the overflow menu',
        '',
        'Poster:',
        '  poster.png         3-4 screenshots composed for sharing',
        '',
        'The colour PDF is generated by tools\make_test_files.js from',
        'public-domain Project Gutenberg excerpts. See the fixture folder''s',
        'SOURCES.txt for the source eBooks.',
        '',
        'Regenerate everything:',
        '  tools\capture-promo.ps1 -StartEmulator',
        ''
    )
    Set-Content -Path (Join-Path $OutDir 'README.txt') -Value $lines -Encoding UTF8
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Build-Apk
Start-EmulatorIfNeeded
New-Fixtures
Install-DebugApk

Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Invoke-Adb shell wm dismiss-keyguard | Out-Null

Capture-Screenshots
Capture-Video

Write-Step 'Composing the poster...'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'make-poster.ps1') -OutDir $OutDir
if ($LASTEXITCODE -ne 0) {
    Write-Warning 'The poster step failed; the screenshots and video are still usable.'
}

Write-Readme

if ($script:StartedEmulator -and -not $KeepEmulator) {
    Write-Step 'Stopping the emulator...'
    & cmd.exe /c "`"$PSScriptRoot\stop-emulator.bat`""
}

Write-Host ''
Write-Host "[OK] Promo assets are in $OutDir" -ForegroundColor Green

# adb writes progress to stderr, which PowerShell counts as an error even when
# the command succeeded. Failures above throw, so reaching this point is success.
exit 0

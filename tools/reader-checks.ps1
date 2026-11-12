#Requires -Version 5.1
<#
.SYNOPSIS
  Runs the reader's UI checks on a connected device and fails on any regression.

.DESCRIPTION
  This is the automated companion to tools\manual-testing.bat. It drives the
  installed debug build with adb and uiautomator, reads the Compose semantics
  tree, and compares screenshots where only pixels can prove the result.

  The checks cover:
    * the toolbar with no document open: Share, info and Save as are disabled,
      look faded, and do nothing when tapped, while the overflow menu stays
      enabled and offers Fix PDF then Exit;
    * plain text defaults and every text option: single page, line wrapping,
      right alignment, line numbers (left in LTR, right in RTL) and text size;
    * native SelectionContainer selection and the Copy/Copy all touch menu,
      including a full-document copy that reaches lines outside the viewport;
    * the editable info dialog: ISO dates, existing fields only, draft
      draft/reopen/Cancel/Apply behaviour, and edited names in Share/Save as;
    * pinch gestures: ignored by reflowable text, still zooming a fixed-layout
      PDF;
    * Exit from every menu: it ends the task and the PDFr process.

  Run tools\install-debug.bat (or build and install the debug APK) first. The
  script writes screenshots and a results.txt to build\reader-checks\ and exits
  with 1 when a check fails. It never pushes or commits anything.

  The gesture checks need adb root, exactly like zoom-probe.ps1. Run the other
  sections on a retail device with -Section empty,text,selection,metadata,exit.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\reader-checks.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\reader-checks.ps1 -Section text

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\reader-checks.ps1 -Section gestures -SkipRoot

.NOTES
  Screen coordinates and the multitouch device default to the pdfr_full AVD
  (1080x2340). Override -ScreenWidth/-ScreenHeight/-EventDevice for a phone.
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'empty', 'text', 'selection', 'metadata', 'gestures', 'exit')]
    [string[]]$Section = @('all'),

    [int]$ScreenWidth = 1080,
    [int]$ScreenHeight = 2340,
    [string]$EventDevice = '/dev/input/event2',

    [string]$OutDir,

    # Skip `adb root` when adbd is already root (avoids a restart that can
    # resize the viewport between screenshots).
    [switch]$SkipRoot,

    # Leave the app on whatever the last check opened.
    [switch]$KeepOpen
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
    $OutDir = Join-Path $repo 'build\reader-checks'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# A compiled helper keeps the screenshot assertions fast enough to run in a
# normal CI pass. The PNG reading and pixel loops in PowerShell itself are
# roughly two orders of magnitude slower.
if (-not ('PdfrImage' -as [type])) {
    Add-Type -AssemblyName System.Drawing
    Add-Type -ReferencedAssemblies 'System.Drawing' -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public class PdfrSelectionStats {
    public int Count;
    public int Gutter;
    public int Dark;
    public int MinX;
    public int MinY;
    public int MaxX;
    public int MaxY;
}

public class PdfrDiffStats {
    public bool Comparable;
    public double Mean;
    public double Changed;
}

public class PdfrImage {
    public readonly int Width;
    public readonly int Height;
    private readonly int _stride;
    private readonly byte[] _bytes;

    public PdfrImage(string path) {
        using (Bitmap bitmap = new Bitmap(path)) {
            Width = bitmap.Width;
            Height = bitmap.Height;
            Rectangle rect = new Rectangle(0, 0, Width, Height);
            BitmapData data = bitmap.LockBits(
                rect,
                ImageLockMode.ReadOnly,
                PixelFormat.Format32bppArgb);
            try {
                _stride = data.Stride;
                _bytes = new byte[_stride * Height];
                Marshal.Copy(data.Scan0, _bytes, 0, _bytes.Length);
            } finally {
                bitmap.UnlockBits(data);
            }
        }
    }

    public int InkCount(int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = Math.Max(0, top); y < Math.Min(Height, bottom); y++) {
            int row = y * _stride;
            for (int x = Math.Max(0, left); x < Math.Min(Width, right); x++) {
                int offset = row + x * 4;
                int blue = _bytes[offset];
                int green = _bytes[offset + 1];
                int red = _bytes[offset + 2];
                if ((red + green + blue) / 3 < 120) {
                    count++;
                }
            }
        }
        return count;
    }

    // Selection fills and handles are the only strongly blue pixels in the
    // reading area. Counting them leaves the line numbers, which are grey,
    // and the white page untouched.
    public PdfrSelectionStats MeasureSelection(int gutterWidth) {
        PdfrSelectionStats stats = new PdfrSelectionStats();
        stats.MinX = int.MaxValue;
        stats.MinY = int.MaxValue;

        int top = (int)(Height * 0.14);
        int bottom = (int)(Height * 0.94);
        for (int y = top; y < bottom; y += 2) {
            int row = y * _stride;
            for (int x = 0; x < Width; x += 2) {
                int offset = row + x * 4;
                int blue = _bytes[offset];
                int green = _bytes[offset + 1];
                int red = _bytes[offset + 2];

                if (blue - red > 20 && blue - green > 10) {
                    stats.Count++;
                    if (x < gutterWidth) {
                        stats.Gutter++;
                    }
                    if ((red + green + blue) / 3 < 120) {
                        stats.Dark++;
                    }
                    if (x < stats.MinX) { stats.MinX = x; }
                    if (x > stats.MaxX) { stats.MaxX = x; }
                    if (y < stats.MinY) { stats.MinY = y; }
                    if (y > stats.MaxY) { stats.MaxY = y; }
                }
            }
        }

        if (stats.Count == 0) {
            stats.MinX = 0;
            stats.MinY = 0;
        }
        return stats;
    }

    public static PdfrDiffStats Compare(string pathA, string pathB) {
        PdfrDiffStats stats = new PdfrDiffStats();
        PdfrImage a = new PdfrImage(pathA);
        PdfrImage b = new PdfrImage(pathB);

        if (a.Width != b.Width || a.Height != b.Height) {
            stats.Comparable = false;
            stats.Mean = 255.0;
            stats.Changed = 1.0;
            return stats;
        }

        stats.Comparable = true;
        int top = (int)(a.Height * 0.14);
        int bottom = (int)(a.Height * 0.90);
        int step = 4;
        double total = 0.0;
        long changed = 0;
        long count = 0;

        for (int y = top; y < bottom; y += step) {
            int rowA = y * a._stride;
            int rowB = y * b._stride;
            for (int x = 0; x < a.Width; x += step) {
                int offsetA = rowA + x * 4;
                int offsetB = rowB + x * 4;
                double difference = (
                    Math.Abs(a._bytes[offsetA + 2] - b._bytes[offsetB + 2]) +
                    Math.Abs(a._bytes[offsetA + 1] - b._bytes[offsetB + 1]) +
                    Math.Abs(a._bytes[offsetA] - b._bytes[offsetB])) / 3.0;
                total += difference;
                count++;
                if (difference > 24) { changed++; }
            }
        }

        stats.Mean = count > 0 ? total / count : 0.0;
        stats.Changed = count > 0 ? changed / (double)count : 0.0;
        return stats;
    }
}
'@
}

$script:Checks = [System.Collections.Generic.List[object]]::new()
$script:CurrentSection = ''

function Write-Result([string]$Name, [bool]$Ok, [string]$Detail) {
    $script:Checks.Add([pscustomobject]@{
        Section = $script:CurrentSection
        Name = $Name
        Ok = $Ok
        Detail = $Detail
    })

    $prefix = if ($Ok) { '[PASS]' } else { '[FAIL]' }
    if ($Detail) {
        Write-Host "$prefix $Name - $Detail"
    } else {
        Write-Host "$prefix $Name"
    }
}

function Test-Check([string]$Name, [bool]$Condition, [string]$Detail = '') {
    Write-Result $Name $Condition $Detail
}

function Test-Equal([string]$Name, $Actual, $Expected) {
    Test-Check $Name ($Actual -eq $Expected) "expected '$Expected', got '$Actual'"
}

function Test-Match([string]$Name, [string]$Actual, [string]$Pattern) {
    if ($Actual -match $Pattern) {
        Test-Check $Name $true "value '$Actual'"
    } else {
        Test-Check $Name $false "value '$Actual' does not match '$Pattern'"
    }
}

function Test-Section([string]$Name, [scriptblock]$Body) {
    $script:CurrentSection = $Name
    Write-Host ''
    Write-Host "=== $Name ==="
    try {
        & $Body
    } catch {
        $detail = $_.Exception.Message
        if ($_.ScriptStackTrace) {
            $detail = "$detail`n$($_.ScriptStackTrace)"
        }
        Write-Result "$Name section completed" $false $detail
    }
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    # adb routinely writes harmless notices to stderr (for example "Activity
    # not started"). Keep those from becoming terminating errors while still
    # reporting a real non-zero exit code.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $adb @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }

    if ($code -ne 0) {
        throw "adb failed ($code): $($Arguments -join ' ')`n$output"
    }
    return $output
}

function Save-Shot([string]$Name) {
    $remote = "/sdcard/pdfr-checks-$Name.png"
    $local = Join-Path $OutDir "$Name.png"
    Invoke-Adb shell "screencap -p $remote" | Out-Null
    Invoke-Adb pull $remote $local | Out-Null
    Invoke-Adb shell rm $remote | Out-Null
    Write-Host "[PDFr] $local"
    return $local
}

function Get-UiXml {
    $remote = '/sdcard/pdfr-checks-window.xml'
    $local = Join-Path $OutDir 'window.xml'

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        for ($attempt = 0; $attempt -lt 4; $attempt++) {
            # --windows also dumps the Compose text context menu, which lives in
            # its own Popup Window rather than in the activity window.
            & $adb shell uiautomator dump --windows $remote 2>&1 | Out-Null
            & $adb pull $remote $local 2>&1 | Out-Null

            if ((Test-Path $local) -and ((Get-Item $local).Length -gt 100)) {
                try {
                    return Merge-AppWindows ([xml](Get-Content -Raw -Encoding UTF8 $local))
                } catch {
                    # The pull can race the dump; retry.
                }
            }
            Start-Sleep -Milliseconds 400
        }
    } finally {
        $ErrorActionPreference = $previous
    }

    throw 'Could not read the UI hierarchy from uiautomator.'
}

function Merge-AppWindows($Dump) {
    # A --windows dump has a <displays> root. Fold the windows into one
    # hierarchy so every existing //node lookup keeps working, while dropping
    # the launcher and system chrome (status bar, clipboard preview) that never
    # matter to these checks. Nested windows repeat their parent's subtree, so
    # only direct display children are considered.
    $doc = New-Object System.Xml.XmlDocument
    $root = $doc.CreateElement('hierarchy')
    $doc.AppendChild($root) | Out-Null

    foreach ($window in $Dump.SelectNodes('/displays/display/window')) {
        $first = $window.SelectNodes('.//node') |
            Where-Object { $_.package } |
            Select-Object -First 1
        if (-not $first) { continue }
        if ($first.package -eq 'com.android.launcher3') { continue }
        if ($first.package -eq 'com.android.systemui') { continue }

        foreach ($hierarchy in $window.SelectNodes('./hierarchy')) {
            foreach ($child in @($hierarchy.ChildNodes)) {
                $root.AppendChild($doc.ImportNode($child, $true)) | Out-Null
            }
        }
    }

    return $doc
}

function Find-Node {
    param(
        [string]$Pattern,
        $Xml = $null
    )

    if ($null -eq $Xml) { $Xml = Get-UiXml }
    # Nodes that are not laid out (for example from a popup that is closing)
    # carry no bounds and cannot be tapped, so skip them.
    return $Xml.SelectNodes('//node') | Where-Object {
        $_.bounds -match '^\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]$' -and
        (
            ($_.text -match $Pattern) -or
            ($_.'content-desc' -match $Pattern)
        )
    } | Select-Object -First 1
}

function Get-NodeRect($Node) {
    if ($Node.bounds -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
        throw "Could not parse bounds '$($Node.bounds)'."
    }

    $left = [int]$Matches[1]
    $top = [int]$Matches[2]
    $right = [int]$Matches[3]
    $bottom = [int]$Matches[4]

    return [pscustomobject]@{
        Left = $left
        Top = $top
        Right = $right
        Bottom = $bottom
        Width = $right - $left
        Height = $bottom - $top
        X = [int](($left + $right) / 2)
        Y = [int](($top + $bottom) / 2)
    }
}

function Tap-Node($Node) {
    $rect = Get-NodeRect $Node
    Invoke-Adb shell input tap $rect.X $rect.Y | Out-Null
    Start-Sleep -Milliseconds 500
}

function Tap-Pattern([string]$Pattern) {
    $node = Find-Node $Pattern
    Tap-Node $node
}

function Wait-ForPattern([string]$Pattern, [int]$TimeoutMs = 8000) {
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    do {
        try {
            $node = Find-Node $Pattern
            if ($node) { return $node }
        } catch {
            # The window can be mid-animation; keep polling.
        }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for '$Pattern'."
}

function Wait-ForGone([string]$Pattern, [int]$TimeoutMs = 8000) {
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    do {
        try {
            if (-not (Find-Node $Pattern)) { return $true }
        } catch {
            # A dump failure means the window is changing; retry.
        }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)

    return $false
}

function Get-ActionNode([string]$Description) {
    $xml = Get-UiXml
    return $xml.SelectNodes('//node') | Where-Object {
        $_.'content-desc' -eq $Description
    } | Select-Object -First 1
}

function Test-NodeEnabled($Node) {
    if (-not $Node) { return $null }

    $parent = $Node.ParentNode
    while ($parent -and $parent.Name -eq 'node') {
        if ($parent.clickable -eq 'true') {
            return $parent.enabled -eq 'true'
        }
        $parent = $parent.ParentNode
    }
    return $Node.enabled -eq 'true'
}

function Test-ActionEnabled([string]$Description) {
    return Test-NodeEnabled (Get-ActionNode $Description)
}

function Get-ActionRect([string]$Description) {
    $node = Get-ActionNode $Description
    if (-not $node) { throw "No toolbar action '$Description'." }

    $parent = $node.ParentNode
    while ($parent -and $parent.Name -eq 'node') {
        if ($parent.clickable -eq 'true') {
            return Get-NodeRect $parent
        }
        $parent = $parent.ParentNode
    }
    return Get-NodeRect $node
}

function Test-PackageVisible([string]$Package) {
    $xml = Get-UiXml
    return [bool]($xml.SelectNodes('//node') | Where-Object {
        $_.package -eq $Package
    } | Select-Object -First 1)
}

$menuPattern = '^(Fix PDF|Copy all|Text size:|Single page:|Line wrap:|Align right:|Line numbers:|Monospace:|Reorder pages|Rotate pages|Normalize|Exit)'

function Test-MenuOpen {
    try {
        return [bool](Find-Node $menuPattern)
    } catch {
        return $false
    }
}

function Close-Popup {
    if (Test-MenuOpen) {
        # BACK only reaches here when a popup really is open, so it cannot
        # close the reader by accident.
        Invoke-Adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 350
    }
}

function Open-Overflow {
    Close-Popup
    $button = Get-ActionNode 'More options'
    if (-not $button) { throw 'The overflow button is not on screen.' }
    Tap-Node $button
    Wait-ForPattern $menuPattern | Out-Null
}

function Get-MenuLabels {
    Open-Overflow
    $xml = Get-UiXml
    $labels = @($xml.SelectNodes('//node') | Where-Object {
        $_.text -match $menuPattern
    } | ForEach-Object { $_.text })

    Invoke-Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 350
    return $labels
}

function Get-MenuLabel([string]$Label) {
    Open-Overflow
    $node = Find-Node "^$([regex]::Escape($Label)):"
    if (-not $node) {
        Invoke-Adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 300
        throw "No menu item '$Label'."
    }
    $text = $node.text
    Invoke-Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 350
    return $text
}

function Toggle-MenuItem([string]$Label) {
    Open-Overflow
    $node = Find-Node "^$([regex]::Escape($Label)):"
    if (-not $node) {
        Invoke-Adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 300
        throw "No menu item '$Label'."
    }
    Tap-Node $node
    Start-Sleep -Milliseconds 400
}

function Get-TextSizeDialogXml {
    Open-Overflow
    Tap-Pattern '^Text size:'
    Wait-ForPattern '^\d+ pt$' | Out-Null
    return Get-UiXml
}

function Set-TextSize([int]$Points) {
    $xml = Get-TextSizeDialogXml
    $slider = $xml.SelectNodes("//node[@class='android.widget.SeekBar']") | Select-Object -First 1
    if (-not $slider) { throw 'The text-size slider is not on screen.' }

    $rect = Get-NodeRect $slider
    $fraction = ($Points - 5.0) / 35.0
    $tapX = [int]($rect.Left + $rect.Width * $fraction)
    Invoke-Adb shell input tap $tapX $rect.Y | Out-Null
    Start-Sleep -Milliseconds 400

    $after = Get-UiXml
    $value = $after.SelectNodes('//node') | Where-Object {
        $_.text -match '^\d+ pt$'
    } | Select-Object -First 1

    return [pscustomobject]@{
        Slider = $rect
        Value = if ($value) { $value.text } else { '' }
    }
}

function Open-EmptyApp {
    Invoke-Adb shell am force-stop com.karako.pdfr | Out-Null
    Invoke-Adb shell 'am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.karako.pdfr/.MainActivity' | Out-Null
    Wait-ForPattern '^Nothing open$' 12000 | Out-Null
}

function Open-Doc([string]$Name) {
    $opener = Join-Path $PSScriptRoot 'open-fixture.bat'
    & $opener $Name | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "open-fixture.bat failed for '$Name'."
    }

    Start-Sleep -Seconds 2
    $deadline = (Get-Date).AddSeconds(12)
    do {
        if ((Test-ActionEnabled 'Share document') -eq $true) { return }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)

    throw "The reader did not open '$Name'."
}

function Get-EditNodes($Xml) {
    return @($Xml.SelectNodes("//node[@class='android.widget.EditText']"))
}

function Set-EditText($Node, [string]$Value) {
    Tap-Node $Node
    Invoke-Adb shell input keyevent KEYCODE_MOVE_END | Out-Null
    $deletes = ('67 ' * 80).Trim()
    Invoke-Adb shell "input keyevent $deletes" | Out-Null
    if ($Value) {
        Invoke-Adb shell "input text $Value" | Out-Null
    }
    Start-Sleep -Milliseconds 500
}

function Open-InfoDialog {
    Close-Popup
    Tap-Pattern '^Document info$'
    Wait-ForPattern '^Cancel$' | Out-Null
    return Get-UiXml
}

function Close-InfoDialog([string]$Button) {
    Tap-Pattern "^$Button$"
    Wait-ForGone '^Apply$' 10000 | Out-Null
    Start-Sleep -Milliseconds 500
}

function Dismiss-Dialog {
    # x is left of the modal surface, so this lands on the scrim and keeps
    # the ViewModel's draft (the documented dismiss behaviour).
    Invoke-Adb shell input tap 40 ([int]($ScreenHeight * 0.45)) | Out-Null
    Start-Sleep -Milliseconds 600
}

function Enable-Root {
    if ($SkipRoot) {
        $identity = ((& $adb shell id) -join '').Trim()
        if ($identity -notmatch 'uid=0') {
            throw "Raw multitouch needs root. Run 'adb root', or omit -SkipRoot."
        }
        return
    }

    & $adb root | Out-Null
    Start-Sleep -Seconds 1
    & $adb wait-for-device | Out-Null
    Start-Sleep -Seconds 2

    $identity = ((& $adb shell id) -join '').Trim()
    if ($identity -notmatch 'uid=0') {
        throw 'Raw multitouch needs root. Use an emulator or a debuggable build.'
    }
}

function Invoke-ZoomProbe([string]$Prefix, [string]$Action, [double]$CenterY = 0.45) {
    $probe = Join-Path $PSScriptRoot 'zoom-probe.ps1'
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', $probe,
        '-Action', $Action,
        '-EventDevice', $EventDevice,
        '-ScreenWidth', $ScreenWidth,
        '-ScreenHeight', $ScreenHeight,
        '-CenterY', $CenterY,
        '-OutDir', $OutDir,
        '-Prefix', $Prefix
    )
    if ($SkipRoot) { $arguments += '-SkipRoot' }

    & powershell @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "zoom-probe.ps1 failed with exit code $LASTEXITCODE."
    }
}

function Get-NumericNodes($Xml) {
    return @($Xml.SelectNodes('//node') | Where-Object {
        $_.class -eq 'android.widget.TextView' -and $_.text -match '^\d+$'
    })
}

function Get-DialogHeight($Xml) {
    # The modal window is the widest frame narrower than the screen. Picking
    # the tallest such frame measures the height cap without depending on the
    # internal Compose hierarchy.
    $candidates = @($Xml.SelectNodes('//node') | Where-Object {
        if ($_.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return $false }
        $w = [int]$Matches[3] - [int]$Matches[1]
        $h = [int]$Matches[4] - [int]$Matches[2]
        return ($w -gt ($ScreenWidth * 0.55)) -and
            ($w -lt ($ScreenWidth * 0.99)) -and
            ($h -gt 200) -and
            ($h -lt ($ScreenHeight - 100))
    } | ForEach-Object {
        if ($_.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            [pscustomobject]@{
                Height = [int]$Matches[4] - [int]$Matches[2]
                Bounds = $_.bounds
            }
        }
    })

    if (-not $candidates -or $candidates.Count -eq 0) { return 0 }
    return ($candidates | Sort-Object Height -Descending | Select-Object -First 1).Height
}

function Test-EmptyState {
    Open-EmptyApp

    $xml = Get-UiXml
    Test-Check 'shows the empty pane' ([bool](Find-Node '^Nothing open$' -Xml $xml))
    Test-Check 'Open document is enabled' ((Test-ActionEnabled 'Open document') -eq $true)

    foreach ($action in @('Share document', 'Document info', 'Save as')) {
        Test-Check "$action is disabled" ((Test-ActionEnabled $action) -eq $false)
    }

    # The overflow menu stays available with no document so Fix PDF can open
    # the picker itself.
    Test-Check 'More options is enabled' ((Test-ActionEnabled 'More options') -eq $true)

    $shot = Save-Shot 'empty'
    $image = New-Object PdfrImage -ArgumentList $shot
    $openRect = Get-ActionRect 'Open document'
    Test-Check 'Open document icon is visible' (
        $image.InkCount($openRect.Left, $openRect.Top, $openRect.Right, $openRect.Bottom) -gt 200
    )

    foreach ($action in @('Share document', 'Document info', 'Save as')) {
        $rect = Get-ActionRect $action
        $ink = $image.InkCount($rect.Left, $rect.Top, $rect.Right, $rect.Bottom)
        Test-Check "$action looks faded" ($ink -lt 50) "ink pixels: $ink"
    }

    $emptyLabels = @(Get-MenuLabels)
    Test-Check 'the empty menu offers Fix PDF' (
        $emptyLabels -contains 'Fix PDF'
    ) ($emptyLabels -join ', ')
    # A single-item array collapses to a string when returned from a function,
    # so compare the joined labels rather than indexing.
    Test-Check 'the empty menu offers Fix PDF then Exit' (
        ($emptyLabels -join ', ') -eq 'Fix PDF, Exit'
    ) ($emptyLabels -join ', ')

    # A disabled Compose button has no click listener; tapping its centre must
    # leave the empty pane in place instead of opening a dialog or picker.
    Tap-Node (Get-ActionNode 'Document info')
    $afterInfo = Get-UiXml
    Test-Check 'tapping info with no document does nothing' (
        [bool](Find-Node '^Nothing open$' -Xml $afterInfo) -and
        (Get-EditNodes $afterInfo).Count -eq 0
    )

    Tap-Node (Get-ActionNode 'Save as')
    Start-Sleep -Milliseconds 600
    Test-Check 'tapping Save as with no document does nothing' (
        Test-PackageVisible 'com.karako.pdfr'
    )
}

function Test-Exit {
    Open-EmptyApp

    # Exit is deliberately last in every menu. With no document open the menu
    # is exactly Fix PDF then Exit.
    $labels = @(Get-MenuLabels)
    Test-Check 'the empty menu ends with Exit' (
        $labels.Count -eq 2 -and $labels[-1] -eq 'Exit'
    ) ($labels -join ', ')

    Open-Overflow
    Tap-Pattern '^Exit$'
    Start-Sleep -Seconds 2
    $pidText = ((& $adb shell pidof com.karako.pdfr 2>$null) -join '').Trim()
    Test-Check 'Exit ends the PDFr process' ($pidText -eq '') "pid: $pidText"
    Test-Check 'Exit leaves no PDFr screen behind' (
        -not (Test-PackageVisible 'com.karako.pdfr')
    )

    # A clean relaunch proves the task was removed rather than left in a
    # half-finished state.
    Open-EmptyApp
    Test-Check 'the app starts again after Exit' (
        [bool](Find-Node '^Nothing open$')
    )
}

function Test-PlainTextSettings {
    Open-Doc 'notes.txt'
    Wait-ForPattern '^PDFr plain text fixture$' 12000 | Out-Null

    $labels = Get-MenuLabels
    Test-Check 'default text size is 12 pt' ($labels -contains 'Text size: 12 pt') ($labels -join ', ')
    Test-Check 'plain text has no Fix PDF' (-not ($labels -contains 'Fix PDF')) ($labels -join ', ')
    Test-Check 'plain text menu ends with Exit' ($labels[-1] -eq 'Exit') ($labels -join ', ')
    Test-Check 'single page defaults to on' ($labels -contains 'Single page: on') ($labels -join ', ')
    Test-Check 'line wrap defaults to on' ($labels -contains 'Line wrap: on') ($labels -join ', ')
    Test-Check 'right alignment defaults to off' ($labels -contains 'Align right: off') ($labels -join ', ')
    Test-Check 'line numbers default to on' ($labels -contains 'Line numbers: on') ($labels -join ', ')
    Test-Check 'monospace defaults to on' ($labels -contains 'Monospace: on') ($labels -join ', ')

    $xml = Get-UiXml
    $numbers = Get-NumericNodes $xml
    Test-Check 'line numbers are rendered' ($numbers.Count -ge 8) "found $($numbers.Count)"
    $leftNumbers = @($numbers | Where-Object {
        $rect = Get-NodeRect $_
        ($rect.Left -lt ($ScreenWidth * 0.15)) -and
        ($rect.Right -le ($ScreenWidth * 0.15))
    })
    Test-Check 'line numbers sit on the left in LTR' (
        $leftNumbers.Count -eq $numbers.Count
    ) "$($leftNumbers.Count) of $($numbers.Count) in the left gutter"

    Toggle-MenuItem 'Line numbers'
    $offLabel = Get-MenuLabel 'Line numbers'
    Test-Equal 'line numbers can be turned off' $offLabel 'Line numbers: off'
    Test-Check 'no line number nodes while off' ((Get-NumericNodes (Get-UiXml)).Count -eq 0)

    Toggle-MenuItem 'Line numbers'
    Test-Check 'line numbers come back on' ((Get-NumericNodes (Get-UiXml)).Count -ge 8)

    Toggle-MenuItem 'Align right'
    $rtlLabel = Get-MenuLabel 'Align right'
    Test-Equal 'right alignment can be turned on' $rtlLabel 'Align right: on'
    $rtlNumbers = Get-NumericNodes (Get-UiXml)
    $rightNumbers = @($rtlNumbers | Where-Object {
        (Get-NodeRect $_).Left -ge ($ScreenWidth * 0.80)
    })
    Test-Check 'line numbers move to the right in RTL' (
        $rtlNumbers.Count -ge 8 -and $rightNumbers.Count -eq $rtlNumbers.Count
    ) "$($rightNumbers.Count) of $($rtlNumbers.Count) in the right gutter"

    Toggle-MenuItem 'Align right'
    $ltrAgain = Get-NumericNodes (Get-UiXml)
    $leftAgain = @($ltrAgain | Where-Object {
        (Get-NodeRect $_).Right -le ($ScreenWidth * 0.15)
    })
    Test-Check 'line numbers return to the left' (
        $ltrAgain.Count -ge 8 -and $leftAgain.Count -eq $ltrAgain.Count
    )

    # With wrapping off a long line can scroll horizontally. A horizontal
    # swipe must move the text, which is the visible difference between this
    # mode and the default wrapped page.
    Toggle-MenuItem 'Line wrap'
    Test-Equal 'line wrap can be turned off' (Get-MenuLabel 'Line wrap') 'Line wrap: off'
    $wrapBefore = Save-Shot 'wrap-off-before'
    Invoke-Adb shell input swipe ([int]($ScreenWidth * 0.85)) 1200 ([int]($ScreenWidth * 0.20)) 1200 450 | Out-Null
    Start-Sleep -Milliseconds 600
    $wrapAfter = Save-Shot 'wrap-off-after'
    $wrapDiff = [PdfrImage]::Compare($wrapBefore, $wrapAfter)
    Test-Check 'unwrapped text scrolls horizontally' (
        $wrapDiff.Comparable -and (($wrapDiff.Mean -gt 1.0) -or ($wrapDiff.Changed -gt 0.01))
    ) ('mean {0:N2}, changed {1:P1}' -f $wrapDiff.Mean, $wrapDiff.Changed)

    Toggle-MenuItem 'Line wrap'
    Test-Equal 'line wrap can be restored' (Get-MenuLabel 'Line wrap') 'Line wrap: on'

    Toggle-MenuItem 'Single page'
    Test-Equal 'single page can be turned off' (Get-MenuLabel 'Single page') 'Single page: off'
    $paged = Get-UiXml
    $badge = $paged.SelectNodes('//node') | Where-Object {
        $_.text -match '^\d+ / \d+$'
    } | Select-Object -First 1
    Test-Check 'paged mode shows the page badge' ([bool]$badge) $(if ($badge) { $badge.text } else { 'no badge' })

    Toggle-MenuItem 'Single page'
    Test-Equal 'single page can be restored' (Get-MenuLabel 'Single page') 'Single page: on'

    $size = Set-TextSize 22
    Test-Equal 'the text-size dialog follows the slider' $size.Value '22 pt'
    Close-InfoDialog 'Apply'
    Test-Equal 'the chosen text size is applied' (Get-MenuLabel 'Text size') 'Text size: 22 pt'

    # Reopening proves the value survives closing the dialog; a full force-stop
    # is deliberately avoided here because that resets the reader defaults.
    $reopened = Get-TextSizeDialogXml
    $reopenedValue = $reopened.SelectNodes('//node') | Where-Object {
        $_.text -match '^22 pt$'
    } | Select-Object -First 1
    Test-Check 'the text size persists while the document stays open' ([bool]$reopenedValue)
    Tap-Pattern '^Cancel$'
    Wait-ForGone '^Cancel$' 5000 | Out-Null

    # Fixed-layout documents keep pinch zoom; they must not expose text size.
    Open-Doc 'sample.pdf'
    $pdfLabels = @(Get-MenuLabels)
    Test-Check 'PDFs have no text-size option' (-not ($pdfLabels | Where-Object { $_ -match '^Text size:' })) ($pdfLabels -join ', ')
    Test-Check 'PDF menus keep the fixed-layout actions' (
        [bool]($pdfLabels | Where-Object { $_ -match '^Reorder pages' })
    ) ($pdfLabels -join ', ')
    Test-Check 'PDF menus offer Fix PDF first' (
        $pdfLabels.Count -gt 0 -and $pdfLabels[0] -eq 'Fix PDF'
    ) ($pdfLabels -join ', ')
    Test-Check 'PDF menus end with Exit' (
        $pdfLabels.Count -gt 0 -and $pdfLabels[-1] -eq 'Exit'
    ) ($pdfLabels -join ', ')
}

function Test-Selection {
    Open-Doc 'notes.txt'
    Wait-ForPattern '^PDFr plain text fixture$' 12000 | Out-Null

    $numbersBefore = Get-NumericNodes (Get-UiXml)
    Test-Check 'line numbers are shown before selecting' ($numbersBefore.Count -ge 8) "found $($numbersBefore.Count)"

    Invoke-Adb shell input motionevent DOWN 540 900 | Out-Null
    Start-Sleep -Milliseconds 900
    Invoke-Adb shell input motionevent MOVE 660 900 | Out-Null
    Start-Sleep -Milliseconds 300
    Invoke-Adb shell input motionevent UP 660 900 | Out-Null
    Start-Sleep -Milliseconds 900

    $partial = Save-Shot 'selection-partial'
    $partialImage = New-Object PdfrImage -ArgumentList $partial
    $partialStats = $partialImage.MeasureSelection([int]($ScreenWidth * 0.13))

    Test-Check 'long-press selects text natively' ($partialStats.Count -ge 1000) "selection pixels: $($partialStats.Count)"
    Test-Check 'selection handles are visible' ($partialStats.Dark -ge 100) "dark blue pixels: $($partialStats.Dark)"
    Test-Check 'the selection is not the whole document yet' ($partialStats.Count -lt 50000) "selection pixels: $($partialStats.Count)"

    # The custom toolbar lives in the reader window, so uiautomator exposes its
    # buttons. It has Copy and Copy all, but deliberately no Select all.
    $touchMenu = Get-UiXml
    Test-Check 'the touch menu shows Copy' ([bool](Find-Node '^Copy$' -Xml $touchMenu))
    Test-Check 'the touch menu shows Copy all' ([bool](Find-Node '^Copy all$' -Xml $touchMenu))
    Test-Check 'the touch menu has no Select all' (
        -not (Find-Node '^Select all$' -Xml $touchMenu)
    )
    Test-Check 'Copy is enabled while text is selected' (
        (Test-NodeEnabled (Find-Node '^Copy$' -Xml $touchMenu)) -eq $true
    )

    # Copy keeps the manual-selection flow: it puts the selected text, not the
    # whole document, on the clipboard.
    Tap-Pattern '^Copy$'
    Start-Sleep -Seconds 4

    $dialog = Open-InfoDialog
    $edits = Get-EditNodes $dialog
    if ($edits.Count -ge 1) {
        Set-EditText $edits[0] ''
        Invoke-Adb shell input keycombination 113 50 | Out-Null
        Start-Sleep -Milliseconds 900
        $selected = (Get-EditNodes (Get-UiXml))[0].text
        Test-Check 'touch-menu Copy copies the selection' (
            $selected.Length -gt 0 -and $selected -notmatch '^PDFr plain text fixture'
        ) ('copied {0} characters' -f $selected.Length)
        Close-InfoDialog 'Cancel'
    }

    # Copy cleared the selection, so select again before testing Copy all.
    Invoke-Adb shell input motionevent DOWN 540 900 | Out-Null
    Start-Sleep -Milliseconds 900
    Invoke-Adb shell input motionevent MOVE 660 900 | Out-Null
    Start-Sleep -Milliseconds 300
    Invoke-Adb shell input motionevent UP 660 900 | Out-Null
    Start-Sleep -Milliseconds 900

    # Copy all ignores the partial selection and starts at the first line.
    Tap-Pattern '^Copy all$'
    Start-Sleep -Seconds 4

    $dialog = Open-InfoDialog
    $edits = Get-EditNodes $dialog
    if ($edits.Count -ge 1) {
        Set-EditText $edits[0] ''
        Invoke-Adb shell input keycombination 113 50 | Out-Null
        Start-Sleep -Milliseconds 900
        $pasted = (Get-EditNodes (Get-UiXml))[0].text
        Test-Check 'touch-menu Copy all starts at the first line' (
            $pasted -match '^PDFr plain text fixture'
        ) $(if ($pasted) { $pasted.Substring(0, [Math]::Min(40, $pasted.Length)) } else { 'empty' })
        Test-Check 'touch-menu Copy all ignores the selection' (
            $pasted -match 'quick brown fox'
        ) ('pasted {0} characters' -f $pasted.Length)
        Close-InfoDialog 'Cancel'
    }

    Invoke-Adb shell input tap ([int]($ScreenWidth * 0.5)) ([int]($ScreenHeight * 0.72)) | Out-Null
    Start-Sleep -Milliseconds 700
    $cleared = Save-Shot 'selection-cleared'
    $clearedImage = New-Object PdfrImage -ArgumentList $cleared
    $clearedStats = $clearedImage.MeasureSelection([int]($ScreenWidth * 0.13))
    Test-Check 'tapping clears the selection' ($clearedStats.Count -lt 1000) "selection pixels: $($clearedStats.Count)"
    Test-Check 'the reader survives clearing the selection' (
        [bool](Find-Node '^PDFr plain text fixture$')
    )

    # "Copy all" must take the whole document from the model. The lazy list
    # only composes the rows near the viewport. A 40 pt text size keeps the
    # viewport to a handful of lines, while the pasted clipboard text still
    # reaches L030.
    Open-Doc 'long.txt'
    Wait-ForPattern '^L001$' 12000 | Out-Null
    Set-TextSize 40 | Out-Null
    Close-InfoDialog 'Apply'

    $labels = Get-MenuLabels
    Test-Check 'the overflow menu offers Copy all' ($labels -contains 'Copy all') ($labels -join ', ')
    Test-Check 'Copy all is the first overflow entry' (
        $labels.Count -gt 0 -and $labels[0] -eq 'Copy all'
    ) ($labels -join ', ')

    Open-Overflow
    Tap-Pattern '^Copy all$'
    # Android shows its own clipboard preview (and a share button) for a few
    # seconds. Both are blue-tinted, so wait for them to fade before measuring
    # whether the reader itself drew a selection. The preview can linger longer
    # than a fixed delay, so retry until the frame is clean.
    $copyStats = $null
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        Start-Sleep -Seconds 2
        $copyShot = Save-Shot 'copy-all'
        $copyImage = New-Object PdfrImage -ArgumentList $copyShot
        $copyStats = $copyImage.MeasureSelection([int]($ScreenWidth * 0.13))
        if ($copyStats.Count -lt 500) { break }
    }
    Test-Check 'Copy all from the overflow menu draws no selection' (
        $copyStats.Count -lt 500
    ) "selection pixels: $($copyStats.Count)"
    Test-Check 'Copy all closes the menu' (-not (Test-MenuOpen))

    # Paste into the filename field to inspect the clipboard. That field keeps
    # 200 characters, which is about 40 five-character lines: far past what the
    # 40 pt viewport composed.
    $dialog = Open-InfoDialog
    $edits = Get-EditNodes $dialog
    if ($edits.Count -ge 1) {
        Set-EditText $edits[0] ''
        Invoke-Adb shell input keycombination 113 50 | Out-Null
        Start-Sleep -Milliseconds 900
        $pasted = (Get-EditNodes (Get-UiXml))[0].text
        Test-Check 'Copy all starts at the first line' (
            $pasted -match '^L001'
        ) $(if ($pasted) { $pasted.Substring(0, [Math]::Min(40, $pasted.Length)) } else { 'empty' })
        Test-Check 'Copy all includes lines beyond the viewport' (
            $pasted -match 'L030'
        ) ('pasted {0} characters' -f $pasted.Length)
        Close-InfoDialog 'Cancel'
    }
}

function Test-Metadata {
    Open-Doc 'notes.txt'
    Wait-ForPattern '^PDFr plain text fixture$' 12000 | Out-Null

    $dialog = Open-InfoDialog
    $edits = Get-EditNodes $dialog
    Test-Check 'the filename and extension are editable' ($edits.Count -ge 4) "found $($edits.Count) fields"
    if ($edits.Count -ge 4) {
        Test-Equal 'the filename starts as the document name' $edits[0].text 'notes'
        Test-Equal 'the extension is separate' $edits[1].text 'txt'
        Test-Match 'the created date is ISO' $edits[2].text '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$'
        Test-Match 'the modified date is ISO' $edits[3].text '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$'
    }

    $visibleText = @($dialog.SelectNodes('//node') | Where-Object { $_.text } | ForEach-Object { $_.text })
    Test-Check 'encryption details are not shown' (
        -not ($visibleText | Where-Object { $_ -match 'Encrypt|Password' })
    )
    Test-Check 'empty standard slots are still offered' (
        ($visibleText -contains 'Title') -and
        ($visibleText -contains 'Author') -and
        ($visibleText -contains 'Producer')
    )

    $dialogHeight = Get-DialogHeight $dialog
    Test-Check 'the info dialog is capped below the screen' (
        $dialogHeight -gt 200 -and $dialogHeight -le ($ScreenHeight * 0.76)
    ) "dialog height: $dialogHeight"

    if ($edits.Count -ge 3) {
        Set-EditText $edits[0] 'draftname'
        Dismiss-Dialog
        Test-Check 'dismissing keeps the draft' (
            [bool](Wait-ForGone '^Apply$' 5000)
        )

        $reopened = Open-InfoDialog
        Test-Equal 'the draft survives reopening' (Get-EditNodes $reopened)[0].text 'draftname'

        Close-InfoDialog 'Cancel'
        $afterCancel = Open-InfoDialog
        Test-Equal 'Cancel discards the draft' (Get-EditNodes $afterCancel)[0].text 'notes'

        $current = Get-EditNodes $afterCancel
        Set-EditText $current[0] 'editednotes'
        Tap-Pattern '^Apply$'
        Wait-ForGone '^Apply$' 12000 | Out-Null
        Start-Sleep -Milliseconds 1200
        Test-Check 'the reader reloads after Apply' (
            [bool](Find-Node '^PDFr plain text fixture$')
        )

        $applied = Open-InfoDialog
        Test-Equal 'Apply writes the edited filename' (Get-EditNodes $applied)[0].text 'editednotes'
        Close-InfoDialog 'Cancel'

        # Save as gets the edited name in the system picker.
        Tap-Pattern '^Save as$'
        Start-Sleep -Seconds 2
        $picker = Get-UiXml
        $pickerNames = @($picker.SelectNodes('//node') | Where-Object {
            ($_.text -match 'editednotes') -or ($_.'content-desc' -match 'editednotes')
        })
        Test-Check 'Save as uses the edited name' ($pickerNames.Count -gt 0) $(if ($pickerNames.Count) { $pickerNames[0].text } else { 'name not found' })

        for ($attempt = 0; $attempt -lt 2; $attempt++) {
            if (Test-PackageVisible 'com.karako.pdfr') { break }
            Invoke-Adb shell input keyevent 4 | Out-Null
            Start-Sleep -Milliseconds 700
        }
        Wait-ForPattern '^PDFr plain text fixture$' 8000 | Out-Null

        # Share makes a cache copy under the edited name before opening the
        # chooser. Checking the copy proves the chooser received that name.
        Tap-Pattern '^Share document$'
        Start-Sleep -Seconds 2
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $shareCopy = & $adb shell run-as com.karako.pdfr find cache/documents -type f -name 'editednotes.txt' 2>&1
        } finally {
            $ErrorActionPreference = $previous
        }
        Test-Check 'Share uses the edited name' (($shareCopy -join ' ') -match 'editednotes\.txt') ($shareCopy -join ' ')

        Invoke-Adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 700
        Test-Check 'dismissing Share returns to the reader' (
            [bool](Wait-ForPattern '^PDFr plain text fixture$' 8000)
        )
    }
}

function Test-Gestures {
    Enable-Root

    Open-Doc 'notes.txt'
    Wait-ForPattern '^PDFr plain text fixture$' 12000 | Out-Null
    Invoke-ZoomProbe 'text-pinch' 'pinch-out'
    $textBefore = Join-Path $OutDir 'text-pinch-before.png'
    $textSettled = Join-Path $OutDir 'text-pinch-settled.png'
    $textDiff = [PdfrImage]::Compare($textBefore, $textSettled)
    Test-Check 'a pinch does not zoom reflowable text' (
        $textDiff.Comparable -and $textDiff.Mean -lt 1.5 -and $textDiff.Changed -lt 0.02
    ) ('mean {0:N2}, changed {1:P1}' -f $textDiff.Mean, $textDiff.Changed)

    Open-Doc 'sample.pdf'
    Invoke-ZoomProbe 'pdf-pinch' 'pinch-out' 0.20
    $pdfBefore = Join-Path $OutDir 'pdf-pinch-before.png'
    $pdfSettled = Join-Path $OutDir 'pdf-pinch-settled.png'
    $pdfDiff = [PdfrImage]::Compare($pdfBefore, $pdfSettled)
    Test-Check 'a pinch zooms a fixed-layout PDF' (
        $pdfDiff.Comparable -and (($pdfDiff.Mean -gt 1.0) -or ($pdfDiff.Changed -gt 0.03))
    ) ('mean {0:N2}, changed {1:P1}' -f $pdfDiff.Mean, $pdfDiff.Changed)
}

# --- Run ---

$state = (& $adb get-state 2>$null) -join ''
if ($state.Trim() -ne 'device') {
    throw "No Android device is connected. Start the emulator first.`n$state"
}

$packagePath = (& $adb shell pm path com.karako.pdfr 2>$null) -join ''
if ($packagePath -notmatch 'package:') {
    throw 'com.karako.pdfr is not installed. Run tools\install-debug.bat first.'
}

Invoke-Adb shell svc power stayon true | Out-Null
Invoke-Adb shell input keyevent 224 | Out-Null
Invoke-Adb shell wm dismiss-keyguard | Out-Null

$selected = @($Section)
if ($selected -contains 'all') {
    $selected = @('empty', 'text', 'selection', 'metadata', 'gestures', 'exit')
}

if ($selected -contains 'empty') {
    Test-Section 'No document open' { Test-EmptyState }
}
if ($selected -contains 'text') {
    Test-Section 'Plain text settings' { Test-PlainTextSettings }
}
if ($selected -contains 'selection') {
    Test-Section 'Selection' { Test-Selection }
}
if ($selected -contains 'metadata') {
    Test-Section 'Metadata editor' { Test-Metadata }
}
if ($selected -contains 'gestures') {
    Test-Section 'Gestures' { Test-Gestures }
}
if ($selected -contains 'exit') {
    Test-Section 'Exit' { Test-Exit }
}

$report = Join-Path $OutDir 'results.txt'
$script:Checks | ForEach-Object {
    $status = if ($_.Ok) { 'PASS' } else { 'FAIL' }
    "{0}  {1}  {2}  {3}" -f $status, $_.Section, $_.Name, $_.Detail
} | Set-Content -Path $report -Encoding UTF8

$failed = @($script:Checks | Where-Object { -not $_.Ok })
$passed = $script:Checks.Count - $failed.Count

Write-Host ''
Write-Host "=== $passed passed, $($failed.Count) failed ==="
Write-Host "[PDFr] Report: $report"

if (-not $KeepOpen) {
    Invoke-Adb shell am force-stop com.karako.pdfr | Out-Null
}

if ($failed.Count -gt 0) {
    exit 1
}
exit 0

<#
.SYNOPSIS
    Draws the Android TV banner (320x180) used by android:banner.

.DESCRIPTION
    Android TV launchers require a banner image rather than the square launcher
    icon. This script uses System.Drawing, so it needs no extra tools. The
    output goes to app\src\main\res\drawable-nodpi\tv_banner.png by default.

.PARAMETER OutFile
    Destination PNG. Defaults to the app's drawable-nodpi folder.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\make-tv-banner.ps1
#>
param(
    [string]$OutFile
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repo = Split-Path -Parent $PSScriptRoot
if (-not $OutFile) {
    $OutFile = Join-Path $repo 'app\src\main\res\drawable-nodpi\tv_banner.png'
}
$OutFile = [System.IO.Path]::GetFullPath($OutFile)
$dir = Split-Path -Parent $OutFile
New-Item -ItemType Directory -Force $dir | Out-Null

$width = 320
$height = 180
$bitmap = New-Object System.Drawing.Bitmap($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

# Deep blue gradient, the same family as the adaptive icon background.
$rect = New-Object System.Drawing.Rectangle(0, 0, $width, $height)
$brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    $rect,
    [System.Drawing.Color]::FromArgb(255, 16, 42, 67),
    [System.Drawing.Color]::FromArgb(255, 38, 92, 140),
    45.0
)
$graphics.FillRectangle($brush, $rect)

# A simple document sheet on the left.
$sheet = New-Object System.Drawing.RectangleF(38, 38, 78, 104)
$sheetBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 245, 248, 252))
$graphics.FillRectangle($sheetBrush, $sheet)
$foldBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 185, 202, 219))
$graphics.FillPolygon($foldBrush, [System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF(92, 38)),
    (New-Object System.Drawing.PointF(116, 62)),
    (New-Object System.Drawing.PointF(92, 62))
))
$linePen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 120, 150, 180), 4)
foreach ($y in 82, 96, 110, 124) {
    $graphics.DrawLine($linePen, 52, $y, 100, $y)
}

# Title and subtitle.
$titleFont = New-Object System.Drawing.Font('Segoe UI', 42, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$subFont = New-Object System.Drawing.Font('Segoe UI', 17, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$muted = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 198, 219, 238))
$graphics.DrawString('PDFr', $titleFont, $white, 138, 55)
$graphics.DrawString('Read anything', $subFont, $muted, 141, 108)

$bitmap.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)

$titleFont.Dispose(); $subFont.Dispose()
$white.Dispose(); $muted.Dispose()
$linePen.Dispose(); $sheetBrush.Dispose(); $foldBrush.Dispose(); $brush.Dispose()
$graphics.Dispose(); $bitmap.Dispose()

Write-Host "Wrote $OutFile ($width x $height)"

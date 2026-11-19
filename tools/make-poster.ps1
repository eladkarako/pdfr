#Requires -Version 5.1
<#
.SYNOPSIS
    Compose 3-4 PDFr screenshots into a single promotional poster.

.DESCRIPTION
    Reads the screenshots captured by tools\capture-promo.ps1 (or any PNGs you
    pass with -Images) and writes a shareable poster with a dark gradient
    background, a title, rounded screenshot cards and a footer.

    System.Drawing is part of Windows PowerShell, so no ImageMagick or ffmpeg
    install is needed.

.EXAMPLE
    tools\make-poster.ps1

.EXAMPLE
    tools\make-poster.ps1 -OutDir build\promo -Images shot1.png,shot2.png,shot3.png
#>
param(
    [string]$OutDir = (Join-Path $PSScriptRoot '..\build\promo'),
    [string[]]$Images = @(),
    [string]$OutputName = 'poster.png',
    [string]$Title = 'PDFr',
    [string]$Subtitle = 'Read anything.',
    [int]$Width = 1600,
    [int]$Columns = 2,
    [string]$Background = '#0F1720',
    [string]$BackgroundEnd = '#22384D',
    [string]$Accent = '#E0A526',
    [string]$Foreground = '#F5F1E8'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# powershell.exe -File passes "-Images a.png,b.png" as one string, so split
# comma-separated lists as well as accepting an array from a direct call.
$Images = @($Images | ForEach-Object { $_ -split ',' } | Where-Object { $_ })

Add-Type -AssemblyName System.Drawing

$OutDir = [System.IO.Path]::GetFullPath($OutDir)
if (-not (Test-Path $OutDir)) {
    throw "Output folder not found: $OutDir"
}

function Resolve-ImageList {
    if ($Images.Count -gt 0) {
        return $Images | ForEach-Object {
            if ([System.IO.Path]::IsPathRooted($_)) { $_ } else { Join-Path $OutDir $_ }
        }
    }

    $preferred = @(
        '02-pdf-cover.png',
        '03-pdf-colour.png',
        '04-overflow.png',
        '06-epub.png'
    )
    $chosen = @()
    foreach ($name in $preferred) {
        $candidate = Join-Path $OutDir $name
        if (Test-Path $candidate) { $chosen += $candidate }
    }

    if ($chosen.Count -lt 3) {
        $chosen = Get-ChildItem -Path $OutDir -Filter '*.png' |
            Where-Object { $_.Name -ne $OutputName } |
            Sort-Object Name |
            Select-Object -First 4 -ExpandProperty FullName
    }

    return @($chosen | Select-Object -First 4)
}

function New-RoundedPath([System.Drawing.RectangleF]$Rect, [float]$Radius) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $diameter = $Radius * 2
    $path.AddArc($Rect.X, $Rect.Y, $diameter, $diameter, 180, 90)
    $path.AddArc($Rect.Right - $diameter, $Rect.Y, $diameter, $diameter, 270, 90)
    $path.AddArc($Rect.Right - $diameter, $Rect.Bottom - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($Rect.X, $Rect.Bottom - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

$paths = @(Resolve-ImageList)
if ($paths.Count -lt 1) {
    throw "No screenshots found in $OutDir. Run tools\capture-promo.ps1 first."
}

$loaded = @()
try {
    foreach ($path in $paths) {
        if (-not (Test-Path $path)) { throw "Screenshot not found: $path" }
        $loaded += [System.Drawing.Image]::FromFile($path)
    }

    $padding = 72
    $gap = 28
    $headerHeight = 300
    $footerHeight = 120
    $cellWidth = [int](($Width - ($padding * 2) - ($gap * ($Columns - 1))) / $Columns)
    $aspect = $loaded[0].Width / $loaded[0].Height
    $cellHeight = [int]($cellWidth / $aspect)
    $rows = [int][Math]::Ceiling($loaded.Count / $Columns)
    $height = $headerHeight + ($rows * $cellHeight) + (($rows - 1) * $gap) + $footerHeight

    $bitmap = New-Object -TypeName System.Drawing.Bitmap -ArgumentList $Width, $height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

    $backgroundRect = [System.Drawing.Rectangle]::new(0, 0, $Width, $height)
    $backgroundBrush = New-Object -TypeName System.Drawing.Drawing2D.LinearGradientBrush `
        -ArgumentList @(
            $backgroundRect,
            [System.Drawing.ColorTranslator]::FromHtml($Background),
            [System.Drawing.ColorTranslator]::FromHtml($BackgroundEnd),
            90.0
        )
    $graphics.FillRectangle($backgroundBrush, $backgroundRect)

    $accentColor = [System.Drawing.ColorTranslator]::FromHtml($Accent)
    $foregroundColor = [System.Drawing.ColorTranslator]::FromHtml($Foreground)
    $mutedColor = [System.Drawing.Color]::FromArgb(190, $foregroundColor)

    $titleFont = New-Object -TypeName System.Drawing.Font -ArgumentList @(
        'Segoe UI', 96, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel
    )
    $subtitleFont = New-Object -TypeName System.Drawing.Font -ArgumentList @(
        'Segoe UI', 34, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel
    )
    $footerFont = New-Object -TypeName System.Drawing.Font -ArgumentList @(
        'Segoe UI', 22, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel
    )
    $titleBrush = New-Object -TypeName System.Drawing.SolidBrush -ArgumentList $foregroundColor
    $mutedBrush = New-Object -TypeName System.Drawing.SolidBrush -ArgumentList $mutedColor
    $accentBrush = New-Object -TypeName System.Drawing.SolidBrush -ArgumentList $accentColor

    $graphics.DrawString($Title, $titleFont, $titleBrush, [float]$padding, 72)
    $titleSize = $graphics.MeasureString($Title, $titleFont)
    $graphics.FillRectangle(
        $accentBrush,
        [float]$padding,
        [float](72 + $titleSize.Height + 6),
        [float]160,
        [float]8
    )
    $graphics.DrawString(
        $Subtitle,
        $subtitleFont,
        $mutedBrush,
        [float]$padding,
        [float](72 + $titleSize.Height + 34)
    )

    $borderPen = New-Object -TypeName System.Drawing.Pen -ArgumentList $accentColor, 3.0
    $shadowBrush = New-Object -TypeName System.Drawing.SolidBrush -ArgumentList ([System.Drawing.Color]::FromArgb(90, 0, 0, 0))
    $cardFill = New-Object -TypeName System.Drawing.SolidBrush -ArgumentList ([System.Drawing.Color]::FromArgb(255, 18, 28, 38))

    for ($index = 0; $index -lt $loaded.Count; $index++) {
        $image = $loaded[$index]
        $row = [int][Math]::Floor($index / $Columns)
        $column = $index % $Columns
        $itemsInRow = [Math]::Min($Columns, $loaded.Count - ($row * $Columns))
        $rowWidth = ($itemsInRow * $cellWidth) + (($itemsInRow - 1) * $gap)
        $rowStart = [int](($Width - $rowWidth) / 2)
        $x = $rowStart + ($column * ($cellWidth + $gap))
        $y = $headerHeight + ($row * ($cellHeight + $gap))

        $shadowRect = [System.Drawing.RectangleF]::new(
            [float]($x + 6), [float]($y + 14), [float]$cellWidth, [float]$cellHeight
        )
        $shadowPath = New-RoundedPath $shadowRect 28
        $graphics.FillPath($shadowBrush, $shadowPath)
        $shadowPath.Dispose()

        $cardRect = [System.Drawing.RectangleF]::new(
            [float]$x, [float]$y, [float]$cellWidth, [float]$cellHeight
        )
        $cardPath = New-RoundedPath $cardRect 28
        $graphics.FillPath($cardFill, $cardPath)

        $graphics.SetClip($cardPath)
        $scale = [Math]::Min($cellWidth / $image.Width, $cellHeight / $image.Height)
        $drawWidth = [int]($image.Width * $scale)
        $drawHeight = [int]($image.Height * $scale)
        $drawX = [int]($x + (($cellWidth - $drawWidth) / 2))
        $drawY = [int]($y + (($cellHeight - $drawHeight) / 2))
        $graphics.DrawImage($image, $drawX, $drawY, $drawWidth, $drawHeight)
        $graphics.ResetClip()

        $graphics.DrawPath($borderPen, $cardPath)
        $cardPath.Dispose()
    }

    $footerY = $height - $footerHeight + 28
    $graphics.DrawString(
        'PDFr  |  MuPDF-powered  |  no ads  |  no tracking',
        $footerFont,
        $mutedBrush,
        [float]$padding,
        [float]$footerY
    )
    $footer = 'github.com/eladkarako/pdfr'
    $footerSize = $graphics.MeasureString($footer, $footerFont)
    $graphics.DrawString(
        $footer,
        $footerFont,
        $accentBrush,
        [float]($Width - $padding - $footerSize.Width),
        [float]$footerY
    )

    $outputPath = Join-Path $OutDir $OutputName
    $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Host "[PDFr] Poster written to $outputPath"

    $graphics.Dispose()
    $bitmap.Dispose()
    $titleFont.Dispose()
    $subtitleFont.Dispose()
    $footerFont.Dispose()
    $titleBrush.Dispose()
    $mutedBrush.Dispose()
    $accentBrush.Dispose()
    $borderPen.Dispose()
    $shadowBrush.Dispose()
    $cardFill.Dispose()
    $backgroundBrush.Dispose()
}
finally {
    foreach ($image in $loaded) { $image.Dispose() }
}

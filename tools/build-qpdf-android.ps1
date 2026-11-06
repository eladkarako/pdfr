<#
.SYNOPSIS
    Build the static qpdf JNI bridge for Android and refresh app/src/main/jniLibs.

.DESCRIPTION
    Downloads qpdf and libjpeg-turbo, builds both as static libraries for each
    requested Android ABI with the NDK, links app/src/main/cpp/qpdf_jni.cpp,
    strips the result and copies it to app/src/main/jniLibs/<abi>/.

    The checked-in libraries are enough for a normal Gradle build. Run this
    script only after changing qpdf_jni.cpp, upgrading qpdf/libjpeg-turbo, or
    adding an ABI.

    Requirements: Android SDK with NDK 30+, CMake 3.22+ (the SDK copy is fine),
    curl and tar (both ship with Windows 10/11).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\build-qpdf-android.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\build-qpdf-android.ps1 `
        -Abis arm64-v8a,x86_64 -Clean
#>

[CmdletBinding()]
param(
    [string[]] $Abis = @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'),
    [string] $QpdfVersion = '12.4.2',
    [string] $JpegVersion = '3.2.1',
    [string] $AndroidPlatform = 'android-24',
    [string] $Ndk = $env:ANDROID_NDK_ROOT,
    [string] $Sdk = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }),
    [switch] $Clean,
    [switch] $SkipDownload
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# `powershell -File ... -Abis a,b,c` can arrive as one string; accept both.
if ($Abis.Count -eq 1 -and $Abis[0].Contains(',')) {
    $Abis = $Abis[0].Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}

$Repo = Split-Path -Parent $PSScriptRoot
$Work = Join-Path $Repo 'build\qpdf'
$Src = Join-Path $Work 'src'
$JniLibs = Join-Path $Repo 'app\src\main\jniLibs'
$Cpp = Join-Path $Repo 'app\src\main\cpp'
$Jobs = [Math]::Max(1, [Environment]::ProcessorCount)

function Write-Step([string] $Text) {
    Write-Host ''
    Write-Host "[PDFr] $Text" -ForegroundColor Cyan
}

function Fail([string] $Text) {
    Write-Host "[PDFr] ERROR: $Text" -ForegroundColor Red
    exit 1
}

function Find-Sdk {
    if (-not $script:Sdk -and (Test-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        $script:Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    if (-not $script:Sdk -or -not (Test-Path $script:Sdk)) {
        Fail 'Android SDK not found. Set ANDROID_SDK_ROOT.'
    }
}

function Find-Ndk {
    if ($Ndk -and (Test-Path (Join-Path $Ndk 'build\cmake\android.toolchain.cmake'))) {
        return (Resolve-Path $Ndk).Path
    }
    $roots = @(
        (Join-Path $Sdk 'ndk'),
        (Join-Path $Sdk 'ndk-bundle')
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $candidate = Get-ChildItem $root -Directory |
            Where-Object { Test-Path (Join-Path $_.FullName 'build\cmake\android.toolchain.cmake') } |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    Fail 'Android NDK not found. Set ANDROID_NDK_ROOT.'
}

function Find-CMake {
    $cmake = Get-ChildItem (Join-Path $Sdk 'cmake') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($cmake) {
        $exe = Join-Path $cmake.FullName 'bin\cmake.exe'
        if (Test-Path $exe) {
            # Prefer the SDK's CMake, and make its matching ninja visible.
            $env:PATH = "$(Join-Path $cmake.FullName 'bin');$env:PATH"
            return $exe
        }
    }
    $onPath = Get-Command cmake -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    Fail 'CMake not found. Install it from the SDK Manager or put it on PATH.'
}

function Get-NdkTool([string] $Name) {
    $hostTag = if ($env:OS -eq 'Windows_NT') { 'windows-x86_64' } else { 'linux-x86_64' }
    $tool = Join-Path $Ndk "toolchains\llvm\prebuilt\$hostTag\bin\$Name"
    if (-not (Test-Path $tool)) {
        Fail "NDK tool not found: $tool"
    }
    return $tool
}

function Download([string] $Url, [string] $Destination) {
    if ($SkipDownload) {
        Fail "Missing $([System.IO.Path]::GetFileName($Destination)); run without -SkipDownload to download it."
    }
    Write-Host "[PDFr] Downloading $Url"
    & curl.exe -fsSL $Url -o $Destination
    if ($LASTEXITCODE -ne 0) { Fail "download failed: $Url" }
}

function Assert-Exit([string] $What) {
    if ($LASTEXITCODE -ne 0) { Fail $What }
}

function Prepare-Sources {
    New-Item -ItemType Directory -Force -Path $Src | Out-Null

    $qpdfDir = Join-Path $Src "qpdf-$QpdfVersion"
    if (-not (Test-Path $qpdfDir)) {
        $archive = Join-Path $Src "qpdf-$QpdfVersion.tar.gz"
        if (-not (Test-Path $archive)) {
            Download "https://github.com/qpdf/qpdf/releases/download/v$QpdfVersion/qpdf-$QpdfVersion.tar.gz" $archive
        }
        & tar -xzf $archive -C $Src
        Assert-Exit 'could not extract qpdf'
    }

    $jpegDir = Join-Path $Src "libjpeg-turbo-$JpegVersion"
    if (-not (Test-Path $jpegDir)) {
        $archive = Join-Path $Src "libjpeg-turbo-$JpegVersion.tar.gz"
        if (-not (Test-Path $archive)) {
            Download "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/$JpegVersion/libjpeg-turbo-$JpegVersion.tar.gz" $archive
        }
        & tar -xzf $archive -C $Src
        Assert-Exit 'could not extract libjpeg-turbo'
    }

    return @{
        Qpdf = $qpdfDir
        Jpeg = $jpegDir
    }
}

function Invoke-Build([string[]] $Arguments, [string] $What) {
    Write-Host "[PDFr] $What"
    & $CMake @Arguments
    Assert-Exit $What
}

Find-Sdk
$Ndk = Find-Ndk
$CMake = Find-CMake
$Strip = Get-NdkTool 'llvm-strip.exe'
$Sources = Prepare-Sources

$common = @(
    '-G', 'Ninja',
    "-DCMAKE_TOOLCHAIN_FILE=$Ndk\build\cmake\android.toolchain.cmake",
    "-DANDROID_PLATFORM=$AndroidPlatform",
    '-DCMAKE_BUILD_TYPE=Release'
)

Write-Host "[PDFr] Repo:     $Repo"
Write-Host "[PDFr] NDK:      $Ndk"
Write-Host "[PDFr] CMake:    $CMake"
Write-Host "[PDFr] qpdf:     $($Sources.Qpdf)"
Write-Host "[PDFr] jpegturbo:$($Sources.Jpeg)"
Write-Host "[PDFr] ABIs:     $($Abis -join ', ')"

foreach ($Abi in $Abis) {
    Write-Step "Building $Abi"

    $Prefix = Join-Path $Work "prefix-$Abi"
    $JpegBuild = Join-Path $Work "jpeg-$Abi"
    $QpdfBuild = Join-Path $Work "qpdf-$Abi"
    $JniBuild = Join-Path $Work "jni-$Abi"

    if ($Clean) {
        foreach ($dir in @($Prefix, $JpegBuild, $QpdfBuild, $JniBuild)) {
            Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    # --- libjpeg-turbo (static, no SIMD / tools / tests) -------------------
    New-Item -ItemType Directory -Force -Path $JpegBuild | Out-Null
    Invoke-Build (@(
        '-S', $Sources.Jpeg,
        '-B', $JpegBuild
    ) + $common + @(
        "-DANDROID_ABI=$Abi",
        '-DENABLE_SHARED=OFF',
        '-DENABLE_STATIC=ON',
        '-DWITH_TURBOJPEG=OFF',
        '-DWITH_SIMD=OFF',
        '-DWITH_TOOLS=OFF',
        '-DWITH_TESTS=OFF',
        "-DCMAKE_INSTALL_PREFIX=$Prefix"
    )) "configure libjpeg-turbo ($Abi)"

    & $CMake --build $JpegBuild --target install -j $Jobs
    Assert-Exit "build libjpeg-turbo ($Abi)"

    $JpegLib = Join-Path $Prefix 'lib\libjpeg.a'
    if (-not (Test-Path $JpegLib)) { Fail "libjpeg.a was not installed for $Abi" }

    # --- qpdf (static, native crypto, static jpeg) -------------------------
    New-Item -ItemType Directory -Force -Path $QpdfBuild | Out-Null
    Invoke-Build (@(
        '-S', $Sources.Qpdf,
        '-B', $QpdfBuild
    ) + $common + @(
        "-DANDROID_ABI=$Abi",
        '-DBUILD_SHARED_LIBS=OFF',
        '-DBUILD_STATIC_LIBS=ON',
        '-DUSE_IMPLICIT_CRYPTO=OFF',
        '-DREQUIRE_CRYPTO_NATIVE=ON',
        '-DDEFAULT_CRYPTO=native',
        '-DSTATIC_JPEG=ON',
        '-DBUILD_DOC=OFF',
        '-DBUILD_TESTING=OFF',
        '-DINSTALL_EXAMPLES=OFF',
        '-DINSTALL_MANUAL=OFF',
        '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
        # qpdf forces non-PIC for static builds. The JNI shared library needs
        # PIC objects, so force the flag through the global compiler flags.
        '-DCMAKE_C_FLAGS=-fPIC',
        '-DCMAKE_CXX_FLAGS=-fPIC',
        "-DLIBJPEG_H_PATH=$Prefix\include",
        "-DLIBJPEG_LIB_PATH=$JpegLib",
        '-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH',
        '-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH',
        "-DCMAKE_INSTALL_PREFIX=$Prefix"
    )) "configure qpdf ($Abi)"

    & $CMake --build $QpdfBuild --target libqpdf -j $Jobs
    Assert-Exit "build libqpdf ($Abi)"

    $QpdfLib = Join-Path $QpdfBuild 'libqpdf\libqpdf.a'
    if (-not (Test-Path $QpdfLib)) { Fail "libqpdf.a was not built for $Abi" }

    # --- JNI bridge --------------------------------------------------------
    New-Item -ItemType Directory -Force -Path $JniBuild | Out-Null
    Invoke-Build (@(
        '-S', $Cpp,
        '-B', $JniBuild
    ) + $common + @(
        "-DANDROID_ABI=$Abi",
        '-DANDROID_STL=c++_static',
        "-DQPDF_SOURCE_DIR=$($Sources.Qpdf)",
        "-DQPDF_BUILD_DIR=$QpdfBuild",
        "-DQPDF_LIB=$QpdfLib",
        "-DJPEG_LIB=$JpegLib"
    )) "configure qpdf JNI ($Abi)"

    & $CMake --build $JniBuild -j $Jobs
    Assert-Exit "link libqpdfjni.so ($Abi)"

    $Built = Join-Path $JniBuild 'libqpdfjni.so'
    if (-not (Test-Path $Built)) { Fail "libqpdfjni.so was not built for $Abi" }

    $DestinationDir = Join-Path $JniLibs $Abi
    New-Item -ItemType Directory -Force -Path $DestinationDir | Out-Null
    $Destination = Join-Path $DestinationDir 'libqpdfjni.so'
    Copy-Item $Built $Destination -Force

    & $Strip --strip-unneeded $Destination
    Assert-Exit "strip $Abi"

    $size = (Get-Item $Destination).Length
    Write-Host ("[PDFr] {0,-12} libqpdfjni.so {1,10:N0} bytes" -f $Abi, $size) -ForegroundColor Green
    if ($size -lt 1MB) {
        Write-Host "[PDFr] WARNING: the stripped library looks unexpectedly small." -ForegroundColor Yellow
    }
}

Write-Step 'Done'
Write-Host "[PDFr] Updated $JniLibs"
Write-Host '[PDFr] Rebuild the APK with: gradlew :app:assembleDebug'

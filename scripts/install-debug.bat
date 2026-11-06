@echo off
setlocal
call "%~dp0_env.bat"

rem Usage: install-debug.bat [abi]
rem ABI defaults to the architecture reported by the connected device.

call "%PDFR_ROOT%\gradlew.bat" :app:assembleDebug --console=plain
if errorlevel 1 (
  echo [ERROR] Build failed.
  exit /b 1
)

set "ABI=%~1"
set "ABI_FILE=%TEMP%\pdfr-abi-%RANDOM%.txt"
if not defined ABI (
  "%ADB%" shell getprop ro.product.cpu.abi > "%ABI_FILE%" 2>nul
  set /p ABI=<"%ABI_FILE%"
  del "%ABI_FILE%" >nul 2>&1
)
if not defined ABI set "ABI=x86_64"

set "APK=%PDFR_ROOT%\app\build\outputs\apk\debug\app-%ABI%-debug.apk"
if not exist "%APK%" (
  echo [ERROR] APK not found: "%APK%"
  echo         Build once, or pass a supported ABI: armeabi-v7a, arm64-v8a, x86, x86_64.
  exit /b 1
)

echo [PDFr] Installing %ABI% debug APK...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo [ERROR] Installation failed.
  exit /b 1
)

echo [PDFr] Installed com.karako.pdfr (%ABI%).
exit /b 0

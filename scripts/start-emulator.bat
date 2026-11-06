@echo off
setlocal
call "%~dp0_env.bat"

if not "%~1"=="" set "PDFR_AVD=%~1"

if not exist "%EMULATOR%" (
  echo [ERROR] Android emulator not found at "%EMULATOR%".
  echo         Set ANDROID_SDK_ROOT or EMULATOR and try again.
  exit /b 1
)

set "AVD_LIST=%TEMP%\pdfr-avds-%RANDOM%.txt"
"%EMULATOR%" -list-avds > "%AVD_LIST%" 2>nul
findstr /x /c:"%PDFR_AVD%" "%AVD_LIST%" >nul
if errorlevel 1 (
  echo [ERROR] AVD "%PDFR_AVD%" was not found.
  echo         Available AVDs:
  type "%AVD_LIST%"
  echo.
  echo         Set PDFR_AVD to one of them, or create an AVD named pdfr_full.
  del "%AVD_LIST%" >nul 2>&1
  exit /b 1
)
del "%AVD_LIST%" >nul 2>&1

echo [PDFr] Starting AVD "%PDFR_AVD%"...
start "PDFr emulator - %PDFR_AVD%" "%EMULATOR%" -avd "%PDFR_AVD%" -no-snapshot-load -no-boot-anim

if not exist "%ADB%" (
  echo [ERROR] adb not found at "%ADB%".
  exit /b 1
)

echo [PDFr] Waiting for adb...
"%ADB%" start-server >nul 2>&1
"%ADB%" wait-for-device

set "BOOT_FILE=%TEMP%\pdfr-boot-%RANDOM%.txt"
:wait_boot
set "BOOT="
"%ADB%" shell getprop sys.boot_completed > "%BOOT_FILE%" 2>nul
set /p BOOT=<"%BOOT_FILE%"
if not "%BOOT%"=="1" (
  ping -n 3 127.0.0.1 >nul
  goto wait_boot
)
del "%BOOT_FILE%" >nul 2>&1

"%ADB%" shell wm dismiss-keyguard >nul 2>&1
"%ADB%" shell input keyevent KEYCODE_WAKEUP >nul 2>&1
echo [PDFr] Device is ready: %PDFR_AVD%
exit /b 0

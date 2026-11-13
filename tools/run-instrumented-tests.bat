@echo off
setlocal
call "%~dp0_env.bat"

rem Usage: run-instrumented-tests.bat [fully.qualified.TestClass[#method]] [--start-emulator]
rem Example: run-instrumented-tests.bat com.karako.pdfr.MuPdfDocumentInstrumentedTest
rem          run-instrumented-tests.bat --start-emulator

set "CLASS_FILTER="
set "START_EMULATOR="

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--start-emulator" (
  set "START_EMULATOR=1"
) else (
  set "CLASS_FILTER=%~1"
)
shift
goto parse
:parsed

if not exist "%ADB%" (
  echo [ERROR] adb not found at "%ADB%".
  echo         Set ANDROID_SDK_ROOT or ADB and try again.
  exit /b 1
)

"%ADB%" start-server >nul 2>&1

set "DEVICE="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
  if "%%B"=="device" if not defined DEVICE set "DEVICE=%%A"
)

if not defined DEVICE (
  if defined START_EMULATOR (
    echo [PDFr] No device connected, starting the emulator...
    call "%~dp0start-emulator.bat"
    if errorlevel 1 exit /b 1
  ) else (
    echo [ERROR] No device or emulator is connected.
    echo         Start one with: tools\start-emulator.bat
    echo         Or re-run this script with --start-emulator.
    exit /b 1
  )
)

echo [PDFr] Building app and test APKs...
call "%PDFR_ROOT%\gradlew.bat" :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
if errorlevel 1 (
  echo [ERROR] Build failed.
  exit /b 1
)

set "ABI="
set "ABI_FILE=%TEMP%\pdfr-abi-%RANDOM%.txt"
"%ADB%" shell getprop ro.product.cpu.abi > "%ABI_FILE%" 2>nul
set /p ABI=<"%ABI_FILE%"
del "%ABI_FILE%" >nul 2>&1
if not defined ABI set "ABI=x86_64"

set "APP_APK=%PDFR_ROOT%\build\app\outputs\apk\debug\app-%ABI%-debug.apk"
if not exist "%APP_APK%" (
  echo [ERROR] App APK not found: "%APP_APK%"
  exit /b 1
)

set "TEST_APK="
for %%F in ("%PDFR_ROOT%\build\app\outputs\apk\androidTest\debug\*%ABI%*androidTest.apk") do set "TEST_APK=%%~fF"
if not defined TEST_APK for %%F in ("%PDFR_ROOT%\build\app\outputs\apk\androidTest\debug\*androidTest.apk") do set "TEST_APK=%%~fF"
if not defined TEST_APK (
  echo [ERROR] No instrumented test APK found under build\app\outputs\apk\androidTest\debug.
  exit /b 1
)

echo [PDFr] Installing %ABI% app APK...
"%ADB%" install -r -t "%APP_APK%"
if errorlevel 1 exit /b 1

echo [PDFr] Installing test APK...
"%ADB%" install -r -t "%TEST_APK%"
if errorlevel 1 exit /b 1

set "CLASS_ARG="
if defined CLASS_FILTER set CLASS_ARG=-e class %CLASS_FILTER%

echo [PDFr] Running instrumentation...
set "OUT=%TEMP%\pdfr-instrument-%RANDOM%.txt"
"%ADB%" shell am instrument -w %CLASS_ARG% com.karako.pdfr.test/androidx.test.runner.AndroidJUnitRunner > "%OUT%" 2>&1
set "RC=%ErrorLevel%"
type "%OUT%"

set "FAILED="
rem `adb shell am instrument` returns 0 even when a test fails, so inspect the
rem runner's own report instead of trusting the exit code.
findstr /C:"FAILURES!!!" /C:"INSTRUMENTATION_FAILED" /C:"INSTRUMENTATION_ABORTED" /C:"Process crashed" "%OUT%" >nul && set "FAILED=1"
if not "%RC%"=="0" set "FAILED=1"
del "%OUT%" >nul 2>&1

if defined FAILED (
  echo [FAIL] Instrumented tests failed.
  echo        Re-run with a class filter to narrow it down:
  echo        run-instrumented-tests.bat com.karako.pdfr.MuPdfDocumentInstrumentedTest
  exit /b 1
)

echo [OK] Instrumented tests passed.
exit /b 0

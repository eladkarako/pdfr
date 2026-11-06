@echo off
setlocal
call "%~dp0_env.bat"

rem Usage: run-instrumented-tests.bat [fully.qualified.TestClass]
rem Example: run-instrumented-tests.bat com.karako.pdfr.MuPdfDocumentInstrumentedTest

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

set "APP_APK=%PDFR_ROOT%\app\build\outputs\apk\debug\app-%ABI%-debug.apk"
if not exist "%APP_APK%" (
  echo [ERROR] App APK not found: "%APP_APK%"
  exit /b 1
)

set "TEST_APK="
for %%F in ("%PDFR_ROOT%\app\build\outputs\apk\androidTest\debug\*%ABI%*androidTest.apk") do set "TEST_APK=%%~fF"
if not defined TEST_APK for %%F in ("%PDFR_ROOT%\app\build\outputs\apk\androidTest\debug\*androidTest.apk") do set "TEST_APK=%%~fF"
if not defined TEST_APK (
  echo [ERROR] No instrumented test APK found under app\build\outputs\apk\androidTest\debug.
  exit /b 1
)

echo [PDFr] Installing %ABI% app APK...
"%ADB%" install -r "%APP_APK%"
if errorlevel 1 exit /b 1

echo [PDFr] Installing test APK...
"%ADB%" install -r "%TEST_APK%"
if errorlevel 1 exit /b 1

set "CLASS_FILTER="
if not "%~1"=="" set "CLASS_FILTER=-e class %~1"

echo [PDFr] Running instrumentation...
"%ADB%" shell am instrument -w %CLASS_FILTER% com.karako.pdfr.test/androidx.test.runner.AndroidJUnitRunner
if errorlevel 1 (
  echo [FAIL] Instrumented tests failed.
  echo        Re-run with a class filter to narrow it down:
  echo        run-instrumented-tests.bat com.karako.pdfr.MuPdfDocumentInstrumentedTest
  exit /b 1
)

echo [OK] Instrumented tests passed.
exit /b 0

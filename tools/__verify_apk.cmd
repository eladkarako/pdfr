@echo off
setlocal
chcp 65001 1>nul 2>nul
call "%~dp0_env.bat"
pushd "%PDFR_ROOT%"
if not exist "build\logs" mkdir "build\logs"

set "LOG=build\logs\__verify_apk.log"
set "LANG=en_US.UTF-8"
set "LANGUAGE=en_US"
set "LC_ALL=en_US.UTF-8"
set "LC_COLLATE=en_US.UTF-8"
set "LC_CTYPE=en_US.UTF-8"
set "LC_MESSAGES=en_US.UTF-8"
set "LC_NUMERIC=en_US.UTF-8"
set "LC_TIME=en_US.UTF-8"
set "TZ=UTC"
set "NO_COLOR=1"

set "TARGET=%~1"
if not defined TARGET set "TARGET=build\app\outputs\apk\release\app-arm64-v8a-release.apk"
if not exist "%TARGET%" (
  echo [ERROR] APK not found: "%TARGET%"
  echo         Build it first, or pass the APK path as the first argument.
  popd
  exit /b 1
)

rem The SDK path can be customised with ANDROID_SDK_ROOT/ANDROID_HOME.
set "APKSIGNER="
for /f "delims=" %%D in ('dir /b /ad /o-n "%ANDROID_SDK_ROOT%\build-tools" 2^>nul') do (
  if not defined APKSIGNER if exist "%ANDROID_SDK_ROOT%\build-tools\%%D\apksigner.bat" (
    set "APKSIGNER=%ANDROID_SDK_ROOT%\build-tools\%%D\apksigner.bat"
  )
)

set "JARSIGNER="
where jarsigner.exe >nul 2>&1 && set "JARSIGNER=jarsigner.exe"
if not defined JARSIGNER if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jarsigner.exe" set "JARSIGNER=%JAVA_HOME%\bin\jarsigner.exe"

set "FAILED="
echo.>"%LOG%"

echo [PDFr] Verifying "%TARGET%"...

if defined JARSIGNER (
  echo.>>"%LOG%"
  echo == jarsigner ==>>"%LOG%"
  "%JARSIGNER%" -verify -verbose:all -certs "%TARGET%" >>"%LOG%" 2>&1
  if errorlevel 1 set "FAILED=1"
) else (
  echo [WARN] jarsigner not found; skipping the JAR signature check.
  echo [WARN] jarsigner not found; skipping the JAR signature check.>>"%LOG%"
)

if defined APKSIGNER (
  echo.>>"%LOG%"
  echo == apksigner ==>>"%LOG%"
  "%APKSIGNER%" verify --print-certs --verbose "%TARGET%" >>"%LOG%" 2>&1
  if errorlevel 1 set "FAILED=1"
) else (
  echo [WARN] apksigner.bat not found under "%ANDROID_SDK_ROOT%\build-tools"; skipping the APK signature check.
  echo [WARN] apksigner.bat not found; skipping the APK signature check.>>"%LOG%"
)

type "%LOG%"

if defined FAILED (
  echo.
  echo [FAIL] Signature verification failed. See "%LOG%".
  popd
  exit /b 1
)

echo.
echo [OK] Signature verification passed. Log: "%LOG%".
popd
exit /b 0

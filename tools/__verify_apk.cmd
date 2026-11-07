@echo off
chcp 65001 1>nul 2>nul
pushd "%~dp0.."
if not exist "build\logs" mkdir "build\logs"

set "LANG=en_US.UTF-8"
set "LANGUAGE=en_US"
set "LC_ADDRESS=en_US.UTF-8"
set "LC_ALL=en_US.UTF-8"
set "LC_COLLATE=en_US.UTF-8"
set "LC_CTYPE=en_US.UTF-8"
set "LC_IDENTIFICATION=en_US.UTF-8"
set "LC_MEASUREMENT=en_US.UTF-8"
set "LC_MESSAGES=en_US.UTF-8"
set "LC_MONETARY=en_US.UTF-8"
set "LC_NAME=en_US.UTF-8"
set "LC_NUMERIC=en_US.UTF-8"
set "LC_PAPER=en_US.UTF-8"
set "LC_TELEPHONE=en_US.UTF-8"
set "LC_TIME=en_US.UTF-8"
set "TZ=UTC"

call "taskkill.exe" /F /IM "java.exe"  /T
call "taskkill.exe" /F /IM "javaw.exe" /T
timeout /t 2
call "taskkill.exe" /F /IM "java.exe"  /T
call "taskkill.exe" /F /IM "javaw.exe" /T
timeout /t 2

echo.>"build\logs\__verify_apk.log"

set "NO_COLOR=1"

set "TARGET="
if ["%~1"] neq [""] ( 
  set "TARGET=%~1"
) else ( 
  set "TARGET=build\app\outputs\apk\release\app-arm64-v8a-release.apk"
)

title verify signature (jarsigner)
call "jarsigner.exe" -verify "-verbose:all" -certs "%TARGET%" 2>&1 | "tee.exe" --append "build\logs\__verify_apk.log"

echo.>>"build\logs\__verify_apk.log"
echo.>>"build\logs\__verify_apk.log"

title verify signature (apksigner)
call "%LocalAppData%\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs --verbose "%TARGET%" 2>&1 | "tee.exe" --append "build\logs\__verify_apk.log"

timeout /t 5
popd
exit /b 0
:: for Windows tee - `winget install Microsoft.Coreutils` - https://learn.microsoft.com/en-us/windows/core-utils/overview  - (adds to the PATH C:\Program Files\coreutils\bin) \tee.exe
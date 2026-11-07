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

call ".\gradlew.bat" --stop
timeout /t 5
call "taskkill.exe" /F /IM "java.exe"  /T
call "taskkill.exe" /F /IM "javaw.exe" /T
timeout /t 2

set "NO_COLOR=1"
::---------------------- build log file.
echo.>"build\logs\__build.log"
title gradle clean assembleRelease
call ".\gradlew.bat" clean assembleRelease --console=plain 2>&1 | "tee.exe" --append "build\logs\__build.log"
::call ".\gradlew.bat" clean assembleRelease --info --stacktrace --console=plain 2>&1 | "tee.exe" --append "build\logs\__build.log"

timeout /t 2
call ".\gradlew.bat" --stop
timeout /t 5
call "taskkill.exe" /F /IM "java.exe"  /T
call "taskkill.exe" /F /IM "javaw.exe" /T
popd
exit /b 0
:: for Windows tee - `winget install Microsoft.Coreutils` - https://learn.microsoft.com/en-us/windows/core-utils/overview  - (adds to the PATH C:\Program Files\coreutils\bin) \tee.exe
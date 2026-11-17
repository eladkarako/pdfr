::@echo off
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

set "NO_COLOR=1"

echo.>"build\logs\__keystore_generate.log"
rem Prefer Node on PATH, then the copy make_test_files.cmd downloads into
rem tools\. The script runs from the repository root, so tools\node.exe is not
rem found by a bare name unless tools is also on PATH.
set "NODE="
where node.exe >nul 2>nul && set "NODE=node.exe"
if not defined NODE if exist "%~dp0node.exe" set "NODE=%~dp0node.exe"
if not defined NODE (
  echo [ERROR] Node.js was not found. Install it, or run tools\make_test_files.cmd once to download it.
  pause
  popd
  exit /b 1
)
call "%NODE%" "tools\__keystore_generate.js" 2>&1 | "tee.exe" --append "build\logs\__keystore_generate.log"

pause
popd
exit /b 0
:: for Windows tee - `winget install Microsoft.Coreutils` - https://learn.microsoft.com/en-us/windows/core-utils/overview  - (adds to the PATH C:\Program Files\coreutils\bin) \tee.exe
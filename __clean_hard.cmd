@echo off
chcp 65001 1>nul 2>nul
pushd "%~dp0"

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
timeout /t 2
call "taskkill.exe" /F /IM "javaw.exe" /T
timeout /t 2
call "taskkill.exe" /F /IM "java.exe"  /T
timeout /t 2
call "taskkill.exe" /F /IM "javaw.exe" /T
timeout /t 2

:: for Windows tee - `winget install Microsoft.Coreutils` - https://learn.microsoft.com/en-us/windows/core-utils/overview

set "NO_COLOR=1"

::---------------------- cleanup file.
echo.>"__clean_hard.log"

title gradle cache (local)
pwsh -NoProfile -Command "$PSStyle.OutputRendering='PlainText'; Remove-Item -Recurse -Force '.\.gradle' -Verbose -ErrorAction SilentlyContinue" 2>&1 | "tee.exe" --append "__clean_hard.log"
echo.>>"__clean_hard.log"
echo.>>"__clean_hard.log"

title gradle cache (user profile)
pwsh -NoProfile -Command "$PSStyle.OutputRendering='PlainText'; Remove-Item -Recurse -Force $env:USERPROFILE\.gradle -Verbose -ErrorAction SilentlyContinue" 2>&1 | "tee.exe" --append "__clean_hard.log"
echo.>>"__clean_hard.log"
echo.>>"__clean_hard.log"

title gradle clean
call ".\gradlew.bat" clean --info --max-workers=16 --stacktrace --console=plain 2>&1 | tee --append "__clean_hard.log"

timeout /t 5
popd
exit /b 0
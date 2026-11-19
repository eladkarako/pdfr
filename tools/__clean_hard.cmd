@echo off
chcp 65001 1>nul 2>nul
pushd "%~dp0.."

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


::-----------------------------------------------------------------------------------------------------------------
set "TIMESTAMP="
for /F "usebackq delims=" %%E IN (`powershell.exe -NoProfile -Command "Get-Date -Format 'yyyyMMddHHmmss'"`) do (
  set "TIMESTAMP=%%E"
  goto EXIT_LOOP_TIMESTAMP
)
:EXIT_LOOP_TIMESTAMP
::-----------------------------------------------------------------------------------------------------------------


::---------------------- "remove" old build folder.
ren "build" "build_%TIMESTAMP%" 1>nul 2>nul

::---------------------- place for logs. re-creates the build folder path.
mkdir "build\logs" 1>nul 2>nul

::---------------------- cleanup log file.
echo.>"build\logs\__clean_hard.log"

::---------------------- where did old build folder was renamed to.
echo note: old [build] folder renamed to [build_%TIMESTAMP%] >>"build\logs\__clean_hard.log"

::---------------------- removing .gradle folder(s)
title .gradle folder (local)
pwsh -NoProfile -Command "$PSStyle.OutputRendering='PlainText'; Remove-Item -Recurse -Force '.\.gradle' -Verbose -ErrorAction SilentlyContinue" 2>&1 | "tee.exe" --append "build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"

title .gradle folder (user profile)
pwsh -NoProfile -Command "$PSStyle.OutputRendering='PlainText'; Remove-Item -Recurse -Force $env:USERPROFILE\.gradle -Verbose -ErrorAction SilentlyContinue" 2>&1 | "tee.exe" --append "build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"


::---------------------- removing .kotlin folder(s)
title .\.kotlin
title .\build\app
pwsh -NoProfile -Command "$PSStyle.OutputRendering='PlainText'; Remove-Item -Recurse -Force '.\.kotlin' -Verbose -ErrorAction SilentlyContinue" 2>&1 | "tee.exe" --append "build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"

title .\build\release
pwsh -NoProfile -Command "$PSStyle.OutputRendering='PlainText'; Remove-Item -Recurse -Force '.\build\release' -Verbose -ErrorAction SilentlyContinue" 2>&1 | "tee.exe" --append "build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"

title .\build\reports
pwsh -NoProfile -Command "$PSStyle.OutputRendering='PlainText'; Remove-Item -Recurse -Force '.\build\reports' -Verbose -ErrorAction SilentlyContinue" 2>&1 | "tee.exe" --append "build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"
echo.>>"build\logs\__clean_hard.log"

::title .\build\test-fixtures
::pwsh -NoProfile -Command "$PSStyle.OutputRendering='PlainText'; Remove-Item -Recurse -Force '.\build\test-fixtures' -Verbose -ErrorAction SilentlyContinue" 2>&1 | "tee.exe" --append "build\logs\__clean_hard.log"
::echo.>>"build\logs\__clean_hard.log"
::echo.>>"build\logs\__clean_hard.log"


title gradle clean
call ".\gradlew.bat" clean --console=plain 2>&1 | "tee.exe" --append "build\logs\__clean_hard.log"
::call ".\gradlew.bat" clean --info --stacktrace --console=plain 2>&1 | "tee.exe" --append "build\logs\__clean_hard.log"


timeout /t 2
call ".\gradlew.bat" --stop
timeout /t 5
call "taskkill.exe" /F /IM "java.exe"  /T
call "taskkill.exe" /F /IM "javaw.exe" /T
popd
exit /b 0
:: for Windows tee - `winget install Microsoft.Coreutils` - https://learn.microsoft.com/en-us/windows/core-utils/overview  - (adds to the PATH C:\Program Files\coreutils\bin) \tee.exe
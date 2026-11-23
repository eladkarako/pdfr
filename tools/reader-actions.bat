@echo off
setlocal
call "%~dp0_env.bat"

rem Drives the reader's tap/long-press menus, for example:
rem   reader-actions.bat -Action long-press
rem   reader-actions.bat -Action language -Language ja
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0reader-actions.js" %*
exit /b %ERRORLEVEL%

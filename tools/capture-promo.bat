@echo off
setlocal
rem Build first, then drive the emulator, exactly like the test runner.
rem Usage: capture-promo.bat [-StartEmulator] [-SkipBuild] [-SkipVideo]
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0capture-promo.js" %*
exit /b %ErrorLevel%

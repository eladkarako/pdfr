@echo off
setlocal
rem Draws the 320x180 Android TV banner.
rem Usage: make-tv-banner.bat [-OutFile path]
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0make-tv-banner.js" %*
exit /b %ErrorLevel%

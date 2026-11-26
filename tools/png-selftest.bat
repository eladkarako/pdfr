@echo off
setlocal
rem Runs the lib/png.js round-trip and malformed-input checks.
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0png-selftest.js" %*
exit /b %ErrorLevel%

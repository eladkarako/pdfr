@echo off
setlocal
call "%~dp0_env.bat"

rem Generates the test documents and copies them to the device's shared
rem Downloads folder. The Node script verifies every pushed file and asks
rem MediaStore to index it; see push-fixtures.js for the options.
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0push-fixtures.js" %*
exit /b %ERRORLEVEL%

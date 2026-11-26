@echo off
setlocal
call "%~dp0_env.bat"

rem Usage: open-fixture.bat <file-name-or-path> [mime-type]
rem Example: open-fixture.bat sample.pdf
rem          open-fixture.bat C:\docs\paper.pdf
rem
rem The file is copied to shared storage (/sdcard/Download/pdfr-fixtures) and
rem verified before it is opened, so nothing is left only in the app's private
rem cache. See open-fixture.js for -Dest, -Mime and -Method.
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0open-fixture.js" %*
exit /b %ERRORLEVEL%

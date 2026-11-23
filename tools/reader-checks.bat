@echo off
setlocal
call "%~dp0_env.bat"

rem Runs the automated reader UI checks and returns their exit code, so this
rem can be used from a CI script as well as from a command prompt.
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0reader-checks.js" %*
exit /b %ERRORLEVEL%

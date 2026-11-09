@echo off
setlocal
call "%~dp0_env.bat"

rem Runs the automated reader UI checks and returns their exit code, so this
rem can be used from a CI script as well as from a command prompt.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0reader-checks.ps1" %*
exit /b %ERRORLEVEL%

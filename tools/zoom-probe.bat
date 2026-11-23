@echo off
setlocal
call "%~dp0_env.bat"

rem Records pinch/pan screenshots and compares late vs settled, for example:
rem   zoom-probe.bat -Action pinch-out
rem   zoom-probe.bat -Action pan -StartY 1700 -EndY 900
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0zoom-probe.js" %*
exit /b %ERRORLEVEL%

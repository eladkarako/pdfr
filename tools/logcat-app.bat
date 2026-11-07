@echo off
setlocal
call "%~dp0_env.bat"

set "PID="
set "PID_FILE=%TEMP%\pdfr-pid-%RANDOM%.txt"
"%ADB%" shell pidof com.karako.pdfr > "%PID_FILE%" 2>nul
set /p PID=<"%PID_FILE%"
del "%PID_FILE%" >nul 2>&1

if defined PID (
  echo [PDFr] Last log lines for PID %PID%:
  "%ADB%" logcat -d --pid=%PID% -v time -t 400
) else (
  echo [PDFr] com.karako.pdfr is not running. Showing recent crashes:
  "%ADB%" logcat -d -v time -t 400 AndroidRuntime:E *:S
)
exit /b 0

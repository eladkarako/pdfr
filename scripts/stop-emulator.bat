@echo off
setlocal
call "%~dp0_env.bat"

echo [PDFr] Stopping the running emulator...
"%ADB%" emu kill
if errorlevel 1 (
  echo [HINT] Could not stop an emulator. Is one running, and is adb available?
  exit /b 1
)
echo [PDFr] Emulator stopped.
exit /b 0

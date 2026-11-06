@echo off
setlocal
call "%~dp0_env.bat"

:menu
cls
echo ============================================================
echo  PDFr manual checks
echo ============================================================
echo   Device: %PDFR_AVD%     Fixtures: %PDFR_FIXTURES%
echo.
echo   1. Push generated fixtures to the emulator
echo   2. Open sample.pdf (14 pages)
echo   3. Open dummy-encrypted.pdf (password: secret)
echo   4. Open notes.txt (monospace toggle)
echo   5. Open alice.epub (reflow)
echo   6. Double-tap zoom (2x, 4x, 6x, 1x)
echo   7. Pinch out / pinch in
echo   8. Long-press and select text
echo   9. Show PDFr cache documents
echo  10. Show recent app logs / crashes
echo   0. Exit
echo.
set "ACTION="
set /p "ACTION=Choose an action: "

if "%ACTION%"=="10" goto logs
if "%ACTION%"=="9" goto cache
if "%ACTION%"=="8" goto select
if "%ACTION%"=="7" goto pinch
if "%ACTION%"=="6" goto doubletap
if "%ACTION%"=="5" goto epub
if "%ACTION%"=="4" goto text
if "%ACTION%"=="3" goto encrypted
if "%ACTION%"=="2" goto sample
if "%ACTION%"=="1" goto push
if "%ACTION%"=="0" exit /b 0
goto menu

:push
call "%~dp0push-fixtures.bat"
goto pause

:sample
call "%~dp0open-fixture.bat" sample.pdf
goto pause

:encrypted
call "%~dp0open-fixture.bat" dummy-encrypted.pdf
echo  Password: secret
goto pause

:text
call "%~dp0open-fixture.bat" notes.txt
goto pause

:epub
call "%~dp0open-fixture.bat" alice.epub
goto pause

:doubletap
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gestures.ps1" double-tap
goto pause

:pinch
choice /c 12 /n /m "1 = pinch out (zoom in), 2 = pinch in (zoom out): "
if errorlevel 2 (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gestures.ps1" pinch-in
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gestures.ps1" pinch-out
)
goto pause

:select
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gestures.ps1" select-word
goto pause

:cache
call "%~dp0show-cache.bat"
goto pause

:logs
call "%~dp0logcat-app.bat"
goto pause

:pause
echo.
pause
goto menu

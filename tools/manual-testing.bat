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
echo   2. Open sample.pdf (14 pages, reorder/rotate/normalize)
echo   3. Open dummy-encrypted.pdf (password: secret)
echo   4. Open text: notes.txt, README.nfo or book.text
echo   5. Open an EPUB, HTML or Gutenberg sample
echo   6. Double-tap zoom (2x, 4x, 6x, 1x)
echo   7. Pinch out / pinch in
echo   8. Long-press and select text
echo   9. Show PDFr cache documents
echo  10. Show recent app logs / crashes
echo  11. Record a zoom probe (pinch/pan screenshots)
echo  12. Check the overflow-menu toolbar mirror
echo  13. Capture promo screenshots + MP4
echo  14. Compose the promo poster
echo  15. Open Settings (theme + language)
echo  16. Switch the UI to Hebrew (RTL)
echo  17. Return the UI to the system language
echo  18. Regenerate the Android TV banner
echo  19. Switch the UI to another language (enter a tag)
echo   0. Exit
echo.
set "ACTION="
set /p "ACTION=Choose an action: "

if "%ACTION%"=="19" goto language-other
if "%ACTION%"=="18" goto banner
if "%ACTION%"=="17" goto language-system
if "%ACTION%"=="16" goto language-hebrew
if "%ACTION%"=="15" goto settings
if "%ACTION%"=="14" goto poster
if "%ACTION%"=="13" goto promo
if "%ACTION%"=="12" goto menu-mirror
if "%ACTION%"=="11" goto zoomprobe
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
choice /c 123 /n /m "1 = notes.txt, 2 = README.nfo, 3 = book.text: "
if errorlevel 3 (
  call "%~dp0open-fixture.bat" book.text
) else if errorlevel 2 (
  call "%~dp0open-fixture.bat" README.nfo
) else (
  call "%~dp0open-fixture.bat" notes.txt
)
goto pause

:epub
choice /c 12345 /n /m "1 = alice.epub, 2 = Alice (Gutenberg), 3 = Pride and Prejudice, 4 = Sherlock Holmes (TXT), 5 = Yellow Wallpaper (HTML): "
if errorlevel 5 (
  call "%~dp0open-fixture.bat" "gutenberg\yellow-wallpaper.html"
) else if errorlevel 4 (
  call "%~dp0open-fixture.bat" "gutenberg\sherlock-holmes.txt"
) else if errorlevel 3 (
  call "%~dp0open-fixture.bat" "gutenberg\pride-and-prejudice.epub"
) else if errorlevel 2 (
  call "%~dp0open-fixture.bat" "gutenberg\alice-gutenberg.epub"
) else (
  call "%~dp0open-fixture.bat" alice.epub
)
goto pause

:doubletap
node "%~dp0gestures.js" double-tap
goto pause

:zoomprobe
choice /c 1234 /n /m "1 = pinch out, 2 = pinch in, 3 = two-finger pan, 4 = double-tap: "
if errorlevel 4 (
  node "%~dp0zoom-probe.js" -Action double-tap
) else if errorlevel 3 (
  node "%~dp0zoom-probe.js" -Action pan
) else if errorlevel 2 (
  node "%~dp0zoom-probe.js" -Action pinch-in
) else (
  node "%~dp0zoom-probe.js" -Action pinch-out
)
goto pause

:pinch
choice /c 12 /n /m "1 = pinch out (zoom in), 2 = pinch in (zoom out): "
if errorlevel 2 (
  node "%~dp0gestures.js" pinch-in
) else (
  node "%~dp0gestures.js" pinch-out
)
goto pause

:select
node "%~dp0gestures.js" select-word
goto pause

:cache
call "%~dp0show-cache.bat"
goto pause

:logs
call "%~dp0logcat-app.bat"
goto pause

:menu-mirror
node "%~dp0reader-actions.js" -Action menu-mirror
goto pause

:promo
call "%~dp0capture-promo.bat"
goto pause

:poster
node "%~dp0make-poster.js"
goto pause

:settings
node "%~dp0reader-actions.js" -Action settings
goto pause

:language-hebrew
node "%~dp0reader-actions.js" -Action language-hebrew
goto pause

:language-system
node "%~dp0reader-actions.js" -Action language-system
goto pause

:language-other
echo Tags: system, en, en-GB, he, ar, fr, fr-CA, de, es, es-419,
echo       pt, pt-BR, ru, ja, zh-CN, zh-TW, ko, hi
set "LANGTAG="
set /p "LANGTAG=Language tag: "
if not defined LANGTAG goto menu
node "%~dp0reader-actions.js" -Action language -Language "%LANGTAG%"
goto pause

:banner
node "%~dp0make-tv-banner.js"
goto pause

:pause
echo.
pause
goto menu

@echo off
setlocal
call "%~dp0_env.bat"

echo [PDFr] Generating fixtures in "%PDFR_FIXTURES%"...
call "%PDFR_ROOT%\tools\make_test_files.cmd" --out "%PDFR_FIXTURES%"
if errorlevel 1 (
  echo [ERROR] Fixture generation failed. Is Node.js available?
  exit /b 1
)

echo [PDFr] Pushing fixtures to /sdcard/Download/pdfr-fixtures...
"%ADB%" shell mkdir -p /sdcard/Download/pdfr-fixtures
"%ADB%" push "%PDFR_FIXTURES%\." /sdcard/Download/pdfr-fixtures
if errorlevel 1 (
  echo [ERROR] adb push failed.
  exit /b 1
)

echo [PDFr] Asking MediaStore to index the new files...
for %%F in ("%PDFR_FIXTURES%\*") do (
  echo   - %%~nxF
  "%ADB%" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/pdfr-fixtures/%%~nxF" >nul 2>&1
)
if exist "%PDFR_FIXTURES%\gutenberg" (
  for %%F in ("%PDFR_FIXTURES%\gutenberg\*") do (
    echo   - gutenberg\%%~nxF
    "%ADB%" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/pdfr-fixtures/gutenberg/%%~nxF" >nul 2>&1
  )
)

echo.
echo [PDFr] Fixtures are ready.
echo        Open one from inside PDFr, or run: open-fixture.bat sample.pdf
echo        Encrypted PDF password: secret
exit /b 0

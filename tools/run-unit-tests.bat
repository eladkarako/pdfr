@echo off
setlocal
call "%~dp0_env.bat"

echo [PDFr] Running local JVM unit tests...
call "%PDFR_ROOT%\gradlew.bat" :app:testDebugUnitTest --console=plain
if errorlevel 1 (
  echo [FAIL] Unit tests failed.
  echo        Report: %PDFR_ROOT%\build\app\reports\tests\testDebugUnitTest\index.html
  exit /b 1
)

echo [OK] Unit tests passed.
echo      Report: %PDFR_ROOT%\build\app\reports\tests\testDebugUnitTest\index.html
exit /b 0

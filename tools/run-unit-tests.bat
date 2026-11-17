@echo off
setlocal
call "%~dp0_env.bat"

rem Usage: run-unit-tests.bat [fully.qualified.TestClass[#method]]
rem Example: run-unit-tests.bat com.karako.pdfr.data.PageRangesTest

set "FILTER="
if not "%~1"=="" set FILTER=--tests "%~1"

echo [PDFr] Running local JVM unit tests...
rem Gradle resolves the project from the current directory, so enter the
rem repository first. Without this the wrapper would look for a build under
rem tools\ when the script is started there.
pushd "%PDFR_ROOT%"
call ".\gradlew.bat" :app:testDebugUnitTest %FILTER% --console=plain
set "RC=%ErrorLevel%"
popd
if not "%RC%"=="0" (
  echo [FAIL] Unit tests failed.
  echo        Report: %PDFR_ROOT%\build\app\reports\tests\testDebugUnitTest\index.html
  exit /b 1
)

echo [OK] Unit tests passed.
echo      Report: %PDFR_ROOT%\build\app\reports\tests\testDebugUnitTest\index.html
exit /b 0

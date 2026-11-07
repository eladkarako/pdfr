@echo off
setlocal
call "%~dp0_env.bat"

rem Usage: open-fixture.bat <file-name-or-path> [mime-type]
rem Example: open-fixture.bat sample.pdf
rem          open-fixture.bat C:\docs\paper.pdf
rem
rem Preferred route: install the debug build, copy the fixture into PDFr's
rem own cache through `run-as`, then hand the app its FileProvider URI. That
rem needs no storage permission and works on Android 13+ where `am start`
rem cannot grant another app access to a MediaStore URI.
rem
rem Fallback: if the app is not debuggable (a release build), look the file up
rem in MediaStore and send ACTION_VIEW. Some Android builds reject that grant;
rem in that case open the folder icon inside PDFr and pick the file manually.

if "%~1"=="" (
  echo Usage: open-fixture.bat ^<file-name^> [mime-type]
  echo Example: open-fixture.bat sample.pdf
  exit /b 1
)

set "NAME=%~1"
set "MIME=%~2"
if not defined MIME call :mime_for "%NAME%"

for %%F in ("%NAME%") do set "BASE=%%~nxF"
if not defined BASE set "BASE=%NAME%"

set "SOURCE=%~1"
if exist "%SOURCE%" goto have_source
if exist "%PDFR_FIXTURES%\%NAME%" (
  set "SOURCE=%PDFR_FIXTURES%\%NAME%"
  goto have_source
)

echo [PDFr] "%NAME%" was not found under "%PDFR_FIXTURES%".
echo         Trying MediaStore instead.
goto mediastore

:have_source
set "INSTALLED=%TEMP%\pdfr-installed-%RANDOM%.txt"
"%ADB%" shell pm path com.karako.pdfr > "%INSTALLED%" 2>nul
set "HAS_APP="
set /p HAS_APP=<"%INSTALLED%"
del "%INSTALLED%" >nul 2>&1
if not defined HAS_APP (
  echo [ERROR] com.karako.pdfr is not installed. Run install-debug.bat first.
  exit /b 1
)

rem The app ignores FileProvider URIs while a document is already open (that
rem guard stops "share back to PDFr" from re-importing the same file), and it
rem deletes stale cache documents while it starts. Restart it, wait for that
rem cleanup, then stage the fixture and deliver the URI.
echo [PDFr] Restarting the app...
"%ADB%" shell am force-stop com.karako.pdfr
"%ADB%" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.karako.pdfr/.MainActivity >nul
rem A cold start takes a few seconds on the emulator.
ping -n 7 127.0.0.1 >nul

echo [PDFr] Staging "%SOURCE%" inside the app...
"%ADB%" shell run-as com.karako.pdfr mkdir -p cache/documents >nul 2>&1
"%ADB%" exec-in run-as com.karako.pdfr tee "cache/documents/%BASE%" < "%SOURCE%" >nul 2>&1
if errorlevel 1 (
  echo [HINT] Could not write inside the app. Is the debug build installed?
  goto mediastore
)

echo [PDFr] Opening %BASE% (%MIME%)...
"%ADB%" shell am start -a android.intent.action.VIEW -d "content://com.karako.pdfr.fileprovider/shared_documents/%BASE%" -t "%MIME%"
if errorlevel 1 (
  echo [HINT] If PDFr shows "no access", open the folder icon and pick the file manually.
  exit /b 1
)
exit /b 0

:mediastore
set "ROW="
set "LIST=%TEMP%\pdfr-mediastore-%RANDOM%.txt"
"%ADB%" shell content query --uri content://media/external/file --projection _id:_display_name > "%LIST%" 2>nul
for /f "tokens=3 delims=, " %%A in ('findstr /i /c:"_display_name=%BASE%" "%LIST%"') do set "ROW=%%A"
del "%LIST%" >nul 2>&1

if not defined ROW (
  echo [ERROR] "%BASE%" is not in MediaStore either.
  echo         Run push-fixtures.bat, or pass a path to an existing file.
  exit /b 1
)

rem ROW looks like "_id=1000000104"; drop the prefix and the leading "=".
set "ID=%ROW:_id=%"
set "ID=%ID:~1%"
echo [PDFr] Opening %BASE% through MediaStore (id %ID%, %MIME%)...
"%ADB%" shell am start -a android.intent.action.VIEW -d "content://media/external/file/%ID%" -t "%MIME%" --grant-read-uri-permission
if errorlevel 1 (
  echo [HINT] Android refused the shell URI grant. Open the folder icon inside
  echo        PDFr and pick the file from Download/pdfr-fixtures instead.
  exit /b 1
)
exit /b 0

:mime_for
set "MIME=application/octet-stream"
if /i "%~x1"==".pdf" set "MIME=application/pdf"
if /i "%~x1"==".epub" set "MIME=application/epub+zip"
if /i "%~x1"==".xps" set "MIME=application/vnd.ms-xpsdocument"
if /i "%~x1"==".oxps" set "MIME=application/oxps"
if /i "%~x1"==".cbz" set "MIME=application/vnd.comicbook+zip"
if /i "%~x1"==".txt" set "MIME=text/plain"
if /i "%~x1"==".nfo" set "MIME=text/plain"
if /i "%~x1"==".text" set "MIME=text/plain"
if /i "%~x1"==".html" set "MIME=text/html"
if /i "%~x1"==".htm" set "MIME=text/html"
if /i "%~x1"==".xhtml" set "MIME=application/xhtml+xml"
if /i "%~x1"==".fb2" set "MIME=application/x-fictionbook+xml"
if /i "%~x1"==".svg" set "MIME=image/svg+xml"
exit /b 0

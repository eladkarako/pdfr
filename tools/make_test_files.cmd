::@echo off
chcp 65001 1>nul 2>nul
pushd "%~sdp0"

goto :MAIN

::-------------------------------------------------------------------------------------------------------------
:DOWNLOAD_QPDF
  set "URL=https://github.com/qpdf/qpdf/releases/download/v12.4.2/qpdf-12.4.2-msvc64.zip"
  call "curl.exe" --location-trusted --anyauth --output-dir "%CD%" --output "qpdf-msvc64.zip" --url "%URL%"
  set "EXIT_CODE=%ErrorLevel%"
  if ["%EXIT_CODE%"] neq ["0"] (
    echo [ERROR] curl failed for downloading qpdf, with exit code: %EXIT_CODE%. 1>&2
    exit /b %EXIT_CODE%
  )

  call "7z.exe" e -bb3 -bt -o"%CD%" -w"%CD%" -scc"UTF-8" -scs"UTF-8" -y -r "qpdf-msvc64.zip" "*.dll" "*.exe"
  set "EXIT_CODE=%ErrorLevel%"
  if ["%EXIT_CODE%"] neq ["0"] (
    echo [ERROR] 7z failed with exit code: %EXIT_CODE%. 1>&2
    exit /b %EXIT_CODE%
  )

  del /f /q "qpdf-msvc64.zip"
  goto :eof
::-------------------------------------------------------------------------------------------------------------

::-------------------------------------------------------------------------------------------------------------
:DOWNLOAD_NODE
  set "URL=https://nodejs.org/download/nightly/v27.0.0-nightly20261027640ce854c5/win-x64/node.exe"
  call "curl.exe" --location-trusted --anyauth --output-dir "%CD%" --output "node.exe" --url "%URL%"
  set "EXIT_CODE=%ErrorLevel%"
  if ["%EXIT_CODE%"] neq ["0"] (
    echo [ERROR] curl failed for downloading node, with exit code: %EXIT_CODE%. 1>&2
    exit /b %EXIT_CODE%
  )
::-------------------------------------------------------------------------------------------------------------

::-------------------------------------------------------------------------------------------------------------
:MAIN
where "node.exe" 1>nul 2>nul
set "EXIT_CODE=%ErrorLevel%"
if ["%EXIT_CODE%"] neq ["0"] ( call :DOWNLOAD_NODE )

where "qpdf.exe" 1>nul 2>nul
set "EXIT_CODE=%ErrorLevel%"
if ["%EXIT_CODE%"] neq ["0"] ( call :DOWNLOAD_QPDF )


call "node.exe" "make_test_files.js" %*
set "EXIT_CODE=%ErrorLevel%"

echo [INFO] node ended with exit-code: %EXIT_CODE%.

rem Keep the window open only for a double-click / plain run.
if "%~1"=="" timeout /t 10
exit /b %EXIT_CODE%
::-------------------------------------------------------------------------------------------------------------



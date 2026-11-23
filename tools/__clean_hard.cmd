@echo off
chcp 65001 1>nul 2>nul
pushd "%~dp0.."

where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  popd
  exit /b 1
)

node "%~dp0__clean_hard.js"
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%

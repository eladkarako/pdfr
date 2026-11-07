@echo off
setlocal

rem Usage: create-test-avd.bat [avd-name] [ram-mb]
rem Defaults: pdfr_full, 1536 MB.

if "%~1"=="" (set "AVD_NAME=pdfr_full") else (set "AVD_NAME=%~1")
if "%~2"=="" (set "AVD_RAM=1536") else (set "AVD_RAM=%~2")

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0create-test-avd.ps1" -Name "%AVD_NAME%" -RamMb %AVD_RAM%
exit /b %ErrorLevel%

@echo off
setlocal

rem Usage: create-tv-avd.bat [avd-name] [ram-mb]
rem Defaults: pdfr_tv, 2048 MB. The first run downloads the TV system image.

if "%~1"=="" (set "AVD_NAME=pdfr_tv") else (set "AVD_NAME=%~1")
if "%~2"=="" (set "AVD_RAM=2048") else (set "AVD_RAM=%~2")

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0create-tv-avd.ps1" -Name "%AVD_NAME%" -RamMb %AVD_RAM%
exit /b %ErrorLevel%

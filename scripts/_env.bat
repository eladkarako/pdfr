@echo off
rem Shared environment for the PDFr helper scripts.
rem Override anything below by setting the variable before calling a script.
rem This file is called with `call`; it must never end the caller's window.

set "PDFR_ROOT=%~dp0.."
for %%I in ("%PDFR_ROOT%") do set "PDFR_ROOT=%%~fI"

if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined ANDROID_SDK_ROOT if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"

if not defined ADB set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined EMULATOR set "EMULATOR=%ANDROID_SDK_ROOT%\emulator\emulator.exe"
if not defined PDFR_AVD set "PDFR_AVD=leaf_full"
if not defined PDFR_FIXTURES set "PDFR_FIXTURES=%PDFR_ROOT%\build\test-fixtures"
if not defined PYTHON set "PYTHON=python"

exit /b 0

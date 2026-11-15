@echo off
setlocal
rem Build first, then drive the emulator, exactly like the test runner.
rem Usage: capture-promo.bat [-StartEmulator] [-SkipBuild] [-SkipVideo]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0capture-promo.ps1" %*
exit /b %ErrorLevel%

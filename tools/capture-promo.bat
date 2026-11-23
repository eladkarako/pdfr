@echo off
setlocal
rem Build first, then drive the emulator, exactly like the test runner.
rem Usage: capture-promo.bat [-StartEmulator] [-SkipBuild] [-SkipVideo]
node "%~dp0capture-promo.js" %*
exit /b %ErrorLevel%

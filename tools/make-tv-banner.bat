@echo off
setlocal
rem Draws the 320x180 Android TV banner.
rem Usage: make-tv-banner.bat [-OutFile path]
node "%~dp0make-tv-banner.js" %*
exit /b %ErrorLevel%

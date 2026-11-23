@echo off
setlocal
rem Composes the promo screenshots into poster.png.
rem Usage: make-poster.bat [-OutDir build\promo] [-Images a.png,b.png] [-Columns 2] [-Width 1600]
node "%~dp0make-poster.js" %*
exit /b %ErrorLevel%

@echo off
setlocal
rem Draws the promo screenshots into a poster.
rem Usage: make-poster.bat [-OutDir build\promo] [-Images a.png,b.png] [-Columns 2] [-Width 1600]
where node >nul 2>nul
if errorlevel 1 (
  echo [PDFr] Node.js was not found on PATH.
  exit /b 1
)

node "%~dp0make-poster.js" %*
exit /b %ErrorLevel%

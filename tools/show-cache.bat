@echo off
setlocal
call "%~dp0_env.bat"

echo [PDFr] Cache documents on the device:
"%ADB%" shell run-as com.karako.pdfr ls -lR cache/documents
exit /b 0

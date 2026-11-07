@echo off
chcp 65001 1>nul 2>nul
pushd "%~dp0.."

del /f /q "build\logs\*.log" 1>nul 2>nul
rem Also remove logs left over from the old layout.
del /f /q *.log 1>nul 2>nul

popd
exit /b 0

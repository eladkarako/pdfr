@echo off
chcp 65001 1>nul 2>nul
pushd "%~dp0"

del /f /q *.log 1>nul 2>nul

popd
exit /b 0

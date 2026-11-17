@echo off
cd /d "%~dp0"
where py >nul 2>nul
if errorlevel 1 (
 echo Python launcher not found. Install Python 3, then run: python ai-server\server.py
 pause
 exit /b 1
)
py -3 ai-server\server.py
pause

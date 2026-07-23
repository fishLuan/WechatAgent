@echo off
echo ========================================
echo    WeChat iLink Bot - DeepSeek Edition
echo ========================================
echo.
cd /d "%~dp0"
call mvnw.cmd exec:java
echo.
echo Bot stopped.
pause
@echo off

:: go to project root
cd /d %~dp0
cd ..

echo Stopping Auton8 stack...

docker compose down

pause

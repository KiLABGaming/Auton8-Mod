@echo off

:: go to project root
cd /d %~dp0
cd ..

echo Starting Auton8 stack...

docker compose up

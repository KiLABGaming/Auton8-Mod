@echo off

:: go to project root
cd /d %~dp0
cd ..

echo Starting Auton8 stack...

:: starting containers in background
docker compose up -d

echo.
echo Auton8 services are now running.
echo - Mosquitto on 127.0.0.1:1883
echo - n8n editor on http://localhost:5678
echo - Read live logs with: docker compose logs -f

pause

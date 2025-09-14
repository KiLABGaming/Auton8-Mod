@echo off

:: go to project root
cd /d %~dp0
cd ..

gradlew.bat --console plain --no-daemon --full-stacktrace check build

pause

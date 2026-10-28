@echo off
rem Use this only if "Workshop Manager.exe" will not open.
cd /d "%~dp0"
start "" "runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 -jar "app\WorkshopLauncher.jar"

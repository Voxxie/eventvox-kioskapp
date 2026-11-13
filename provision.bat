@echo off
REM Richt ALLE via USB aangesloten toestellen in als WebKiosk.
REM Vereist adb (Android platform-tools) in PATH of in deze map.
cd /d "%~dp0"
if not exist webkiosk.apk (
  echo webkiosk.apk ontbreekt: download hem bij de nieuwste release op GitHub.
  pause
  exit /b 1
)
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
  if "%%b"=="device" call :setup %%a
)
echo Klaar.
pause
goto :eof

:setup
echo === %1 ===
adb -s %1 install -r webkiosk.apk
adb -s %1 push webkiosk.properties /sdcard/webkiosk.properties
adb -s %1 shell dpm set-device-owner nl.webkiosk/.AdminReceiver || echo !! Device owner mislukt op %1: verwijder eerst alle accounts of doe een fabrieksreset.
adb -s %1 shell am start -n nl.webkiosk/.MainActivity
findstr /R /C:"^wifi\.[0-9]*\.password=." webkiosk.properties >nul && (
  REM wifi-wachtwoorden niet op de opslag laten staan: de app heeft ze dan al overgenomen
  timeout /t 10 /nobreak >nul
  adb -s %1 shell rm -f /sdcard/webkiosk.properties
  echo    config met wifi-wachtwoorden weer van het toestel verwijderd
)
goto :eof

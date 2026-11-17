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
REM Play Protect controleert installaties via USB en blokkeert een app die niet uit de Play Store komt: alleen tijdens
REM het installeren uit (verifier_verify_adb_installs), daarna de oude stand terug. Play Protect zelf blijft aan.
set "OLDVERIFY="
for /f "delims=" %%v in ('adb -s %1 shell settings get global verifier_verify_adb_installs') do set "OLDVERIFY=%%v"
adb -s %1 shell settings put global verifier_verify_adb_installs 0
adb -s %1 install -r webkiosk.apk || (
  echo !! Installeren mislukt op %1. Staat Play Protect een melding te tonen op het toestel? Kies dan 'Toch installeren',
  echo    of zet in de Play Store ^> Play Protect ^> instellingen 'Apps scannen' tijdelijk uit en start dit script opnieuw.
)
if "%OLDVERIFY%"=="" (adb -s %1 shell settings delete global verifier_verify_adb_installs >nul) else if "%OLDVERIFY%"=="null" (adb -s %1 shell settings delete global verifier_verify_adb_installs >nul) else (adb -s %1 shell settings put global verifier_verify_adb_installs %OLDVERIFY%)
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

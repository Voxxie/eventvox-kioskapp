#!/usr/bin/env bash
# Richt ALLE via USB aangesloten toestellen in als WebKiosk.
# Gebruik: ./provision.sh            (gebruikt webkiosk.properties)
#          ./provision.sh https://mijn.site/pagina   (overschrijft url)
set -u
cd "$(dirname "$0")"
URL="${1:-}"
CFG=webkiosk.properties
if [ -n "$URL" ]; then
  sed "s|^url=.*|url=$URL|" webkiosk.properties > /tmp/webkiosk.properties.$$ && CFG=/tmp/webkiosk.properties.$$
fi
[ -f webkiosk.apk ] || { echo "webkiosk.apk ontbreekt: download hem bij de nieuwste release op GitHub."; exit 1; }
DEVICES=$(adb devices | awk 'NR>1 && $2=="device"{print $1}')
[ -z "$DEVICES" ] && { echo "Geen toestellen gevonden (USB-foutopsporing aan?)"; exit 1; }
for S in $DEVICES; do
  echo "=== $S ==="
  adb -s "$S" install -r webkiosk.apk
  adb -s "$S" push "$CFG" /sdcard/webkiosk.properties
  adb -s "$S" shell dpm set-device-owner nl.webkiosk/.AdminReceiver \
    || echo "!! Device owner mislukt op $S: verwijder eerst alle accounts (Instellingen > Accounts) of doe een fabrieksreset."
  adb -s "$S" shell am start -n nl.webkiosk/.MainActivity
  if grep -qE '^wifi\.[0-9]+\.password=.' "$CFG"; then
    # wifi-wachtwoorden niet op de opslag laten staan: de app heeft ze dan al overgenomen
    sleep 10
    adb -s "$S" shell rm -f /sdcard/webkiosk.properties
    echo "   config met wifi-wachtwoorden weer van het toestel verwijderd"
  fi
done
echo "Klaar."

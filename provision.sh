#!/usr/bin/env bash
# Richt ALLE via USB aangesloten toestellen in als WebKiosk.
# Gebruik: ./provision.sh            (gebruikt webkiosk.properties)
#          ./provision.sh https://mijn.site/pagina   (overschrijft url)
set -u
cd "$(dirname "$0")" || exit 1
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
  # Play Protect controleert installaties via USB en blokkeert een app die niet uit de Play Store komt: alleen tijdens het
  # installeren uit (verifier_verify_adb_installs), daarna de oude stand terug. Play Protect zelf blijft aan.
  OLD_VERIFY="$(adb -s "$S" shell settings get global verifier_verify_adb_installs | tr -d '\r')"
  adb -s "$S" shell settings put global verifier_verify_adb_installs 0
  if ! adb -s "$S" install -r webkiosk.apk; then
    echo "!! Installeren mislukt op $S. Staat Play Protect een melding te tonen op het toestel? Kies dan 'Toch installeren',"
    echo "   of zet in de Play Store > Play Protect > instellingen 'Apps scannen' tijdelijk uit en start dit script opnieuw."
  fi
  if [ -z "$OLD_VERIFY" ] || [ "$OLD_VERIFY" = null ]; then
    adb -s "$S" shell settings delete global verifier_verify_adb_installs >/dev/null
  else
    adb -s "$S" shell settings put global verifier_verify_adb_installs "$OLD_VERIFY"
  fi
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

#!/usr/bin/env bash
# Alle controles die zonder toestel kunnen (lokaal en in CI, .github/workflows/check.yml):
# 1. de APK bouwen (met een tijdelijke sleutel) en zijn pakketnaam, versie en toestemmingen controleren;
# 2. Java zonder Android (WifiQrTest);
# 3. het beheer- en netwerkscherm in Chromium (tests/assets.test.mjs).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar

echo "== APK bouwen"
keytool -genkeypair -keystore "$TMP/test.keystore" -storepass testtest -keypass testtest -alias webkiosk \
    -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Test" >/dev/null 2>&1
KEYSTORE="$TMP/test.keystore" KEYSTORE_PASSWORD=testtest REQUIRE_KEYSTORE=1 OUTPUT="$TMP/test.apk" "$ROOT/build.sh" >/dev/null
BADGING="$(aapt dump badging "$TMP/test.apk")"
check() { grep -q "$1" <<<"$BADGING" || { echo "FOUT: $2"; exit 1; }; echo "ok   $2"; }
check "package: name='nl.webkiosk'" "pakketnaam nl.webkiosk (nooit wijzigen)"
check "versionName='0.0.0-dev'" "lokale build is 0.0.0-dev"
check "sdkVersion:'21'" "minSdkVersion 21 (Android 8.1 blijft ondersteund)"
for perm in INTERNET NFC VIBRATE ACCESS_WIFI_STATE CHANGE_WIFI_STATE ACCESS_FINE_LOCATION RECEIVE_BOOT_COMPLETED; do
    check "android.permission.$perm'" "toestemming $perm"
done
FILES="$(unzip -l "$TMP/test.apk")"
for a in admin.html admin.js admin.css wifi.html wifi.js; do
    grep -q "assets/$a" <<<"$FILES" || { echo "FOUT: assets/$a ontbreekt in de APK"; exit 1; }
done
echo "ok   assets in de APK"
# Klassen uit app/stubs/ staan alleen op het classpath; in de APK zouden ze botsen met Android zelf
unzip -p "$TMP/test.apk" classes.dex > "$TMP/classes.dex"
CLASSES="$(python3 "$ROOT/tests/dexclasses.py" "$TMP/classes.dex")"
grep -q "^Lnl/webkiosk/MainActivity;" <<<"$CLASSES" || { echo "FOUT: MainActivity niet in classes.dex"; exit 1; }
if grep -v "^Lnl/webkiosk/" <<<"$CLASSES"; then echo "FOUT: klassen buiten nl.webkiosk in de APK (stub?)"; exit 1; fi
echo "ok   geen stubs in de APK"

echo "== Java zonder Android"
javac -nowarn -cp "$SDK" -d "$TMP/java" "$ROOT/app/src/nl/webkiosk/WifiProfiles.java" "$ROOT/tests/WifiQrTest.java"
java -cp "$TMP/java:$SDK" WifiQrTest

echo "== Beheer- en netwerkscherm"
node --check "$ROOT/app/assets/admin.js"
node --check "$ROOT/app/assets/wifi.js"
node --test "$ROOT/tests/assets.test.mjs"
node --test "$ROOT/tests/scripts.test.mjs"

echo "== Inrichten via USB (provision.sh, nagebootste adb)"
bash -n "$ROOT/provision.sh"
bash "$ROOT/tests/provision.test.sh"

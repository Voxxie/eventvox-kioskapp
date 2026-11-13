#!/usr/bin/env bash
# Bouwt webkiosk.apk.
# Vereist (Debian/Ubuntu): apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange default-jdk
#
# Optionele omgevingsvariabelen:
#   VERSION_NAME   bv. 0.0.1      (standaard: 0.0.0-dev uit AndroidManifest.xml)
#   VERSION_CODE   bv. 1          (standaard: 0 uit AndroidManifest.xml)
#   KEYSTORE       pad naar keystore (standaard: webkiosk.keystore in de repo-map)
#   KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD   (standaard: webkiosk)
#   OUTPUT         pad van de APK (standaard: webkiosk.apk in de repo-map)
#   REQUIRE_KEYSTORE=1   stoppen als de keystore ontbreekt i.p.v. een nieuwe te maken (CI)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
KEYSTORE="${KEYSTORE:-$ROOT/webkiosk.keystore}"
export KS_PASS="${KEYSTORE_PASSWORD:-webkiosk}"
KEY_ALIAS="${KEY_ALIAS:-webkiosk}"
export KEY_PASS="${KEY_PASSWORD:-$KS_PASS}"
OUTPUT="${OUTPUT:-$ROOT/webkiosk.apk}"

cd "$ROOT/app"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
[ -f "$SDK" ] || SDK=$(ls /usr/lib/android-sdk/platforms/*/android.jar | head -1)

VERSION_ARGS=()
[ -n "${VERSION_CODE:-}${VERSION_NAME:-}" ] && VERSION_ARGS+=(--replace-version)
[ -n "${VERSION_CODE:-}" ] && VERSION_ARGS+=(--version-code "$VERSION_CODE")
[ -n "${VERSION_NAME:-}" ] && VERSION_ARGS+=(--version-name "$VERSION_NAME")

rm -rf build && mkdir -p build/gen build/classes
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -A assets -I "$SDK" "${VERSION_ARGS[@]}" -F build/unsigned.apk
javac --release 8 -nowarn -Xlint:-options -classpath "$SDK" -d build/classes $(find src build/gen -name '*.java')
dalvik-exchange --dex --output=build/classes.dex build/classes
(cd build && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  if [ -n "${REQUIRE_KEYSTORE:-}" ]; then echo "Keystore ontbreekt: $KEYSTORE" >&2; exit 1; fi
  echo "Nieuwe keystore aanmaken: $KEYSTORE (bewaar deze goed!)"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KEY_PASS" -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=WebKiosk" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
    --out "$OUTPUT" build/aligned.apk
apksigner verify "$OUTPUT"
echo "Klaar: $OUTPUT"

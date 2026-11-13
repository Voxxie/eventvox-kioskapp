#!/usr/bin/env bash
# Bouwt webkiosk.apk. Vereist (Debian/Ubuntu): apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange default-jdk
set -euo pipefail
cd "$(dirname "$0")/app"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
[ -f "$SDK" ] || SDK=$(ls /usr/lib/android-sdk/platforms/*/android.jar | head -1)
rm -rf build && mkdir -p build/gen build/classes
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK" -F build/unsigned.apk
javac --release 8 -nowarn -Xlint:-options -classpath "$SDK" -d build/classes $(find src build/gen -name '*.java')
dalvik-exchange --dex --output=build/classes.dex build/classes
(cd build && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
KS=../webkiosk.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass webkiosk -keypass webkiosk -alias webkiosk \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=WebKiosk" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:webkiosk --key-pass pass:webkiosk --out ../webkiosk.apk build/aligned.apk
echo "Klaar: $(cd .. && pwd)/webkiosk.apk"

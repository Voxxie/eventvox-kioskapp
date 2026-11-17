#!/usr/bin/env bash
# provision.sh zonder toestel: met een nagebootste adb (één toestel). Play Protect: de controle van USB-installaties
# gaat alleen tijdens het installeren uit en daarna terug naar de oude stand (ook als die er niet was).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
cp "$ROOT/provision.sh" "$ROOT/webkiosk.properties" "$T/"
: > "$T/webkiosk.apk"
mkdir "$T/bin"
cat > "$T/bin/adb" <<'ADB'
#!/bin/bash
echo "$*" >> "$CALLS"
case "$*" in
  devices) printf 'List of devices attached\nZEBRA1\tdevice\n' ;;
  *"settings get global verifier_verify_adb_installs"*) printf '%s\r\n' "$OLD" ;;
esac
exit 0
ADB
chmod +x "$T/bin/adb"
fail() { echo "FOUT: $*"; cat "$T/calls"; exit 1; }
run() { : > "$T/calls"; CALLS="$T/calls" OLD="$1" PATH="$T/bin:$PATH" bash "$T/provision.sh" >/dev/null; }
order() { grep -n "$1" "$T/calls" | head -1 | cut -d: -f1; }

run 1
[ "$(order 'settings put global verifier_verify_adb_installs 0')" -lt "$(order 'install -r')" ] || fail "controle niet vóór het installeren uit"
[ "$(order 'install -r')" -lt "$(order 'settings put global verifier_verify_adb_installs 1')" ] || fail "oude stand (1) niet terug na het installeren"
echo "ok   Play Protect: USB-controle uit tijdens installeren, daarna terug op 1"
run null
grep -q "settings delete global verifier_verify_adb_installs" "$T/calls" || fail "niet-bestaande instelling niet weer weggehaald"
echo "ok   Play Protect: instelling die er niet was, weer weggehaald"

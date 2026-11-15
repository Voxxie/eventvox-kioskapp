# EventVOX-Webkiosk: instructies voor agents

Kiosk-browser voor Zebra TC51-PDA's (Android 6–8.1) die één website fullscreen toont, in de praktijk de
EventVOX-scanterminal (`https://eventvox.nl/scanterminal?serial={serial}`, repository Voxxie/eventvox-frontend,
`public/scanterminal/`). De app is als *device owner* ingericht (lock task, eigen startscherm), zit in de weg van niemand
die geen kiosk-app gebruikt, en voegt toe wat een WebView mist: NFC, batterij, trillen, wifi, scanner (DataWedge),
updates, beheer op het toestel. Teksten voor gebruikers en commentaar in de code zijn **Nederlands**.

## Harde regels (nooit breken)

Elke geïnstalleerde versie, hoe oud ook, moet **rechtstreeks naar de nieuwste** kunnen updaten, en dat moet altijd zo
blijven. Een toestel op v0.0.1 haalt `update.json`, ziet een hoger `versionCode` en installeert meteen de nieuwste APK,
zonder tussenversies. Daarom:

1. **Pakketnaam blijft `nl.webkiosk`.** Een andere naam is voor Android een andere app: geen update, en de device owner
   is weg (alleen een fabrieksreset helpt dan).
2. **Altijd dezelfde keystore** (secret `KEYSTORE_BASE64`, alias `webkiosk`, wachtwoord in `KEYSTORE_PASSWORD`; het
   origineel bewaart de eigenaar buiten git). Een andere sleutel = Android weigert de update. Nooit een nieuwe sleutel
   laten maken in CI (`REQUIRE_KEYSTORE=1`).
3. **`update.json` blijft op dezelfde plek en in hetzelfde formaat**:
   - stabiel: `https://github.com/Voxxie/eventvox-kioskapp/releases/latest/download/update.json`
   - test: `https://github.com/Voxxie/eventvox-kioskapp/releases/download/beta/update.json`
   - formaat: `{ versionCode, versionName, url, sha256, notes }`; velden mogen erbij, nooit eruit of hernoemd. Oude
     versies lezen alleen deze vijf (`Updater.fetch`).
   - De repository blijft **openbaar**: de toestellen downloaden zonder inloggen.
4. **`versionCode` stijgt altijd.** Tag `vMAJOR.MINOR.PATCH` (MINOR en PATCH 0–99) → `MAJOR*10000 + MINOR*100 + PATCH`.
   Nooit een tag hergebruiken of verlagen. Lokale builds zijn `0.0.0-dev` (versionCode 0).
5. **Geen tussenstappen nodig.** Een nieuwe versie moet werken met de opgeslagen instellingen (`SharedPreferences`
   "kiosk") van **elke** oudere versie: ontbrekende sleutels krijgen een standaardwaarde, oude sleutels blijven geldig.
   Nooit een "migratie" die aanneemt dat een bepaalde tussenversie al gedraaid heeft.
6. **Android 8.1 blijft ondersteund** (`minSdkVersion` ≤ 21, TC51 heeft Android 6/7/8.1). Verhoog `targetSdkVersion`
   alleen als stille installatie (PackageInstaller als device owner), `file://`-installatie zonder device owner en de
   toestemmingen getest zijn; tot nu toe 23.
7. **De JavaScript-koppeling is een contract** met eventvox-frontend (zie hieronder): methoden, events en
   veldnamen alleen toevoegen, nooit verwijderen of hernoemen. Websites zonder de app mogen niets merken.
8. **Geen geheimen in logboek, status of git**: geen wachtwoorden, wifi-sleutels, tokens of PIN in `KioskLog`,
   `getStatus()`, `update.json` of commits. De keystore en `webkiosk.keystore` staan in `.gitignore`.

## Bouwen en releasen

- **Geen Gradle, geen Android Studio.** `build.sh` gebruikt Debian-pakketten:
  `apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange default-jdk`, dan `./build.sh`
  (APK: `webkiosk.apk`; variabelen `VERSION_NAME`, `VERSION_CODE`, `KEYSTORE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `OUTPUT`, `REQUIRE_KEYSTORE`).
- **Java 8-syntaxis, geen lambdas of method references**: `dalvik-exchange` (dx) kent geen invokedynamic. Gebruik
  anonieme klassen (`new Runnable() { ... }`). Gecompileerd tegen `android-23/android.jar`.
- **Nieuwere Android-API's**: aanroepen via reflectie of `Build.VERSION.SDK_INT`-controle. Moet een klasse uit een
  nieuwere API in een methodesignatuur staan (zoals `RenderProcessGoneDetail` voor `onRenderProcessGone`), zet dan een
  stub in `app/stubs/`: die staat alleen op het classpath, niet in de APK. Zo'n override krijgt **geen** `@Override`.
- **Release** = GitHub-release met tag `vX.Y.Z` → `.github/workflows/release.yml` bouwt, ondertekent, hangt
  `webkiosk-<versie>.apk`, `webkiosk.apk`, `.sha256`, `update.json` en een zip (APK + scripts + config) aan de release,
  en werkt de rollende pre-release **`beta`** bij (het testkanaal; geen versie, niet installeren).
- **Testkanaal**: markeer een release eerst als *pre-release*; alleen toestellen met `update_channel=beta` krijgen hem.
  Werkt hij, dan een gewone release (of het vinkje weghalen).
- `webkiosk.apk` en `*.zip` staan niet in git; de releases zijn de enige bron.

## Opbouw

| Bestand | Wat |
|---|---|
| `app/src/nl/webkiosk/MainActivity.java` | Alles in één Activity: WebView (`createWeb()`), kiosk/device owner, NFC (reader mode), DataWedge, toetsenbord-focus en wifi-QR via toetsen, wifi, batterij, trillen, updates, beheerscherm-koppeling (`AdminBridge`), netwerkscherm (`WifiBridge`), crash-herstel, terminal-identiteit, nachtelijk herladen, volume, dimmen, status/diagnose. Secties met `// ---------- naam ----------`. |
| `Updater.java` / `UpdateReceiver.java` | `update.json` ophalen (redirects zelf volgen), APK downloaden + SHA-256, stil installeren (device owner) of via Android; herstart na `MY_PACKAGE_REPLACED`. |
| `WifiProfiles.java` | Wifi uit `webkiosk.properties`, wifi-QR-codes (`parseQr`), toevoegen/verbinden, netwerken in de buurt. |
| `DataWedge.java` | Eigen DataWedge-profiel "EventVOX-Webkiosk" (scanner aan, toetsaanslagen uit, scan als intent `nl.webkiosk.SCAN`). |
| `TokenVault.java` | Geheim versleuteld in de Android Keystore (AES-GCM): het terminal-token van de scanterminal. |
| `KioskLog.java` | Logboek op het toestel (laatste 300 regels) voor diagnose. |
| `app/assets/admin.*` | Beheerscherm (HTML, opent na 10× tikken op dezelfde plek + PIN). Praat via `window.Kiosk`. |
| `app/assets/wifi.*` | Scherm "Netwerk toevoegen" (offline): wifi-QR-code of netwerk in de buurt. Praat via `window.WifiQr`. |
| `app/stubs/` | Alleen om te compileren (zie boven). |
| `webkiosk.properties` | Voorbeeldconfiguratie; wordt op `/sdcard/webkiosk.properties` gezet door `provision.*`. |
| `provision.sh` / `.bat` | Richt alle via USB (adb) aangesloten toestellen in: installeren, config, device owner, starten. |

De HTML-schermen in `assets/` draaien in een WebView op Android 8: **geen** CSS `gap` in flexbox, `inset`, optional
chaining (`?.`) of `??` in `assets/*.js`; ES5/vroege ES2015 en `closest()` zijn goed.

## Configuratie

Prioriteit: `webkiosk.properties` (alleen een **gewijzigd** bestand wordt opnieuw toegepast, stempel `cfg_stamp`) →
intent-extras (`url`, `pin`) → wat in het beheerscherm is ingesteld (opgeslagen in `SharedPreferences` "kiosk").
Sleutels: `url` (met `{serial}`, `{version}`, `{model}`), `pin`, `menu_taps`, `keep_screen_on`, `reload_minutes`,
`ignore_ssl_errors`, `nfc_mode`, `nfc_uid_format`, `nfc_keyboard_value`, `nfc_send_enter`, `nfc_debounce_ms`,
`nfc_refresh_seconds`, `volume_lock`, `dim_minutes`, `dim_level`, `nightly_reload`, `nightly_hour`, `low_battery`,
`orientation`, `update_channel`,
`auto_update`, `update_check_hours`, `update_url`, `wifi.N.ssid|password|security|hidden|priority`,
`wifi_remove_others`, `wifi_lockdown`, `wifi_qr`, `datawedge`, `scan_mode`, `vibrate`.
Een nieuwe sleutel: standaardwaarde in de code, inlezen in `readFileConfig()`, tonen/wijzigen in `adminState()` en
`applySetting()` (lijsten `STRING_KEYS`/`BOOL_KEYS`/`INT_KEYS`), in `webkiosk.properties` en in de README.

## Contract met EventVOX (eventvox-frontend, eventvox-backend)

**User agent**: de app zet `EventVOX-Webkiosk/<versie>` achter de user agent; de backend bewaart die per terminal en
de frontend toont "Kiosk-app" (`kioskAppText`).

**`window.WebKiosk`** (in de website):

| Methode | Geeft |
|---|---|
| `nfcAvailable()`, `nfcEnabled()` | boolean |
| `getBattery()` | JSON `{ level, charging, full, plugged, health, temperature, voltage, technology, pack?, raw }`; `pack` = Zebra-accu `{ percent, cycles, decommission, serial, part_number, manufactured, rated_capacity }` |
| `getWifi()` | JSON `{ connected, enabled, level 0-4, rssi, ssid?, link_speed, frequency }` |
| `getSerial()`, `getDeviceInfo()` | serienummer; JSON `{ serial, model, manufacturer, android, sdk, build, appVersion }` |
| `vibrate(pattern)` | trillen (JSON-array of getal, zoals `navigator.vibrate`) |
| `getStatus()` | JSON voor de heartbeat: `{ version, channel, uptime_s, crashes, last_crash_at, nfc_restarts, clock_skew_s, settings_version, command_done, orientation, wifi, update { checked_at, result }, log? }`; `log` alleen de eerste keer na `requestDiagnostics()` |
| `requestDiagnostics()` | het logboek gaat mee met de volgende `getStatus()` |
| `command(json)` | opdracht uit EventVOX `{ id, command: reload\|restart\|update\|locate }`; elke id één keer (`last_command_id`), gemeld als `command_done` in `getStatus()` |
| `applySettings(json)` | instellingen uit EventVOX `{ version, values }`; elke versie één keer, via `applySetting()` (nooit `url`, `pin`, `update_url`) |
| `setAdminKey(hex)` | sleutel van beheer-QR-codes (64 hex), versleuteld in `TokenVault` |

**Events** op `window`: `webkiosk-nfc` `{ uid, uidHex, techs, ndef: [{type, value}] }`, `webkiosk-scan`
`{ data, symbology }`, `webkiosk-battery` (zoals `getBattery`), `webkiosk-wifi` (zoals `getWifi`); ook als functies
`window.onWebKioskNfc` / `onWebKioskScan` / `onWebKioskBattery` / `onWebKioskWifi`.

**De app levert zelf** (vóór de scripts van de pagina, bij `onPageStarted`/`onPageCommitVisible`/`onPageFinished`):
`navigator.getBattery()` (altijd die van de app; `chargingTime` Infinity, zodat een volle accu aan de lader niet als
"geen accu" telt) en `navigator.vibrate()` (de trilmotor; een WebView trilt anders alleen na een tik).

**Scans en NFC** komen standaard als **toetsaanslagen + Enter** in de pagina (`typeText`), zoals een scanner-toetsenbord:
de scanterminal hoeft niets van de app te weten. Codes die met `WIFI:` of `EVXADMIN:` beginnen gaan nooit naar de pagina
(wifi-QR-code; beheer-QR-code `EVXADMIN:<verloop>.<HMAC-SHA256 hex>`, offline gecontroleerd met de sleutel uit `setAdminKey`).

**Backend-velden die hiervan afhangen** (Voxxie/eventvox-backend, `Helpers\Terminals`): heartbeat `battery`,
`battery_pack` (migratie 130), `kiosk_status` + `log` en antwoord `kiosk_diagnostics` (migratie 131), antwoord
`kiosk_command`, `kiosk_settings` en `kiosk_admin_key` (migratie 132; de scanterminal geeft ze door aan `command()`,
`applySettings()` en `setAdminKey()`); de backend
laat onbekende velden en foute waarden vallen, nooit een fout. Een veld toevoegen aan `getStatus()`: eerst in
`Terminals::kioskStatus()` (anders wordt het weggegooid), dan in de frontend tonen.

**Terminal-identiteit**: de scanterminal bewaart zijn token in `localStorage['eventvox.terminal.token']`. De app
bewaart een versleutelde kopie (met het adres) en zet die terug als de opslag verloren is (cache gewist, beschadigd),
waarna de pagina één keer herlaadt. Verdwijnt een teruggezet token binnen 10 minuten weer (EventVOX trok de terminal
in), dan vervalt de kopie. Verander die sleutelnaam nooit zonder de frontend mee te nemen.

## Gedrag dat bewust zo is

- Beheerscherm: 10× tikken op dezelfde plek (max 0,7 s ertussen) + PIN; 5× fout = 1 min geblokkeerd; staat de PIN nog
  op de standaard 1234, dan eerst een eigen PIN kiezen (1234 wordt geweigerd). Sluit na 3 min zonder aanraking.
- Updates: 1 minuut na de start en handmatig (`update_check_hours` = 0); installeren pas na 2 minuten zonder gebruik.
- Offline 30 s: balk "Je bent offline" met knop "Netwerk toevoegen" (blokkeert de pagina niet; offline scannen gaat door).
  Een wifi-QR-code werkt buiten het beheerscherm alleen zonder netwerk.
- Toetsen die het begin van `WIFI:` vormen worden max 100 ms vastgehouden (een mens typt trager dan een scanner) en
  anders in volgorde doorgestuurd.
- NFC na slaapstand: reader mode opnieuw bij scherm aan, ontgrendelen, focus en NFC aan (+ tweede poging na 1,5 s).
- Crash van de pagina (`onRenderProcessGone`): nieuwe WebView; 3× binnen een minuut: 30 s wachten.
- Nachtelijk herladen (standaard 04:00–07:00, alleen na 5 min rust), volume vast (80%, volumeknoppen geblokkeerd),
  dimmen (alleen met "scherm altijd aan", na 2 min naar 15%).
- Meldingen bovenaan (`bannerBox`, tikken gaan erdoor): accu bijna leeg (`low_battery`, 15%), klok wijkt > 2 min af
  (HEAD naar de website, Date-header; als device owner `setAutoTimeRequired`), offline met knop.
- Instellingen uit EventVOX gelden één keer per versie; een latere wijziging op het toestel blijft tot EventVOX een
  nieuwe versie stuurt. Een nieuwe instelling die EventVOX mag zetten: ook in `Terminals::KIOSK_SETTINGS` (backend) en
  het formulier in de frontend.

## Testen

Er is hier geen toestel of emulator; wat in Android zelf gebeurt, wordt op een echte TC51 getest (zeg dat eerlijk).
Wel te testen:
- **Bouwen**: `./build.sh` (met een tijdelijke keystore), en `aapt dump badging` voor versie, rechten en naam.
- **Pure Java** (bv. `WifiProfiles.parseQr`): compileren met `android.jar` op het classpath en draaien met `java`,
  zolang de code geen Android-methoden aanroept.
- **Beheer- en netwerkscherm** (`assets/`): openen in Chromium (Playwright) met een nagebootste `window.Kiosk` /
  `window.WifiQr`, en schermafbeeldingen op 360×640 maken.
- **JavaScript die de app in de pagina zet** (batterij, trillen): in Chromium tegen de echte scanterminal van
  eventvox-frontend (`tests/browser/harness.mjs` daar: `startFrontend`, `mockApi`), met een nagebootste `WebKiosk`.
- Schermafbeeldingen voor de README staan in `screenshots/`.

## Documentatie

`README.md` is de handleiding voor de beheerder (Nederlands). Werk die bij bij elke zichtbare wijziging, net als
`webkiosk.properties` bij een nieuwe instelling.

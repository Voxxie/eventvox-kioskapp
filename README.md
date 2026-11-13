# EventVOX-Webkiosk voor Zebra TC51

<img src="app/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="EventVOX">

Eigen kiosk-browser: opent één website fullscreen, zonder statusbalk/navigatieknoppen,
en is (als *device owner*) niet te verlaten door de gebruiker. Geen licenties of betaalde diensten.

> Pakketnaam blijft `nl.webkiosk` en de JavaScript-koppeling heet `WebKiosk` (events `webkiosk-nfc`, `webkiosk-battery`),
> zodat bestaande installaties en websites blijven werken.

## Bestanden
| Bestand | Doel |
|---|---|
| `webkiosk.apk` | De app (Android 5+; TC51 = Android 6/7/8). Staat **niet** in de repo: download hem bij de [nieuwste release](../../releases/latest) |
| `webkiosk.properties` | Configuratie: URL, PIN, scherm-aan, auto-herladen |
| `provision.bat` / `provision.sh` | Richt in één keer alle via USB aangesloten toestellen in |
| `app/`, `build.sh` | Broncode + buildscript (Java, geen Android Studio nodig) |
| `webkiosk.keystore` | Ondertekeningssleutel — **bewaren**, nodig voor updates van de app |

## Eenmalig per toestel voorbereiden
1. Fabrieksreset (of: verwijder alle accounts onder Instellingen > Accounts). Zonder accounts mag de app *device owner* worden.
2. Sla bij de eerste setup het Google-account over; verbind wel met wifi.
3. Instellingen > Over de telefoon > 7x op *Build-nummer* tikken → Ontwikkelaarsopties → **USB-foutopsporing** aan.

## Inrichten (meerdere tegelijk)
0. Download `webkiosk-<versie>.zip` bij de [nieuwste release](../../releases/latest) en pak hem uit (bevat APK, scripts en config).
1. Controleer de URL in `webkiosk.properties` (standaard eventvox scanterminal met serienummer).
2. Sluit de toestellen aan via USB (USB-hub of cradle) en accepteer de foutopsporingsvraag op elk toestel.
3. Windows: dubbelklik `provision.bat` (adb uit Google *platform-tools* moet in PATH of in de map staan).
   Linux/Mac: `./provision.sh` of `./provision.sh https://andere.url`.

Het script installeert de app, zet de config op `/sdcard/webkiosk.properties`, maakt de app device owner en start hem.
Daarna: statusbalk uit, home/recent-knoppen werken niet, de app start na een herstart vanzelf, schermvergrendeling uit.
USB-foutopsporing kun je daarna weer uitzetten.

## Beheermenu op het toestel
Tik **10x snel op dezelfde plek** op het scherm (maximaal ~0,7 s tussen de tikken) → voer de PIN in (standaard `1234`).

- **Website-adres wijzigen**
- **NFC-instellingen**: modus (uit / typen / JavaScript-event / beide), formaat tag-ID, wat typen (UID of tekst op tag),
  Enter na typen aan/uit, **NFC testen** (toont direct wat een tag oplevert), NFC in Android aan/uit
- **Overige instellingen**: PIN wijzigen, aantal tikken (5–20), scherm altijd aan, automatisch herladen, SSL-fouten negeren, wifi-instellingen
- **Pagina herladen** / **Cache en cookies wissen**
- **App sluiten**: naar de Android-instellingen; open EventVOX-Webkiosk opnieuw of herstart om terug te gaan
- **Kiosk-modus definitief uitschakelen**: toestel wordt weer normaal Android (opnieuw inrichten via provision-script)

Instellingen uit het menu blijven bewaard. Een **nieuw of gewijzigd** `webkiosk.properties`-bestand overschrijft ze bij de volgende start;
een ongewijzigd bestand niet.

## URL later wijzigen
- Via het beheermenu op het toestel, of
- `adb shell am start -n nl.webkiosk/.MainActivity --es url https://nieuwe.url`, of
- nieuwe `webkiosk.properties` naar `/sdcard/` kopiëren en app herstarten (bv. met Zebra **StageNow**, ook gratis).

## Zonder USB-kabel per toestel? (StageNow)
Met Zebra StageNow (gratis) kun je via één barcode-scan de APK en `webkiosk.properties` laten downloaden en installeren
(FileMgr + AppMgr). Zet de app dan als launcher (hij registreert zich als *Home*-app). Het harde slot (device owner)
kan StageNow niet zetten; daarvoor is de eenmalige adb-stap nodig. Alternatief voor de vergrendeling zonder adb: Zebra
**Enterprise Home Screen** (ook gratis) met EventVOX-Webkiosk als enige toegestane app.

## Barcodescanner
DataWedge staat standaard aan: een scan wordt als toetsaanslagen in het actieve invoerveld van de website getypt.

## NFC
De TC51 heeft NFC. WebView ondersteunt geen Web NFC, dus de app leest de tags zelf en geeft ze door aan de website.
Instellen in `webkiosk.properties` (`nfc_mode`):

- **keyboard**: de tag-ID (of de tekst op de tag) wordt als toetsaanslagen in het actieve invoerveld getypt, met Enter erachter.
  Dit werkt zonder aanpassingen aan je website, net als de barcodescanner.
- **event**: de website krijgt een JavaScript-event met alle gegevens:
  ```js
  window.addEventListener('webkiosk-nfc', e => {
    console.log(e.detail.uid);    // tag-ID volgens nfc_uid_format
    console.log(e.detail.uidHex); // altijd hex
    console.log(e.detail.ndef);   // [{type:'text'|'uri'|..., value:'...'}]
    console.log(e.detail.techs);  // ['NfcA','MifareClassic',...]
  });
  if (window.WebKiosk && WebKiosk.nfcEnabled()) { /* NFC aanwezig en aan */ }
  ```
- **both** (standaard) of **off**.

`nfc_uid_format`: `hex` (04A1B2C3…), `hex_reversed`, `hex_colon` (04:A1:…), `dec`, `dec_reversed`.
Veel toegangs- en kassasystemen gebruiken `dec_reversed`; kijk welk formaat jouw systeem verwacht.
Je kunt alles testen met `nfc-test.html`.

### NFC na slaapstand
Op Android (ook Zebra) stopt NFC soms na slaapstand: na scherm-uit/aan is de *reader mode* van de app niet meer actief.
De app vangt dat af door reader mode opnieuw te registreren bij **scherm aan**, **ontgrendelen**, **venster-focus** en
**NFC weer aan**, steeds met een tweede poging na 1,5 s (de NFC-chip is direct na het ontwaken soms nog niet klaar).
Een tag die nog tegen het toestel ligt wordt daardoor niet dubbel doorgegeven (`nfc_debounce_ms`, standaard 1500).

Werkt het op een toestel toch niet goed:
- Beheermenu > NFC > **Periodiek verversen** (of `nfc_refresh_seconds=30`) als extra vangnet;
- Beheermenu > NFC > **NFC nu herstarten** toont ook wanneer en waarom reader mode het laatst is herstart;
- Met het scherm uit leest Android nooit NFC. Wil je dat het scherm aan blijft: `keep_screen_on=true` (standaard uit).


## Wifi-profielen
Zet netwerken in `webkiosk.properties`; de app (als device owner) zet ze in Android:
```properties
wifi.1.ssid=EventVOX-Ingang
wifi.1.password=geheimwachtwoord
wifi.1.security=WPA        # WPA (WPA/WPA2-Personal, standaard), WEP of OPEN
wifi.1.hidden=false        # true voor een verborgen netwerk
wifi.1.priority=10         # hoger = eerder kiezen
wifi.2.ssid=Festival-Backup
wifi.2.password=anderwachtwoord
wifi_remove_others=false   # true: alle andere opgeslagen netwerken verwijderen
wifi_lockdown=false        # true: gebruikers kunnen deze netwerken niet wijzigen of vergeten
```
- Tot 20 netwerken. Een netwerk met dezelfde naam wordt bijgewerkt (bv. nieuw wachtwoord); een netwerk dat de app eerder
  zette en niet meer in het bestand staat, wordt verwijderd.
- Toegepast bij het inrichten en telkens als er een **gewijzigd** bestand op het toestel wordt gezet (provision-script of StageNow).
- Bedrijfsnetwerken met gebruikersnaam (WPA2-Enterprise/802.1X) worden niet ondersteund.
- De wachtwoorden staan leesbaar in het bestand. Het provision-script haalt het bestand daarom na 10 seconden weer van
  het toestel; de app heeft de instellingen dan al opgeslagen. Zet het bestand niet met echte wachtwoorden in Git.
- Status: beheermenu > Overige instellingen > Wifi-instellingen toont welke profielen de app heeft gezet.

## Serienummer in de URL
Standaard-URL: `https://eventvox.nl/scanterminal?serial={serial}`
`{serial}` wordt vervangen door het serienummer van het toestel (`{model}` door het model). Eindigt de URL op `serial=`,
dan wordt het serienummer er ook automatisch achter gezet. Het serienummer staat bovenaan in het beheermenu.

## App-versie zichtbaar in EventVOX
De app zet `EventVOX-Webkiosk/<versie>` achter de user agent van de browser. De EventVOX-backend bewaart de user agent
bij elke heartbeat van een terminal; de terminalpagina in EventVOX toont daaruit de regel **Kiosk-app**
(bv. *EventVOX-Webkiosk 1.4.0*). Wil je de versie ook in de URL: gebruik `{version}`, bv.
`https://eventvox.nl/scanterminal?serial={serial}&app={version}`.

## JavaScript: batterij en toestel
```js
// Standaard Battery API: de app levert hem zelf (uit Android's BatteryManager), vóór de scripts van de pagina,
// zodat ook de EventVOX-scanterminal het batterij-icoon toont en de batterij met de heartbeat meestuurt.
// chargingTime is altijd Infinity: een volle accu aan de lader blijft zo 100% ⚡ tonen.
const b = await navigator.getBattery();
b.level;     // 0.0 - 1.0
b.charging;  // true/false
b.addEventListener('levelchange', () => ...);
b.addEventListener('chargingchange', () => ...);

// Uitgebreid (via de app)
const info = JSON.parse(WebKiosk.getBattery());
// { level: 87, charging: true, full: false, plugged: "ac"|"usb"|"none",
//   health: "good", temperature: 28.5, voltage: 4.12, technology: "Li-ion",
//   raw: { ...alle gegevens van Android/Zebra, o.a. accu-serienummer en laadcycli indien beschikbaar } }
window.addEventListener('webkiosk-battery', e => console.log(e.detail));   // bij wijziging

WebKiosk.getSerial();                    // "18123522501234"
JSON.parse(WebKiosk.getDeviceInfo());    // { serial, model, manufacturer, android, sdk, build, appVersion }
```

## Gedrag
- Geen verbinding → melding en automatisch opnieuw proberen elke 10 s.
- Links naar `tel:`, `mailto:`, andere apps worden genegeerd; alles blijft binnen de kiosk.
- Terug-knop = vorige pagina binnen de site, nooit de app uit.

## Opnieuw bouwen
`sudo apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange default-jdk && ./build.sh`

## Automatische updates
Bij elke release maakt de workflow ook een `update.json` (versie, download-URL, SHA-256, release-notes).
De app controleert die **1 minuut na het starten** en wanneer je in het beheermenu op *Nu controleren* tikt
(periodiek kan ook: `update_check_hours`). Is er een nieuwere versie, dan:
- downloadt hij de APK en controleert de SHA-256;
- wacht hij tot het toestel **2 minuten niet is aangeraakt** (geen update midden in een scan);
- installeert hij **stil** (als device owner) en start daarna vanzelf opnieuw op.

Beheermenu > **Updates**: nu controleren (met release-notes en knop *Nu installeren*), automatisch bijwerken aan/uit,
interval, en het update-adres. In `webkiosk.properties`: `auto_update`, `update_check_hours`, `update_url`.

> **Let op:** de toestellen moeten `update.json` en de APK zonder inloggen kunnen downloaden.
> Het standaard-adres is `https://github.com/Voxxie/eventvox-kioskapp/releases/latest/download/update.json`;
> dat werkt alleen als de repository **openbaar** is. Bij een private repository zet je `update.json` + `webkiosk.apk`
> op een eigen webserver en vul je dat adres in bij `update_url` (de `url` in update.json mag relatief zijn, bv. `webkiosk.apk`).

## Releases (APK automatisch bouwen op GitHub)
Bij elke **gepubliceerde release** bouwt GitHub Actions (`.github/workflows/release.yml`) een ondertekende APK
en hangt die aan de release: `webkiosk-<versie>.apk`, `webkiosk.apk`, een SHA-256 en een zip met APK + scripts + config.

**Eenmalig instellen** (Settings > Secrets and variables > Actions > *New repository secret*):

| Secret | Waarde |
|---|---|
| `KEYSTORE_BASE64` | de keystore als base64 (zie hieronder) |
| `KEYSTORE_PASSWORD` | `webkiosk` (wachtwoord van de huidige keystore) |

Keystore naar base64:
- Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("webkiosk.keystore")) | Set-Clipboard`
- Mac/Linux: `base64 -w0 webkiosk.keystore` (Mac: `base64 -i webkiosk.keystore`)

Gebruik altijd **dezelfde** keystore: met een andere sleutel kan de app niet over bestaande installaties heen worden bijgewerkt.

**Release maken:** Releases > *Draft a new release* > tag `v1.4.0` > *Publish*. Na ±1 minuut staat de APK bij de release.
- Tag-formaat `vMAJOR.MINOR.PATCH`; versionCode wordt `MAJOR*10000 + MINOR*100 + PATCH` (v1.4.0 → 10400),
  dus elke nieuwe tag moet hoger zijn dan de vorige.
- Testen zonder release: Actions > *Release APK* > *Run workflow*; de APK staat dan onder *Artifacts*.

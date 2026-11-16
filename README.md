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

## Inrichten met een QR-code (zonder USB)

In EventVOX, in de **admin-tenant**: *Terminals › Inricht-QR-code*. Vul het wifi-netwerk in (en desgewenst website-adres,
PIN en updatekanaal) en print de code. Dan per toestel: fabrieksreset, op het welkomstscherm **6 keer op dezelfde plek
tikken**, de code scannen. Android verbindt met de wifi, downloadt de nieuwste `webkiosk.apk`, controleert de handtekening
en maakt de app device owner; de app neemt adres, PIN en kanaal één keer over (logboek: *Ingericht met een QR-code*).

- Werkt vanaf **Android 7**; een TC51 met Android 6 richt je in met USB (hieronder).
- De Zebra-apps (DataWedge) blijven aan. Of de TC51-firmware het inrichten met een QR-code ondersteunt, moet op een
  toestel getest worden; lukt het niet, gebruik dan USB of StageNow.
- De code bevat het wifi-wachtwoord en de PIN: bewaar de afdruk niet zichtbaar.

## Inrichten (meerdere tegelijk)
0. Download `webkiosk-<versie>.zip` bij de [nieuwste release](../../releases/latest) en pak hem uit (bevat APK, scripts en config).
1. Controleer de URL in `webkiosk.properties` (standaard eventvox scanterminal met serienummer).
2. Sluit de toestellen aan via USB (USB-hub of cradle) en accepteer de foutopsporingsvraag op elk toestel.
3. Windows: dubbelklik `provision.bat` (adb uit Google *platform-tools* moet in PATH of in de map staan).
   Linux/Mac: `./provision.sh` of `./provision.sh https://andere.url`.

Het script installeert de app, zet de config op `/sdcard/webkiosk.properties`, maakt de app device owner en start hem.
Daarna: statusbalk uit, home/recent-knoppen werken niet, de app start na een herstart vanzelf, schermvergrendeling uit.
USB-foutopsporing kun je daarna weer uitzetten.

## Beheerscherm op het toestel
Tik **10x snel op dezelfde plek** en voer de PIN in (of scan een beheer-QR-code). Het beheerscherm opent op een
**overzicht**:
- **Status**: Netwerk, Accu, NFC, Scanner, Updates en Kiosk; grijs als alles goed is, oranje of rood als er iets is.
  Tik op een tegel voor de details.
- **Snelle acties**: *Pagina herladen*, *Netwerk toevoegen* en *Scan testen* (barcode en NFC; gaat niet naar de website).
- **Menu**, met een korte samenvatting per onderdeel: Website, Scannen (scanner en NFC), Scherm en geluid, Netwerk,
  Updates, Apparaat (met logboek en diagnose) en Beveiliging (PIN, aantal tikken).
- **Kiosk verlaten** staat apart onderaan.

In de detailschermen staan de gewone instellingen bovenaan; zelden gebruikte onder **Meer opties**. Een instelling die
uit EventVOX komt, heeft het labeltje **via EventVOX**. Terug naar het overzicht met het pijltje of de terugknop; het
scherm sluit vanzelf na 3 minuten zonder aanraking.

## URL later wijzigen
- Via het beheermenu op het toestel, of
- `adb shell am start -n nl.webkiosk/.MainActivity --es url https://nieuwe.url`, of
- nieuwe `webkiosk.properties` naar `/sdcard/` kopiëren en app herstarten (bv. met Zebra **StageNow**, ook gratis).

## Zonder USB-kabel per toestel? (StageNow)
Met Zebra StageNow (gratis) kun je via één barcode-scan de APK en `webkiosk.properties` laten downloaden en installeren
(FileMgr + AppMgr). Zet de app dan als launcher (hij registreert zich als *Home*-app). Het harde slot (device owner)
kan StageNow niet zetten; daarvoor is de eenmalige adb-stap nodig. Alternatief voor de vergrendeling zonder adb: Zebra
**Enterprise Home Screen** (ook gratis) met EventVOX-Webkiosk als enige toegestane app.

## Barcodescanner (DataWedge)
De app maakt bij het starten zelf een DataWedge-profiel **EventVOX-Webkiosk** (scanner aan, toetsaanslagen uit, scan
als intent naar de app). De app typt de code dan in de pagina, gevolgd door Enter, precies zoals een scanner-toetsenbord:
de EventVOX-scanterminal (en elke site die op toetsaanslagen + Enter wacht) hoeft niets te weten van de app.
- `scan_mode`: `keyboard` (standaard), `event` (JavaScript-event `webkiosk-scan` met `{ data, symbology }`), `both`, `off`.
- `datawedge=off`: de app laat DataWedge met rust en verwijdert zijn profiel; dan moet DataWedge zelf toetsaanslagen
  met Enter sturen. Toetsaanslagen komen altijd bij de website aan, ook als die even geen focus had.
- Beheerscherm > Scanner: status van het profiel, een testscan (gaat dan niet naar de website) en *Profiel opnieuw instellen*.


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

### Netwerk toevoegen (zonder netwerk)
Heeft het toestel 30 seconden geen netwerk, dan staat bovenaan **Je bent offline** met de knop **Netwerk toevoegen**.
De balk blokkeert niets (alleen de knop reageert): de scanterminal blijft offline scannen. De knop opent een scherm met:
- **Wifi-QR-code**: scan een code (`WIFI:T:WPA;S:naam;P:wachtwoord;;`, het standaardformaat); je ziet naam en
  beveiliging en kiest *Toevoegen en verbinden*.
- **Netwerken in de buurt**: met signaalsterkte, slotje en *opgeslagen*/*verbonden*; tik op een netwerk, vul zo nodig het
  wachtwoord in en kies *Verbinden* (een opgeslagen netwerk kan zonder wachtwoord). Android geeft deze lijst alleen met
  locatie aan: de app zet die als device owner zelf aan, anders staat er uitleg.
Verbinden duurt tot 60 s; lukt het niet, dan zegt het scherm dat en blijven de andere netwerken gewoon werken. Hetzelfde
scherm staat in het beheerscherm (Wifi > *Netwerk toevoegen*), ook als het toestel online is. Een wifi-QR-code zonder
het scherm open opent het ook (alleen offline).
- Wifi-codes gaan nooit naar de website. Met netwerk doet een wifi-code niets, behalve in het beheerscherm: daar wordt
  een gescande wifi-code meteen toegevoegd (Wifi-kaart).
- Een code maken: in EventVOX bij Terminals de knop *Wifi-QR-code* (het wachtwoord blijft in de browser), met een
  QR-generator, of een Android-telefoon (Instellingen > Wifi > Delen).
- Netwerken die via dit scherm of een QR-code zijn toegevoegd, worden niet weggehaald door `wifi_remove_others` of de profielen uit `webkiosk.properties`.
- Werkt met DataWedge via de app (standaard) én als toetsaanslagen (DataWedge met eigen profielen, een USB- of
  Bluetooth-scanner): toetsen die met `WIFI:` beginnen worden even vastgehouden en niet naar de website gestuurd; is het
  toch geen wifi-code, dan gaan ze alsnog door. Na een pauze van meer dan 0,1 s gaat alles direct door (een mens typt
  trager dan een scanner). `wifi_qr=false` zet het uit.

## Serienummer in de URL
Standaard-URL: `https://eventvox.nl/scanterminal?serial={serial}`
`{serial}` wordt vervangen door het serienummer van het toestel (`{model}` door het model). Eindigt de URL op `serial=`,
dan wordt het serienummer er ook automatisch achter gezet. Het serienummer staat bovenaan in het beheermenu.

## App-versie zichtbaar in EventVOX
De app zet `EventVOX-Webkiosk/<versie>` achter de user agent van de browser. De EventVOX-backend bewaart de user agent
bij elke heartbeat van een terminal; de terminalpagina in EventVOX toont daaruit de regel **Kiosk-app**
(bv. *EventVOX-Webkiosk 0.0.1*). Wil je de versie ook in de URL: gebruik `{version}`, bv.
`https://eventvox.nl/scanterminal?serial={serial}&app={version}`.

## Wifi-sterkte
`WebKiosk.getWifi()` geeft `{ connected, level 0-4, rssi (dBm), ssid, link_speed (Mbps), frequency (MHz) }`; bij een
andere stand (verbonden, niveau, netwerk) volgt het event `webkiosk-wifi`. De EventVOX-scanterminal toont dat rechtsboven
als vier streepjes (rood bij zwak of geen verbinding). De netwerknaam geeft Android alleen met locatie-toestemming (die
krijgt de app als device owner) en met locatie aan op het toestel; anders ontbreekt `ssid`.

## Trillen
De app levert `navigator.vibrate()` zelf, via de trilmotor van Android. De EventVOX-scanterminal roept hem bij elke
uitslag aan (1× kort toegelaten, 2× kort waarschuwing, 3× lang geweigerd); in een gewone WebView zou dat niet trillen,
want die trilt alleen na een tik op de pagina, en een scan met DataWedge of NFC is geen tik. Uit te zetten met
`vibrate=false` of in het beheerscherm (Scanner), met testknoppen voor de drie patronen.

## Accu-slijtage in EventVOX
Op een Zebra geeft Android extra gegevens over de accu zelf. De app zet die in `WebKiosk.getBattery().pack`
(gezondheid %, laadcycli, vervangadvies van Zebra, onderdeel- en serienummer, productiedatum, capaciteit; alleen wat het
toestel kent). De EventVOX-scanterminal stuurt dat mee met de heartbeat; de terminalpagina toont de regel **Accu**, en
lijst en pagina tonen *Accu vervangen* (rood) of *Accu NN%* (oranje, onder 80%). In een gewone browser gebeurt niets.

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
//   pack: { percent, cycles, decommission, serial, part_number, manufactured, rated_capacity },  // alleen wat de accu weet (Zebra)
//   raw: { ...alle gegevens van Android/Zebra } }
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

## Controle bij elke wijziging

Bij elke push en pull request bouwt GitHub (Actions › *Controle*) de app met een tijdelijke sleutel en controleert
pakketnaam, versie en toestemmingen, de wifi-QR-herkenning en het beheer- en netwerkscherm in een browser. Lokaal:
`tests/run.sh` (build-tools zoals bij *Opnieuw bouwen*, plus Node en Playwright met Chromium). Wat Android zelf doet
(NFC, scanner, device owner) wordt alleen op een echte TC51 getest.

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

## Betrouwbaarheid en beheer
- **Herstel na een crash**: stopt of crasht de pagina (bv. door geheugengebrek), dan maakt de app hem opnieuw en laadt
  de website; drie keer binnen een minuut: 30 s wachten. Het aantal crashes staat in het beheerscherm en in EventVOX.
- **Terminal-identiteit**: de app bewaart het token van de EventVOX-scanterminal versleuteld (Android Keystore) en zet
  het terug als de opslag van de pagina verloren is (cache gewist, beschadigd). De terminal meldt zich dan als dezelfde
  terminal aan. Wordt de terminal in EventVOX ingetrokken, dan vervalt de kopie en koppelt hij gewoon opnieuw.
- **'s Nachts herladen** (`nightly_reload`, `nightly_hour`, standaard 04:00): één keer per nacht een verse pagina, alleen
  als het toestel 5 minuten niet gebruikt is.
- **Volume vast** (`volume_lock`, standaard uit): aan (bv. 80%) blijft de piep van de scanterminal altijd hoorbaar en doen
  de volumeknoppen niets; uit werken de volumeknoppen gewoon.
- **Dimmen** (`dim_minutes`, `dim_level`): met *scherm altijd aan* na 2 minuten zonder tik, scan of NFC naar 15%.
- **Eigen PIN verplicht**: staat de PIN nog op 1234, dan vraagt het beheerscherm eerst een eigen PIN.
- **Logboek en diagnose**: de app houdt de laatste 300 gebeurtenissen bij (start, crash, wifi, updates, fouten; geen
  wachtwoorden of tokens). Beheerscherm > Apparaat: *Diagnose naar EventVOX sturen*; in EventVOX kan het ook op afstand
  worden opgevraagd (terminalpagina). `WebKiosk.getStatus()` geeft de status voor de heartbeat.

## Op de evenementdag
- **Klok**: als device owner houdt Android de automatische tijd aan; elke 15 minuten vergelijkt de app de klok met de
  server. Meer dan 2 minuten verschil: een oranje balk (offline scans worden op de klok van het toestel beoordeeld) en
  een waarschuwing in EventVOX.
- **Accu bijna leeg** (`low_battery`, standaard 15%): een rode balk "leg me aan de lader" als hij niet aan de lader ligt.
- **Schermstand** (`orientation`): standaard staand; ook liggend of meedraaien.
- **Laadscherm**: het EventVOX-logo tijdens het laden in plaats van een wit scherm.

## Bediening vanuit EventVOX
Op de terminalpagina (kaart Kiosk-app) en voor een selectie in de terminallijst:
- **Opdrachten**: *Pagina herladen*, *App herstarten*, *Nu bijwerken* (zonder te wachten tot het toestel rustig is) en
  *Zoek mij* (30 s piepen, trillen en knipperen; een tik stopt het). Elke opdracht wordt precies één keer uitgevoerd.
- **Locatie bij Zoek mij**: het toestel zoekt hoogstens een minuut zijn positie (GPS, buiten het nauwkeurigst) en stuurt die
  mee met het wifi-toegangspunt waarmee het verbonden is (binnen vaak handiger). EventVOX toont hem op een kaartje bij de
  terminal. Alleen de laatste locatie, 24 uur bewaard; er wordt nooit zomaar een locatie vastgelegd.
- **Kiosk-instellingen**: volume, dimmen, scherm altijd aan, nachtelijk herladen, kanaal, automatisch bijwerken, NFC,
  scanner, trillen, accugrens en schermstand. Niet het adres en de PIN: die blijven op het toestel. Instellingen uit
  EventVOX worden één keer toegepast; wie daarna op het toestel iets wijzigt, houdt dat tot EventVOX opnieuw iets stuurt.
- **Beheer-QR-code** (knop op de terminalspagina): opent het beheerscherm zonder PIN, 1 uur tot 1 week geldig, ook
  zonder netwerk. *Alle beheer-QR-codes intrekken* maakt eerdere codes ongeldig.
- **Versies**: in de terminallijst krijgt een terminal met een oudere app dan de nieuwste van zijn kanaal een oranje
  label (*App 0.0.12*); bovenaan staat hoeveel het er zijn, met **Alle bijwerken** (de opdracht *Nu bijwerken* naar
  al die terminals). De nieuwste versie haalt EventVOX elk uur bij GitHub op.
- **Inricht-QR-code** (alleen in de admin-tenant): zie *Inrichten met een QR-code* hierboven.

## Testkanaal
Zet één of twee toestellen op **Test** (beheerscherm > Updates, of `update_channel=beta`). Markeer een nieuwe release op
GitHub eerst als **pre-release**: alleen de testtoestellen krijgen hem. Werkt hij, haal het vinkje weg (of maak een gewone
release): dan krijgen alle toestellen hem. De workflow houdt hiervoor een pre-release **beta** bij met de nieuwste
`update.json`; die is geen versie en hoeft niet geïnstalleerd te worden.

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

**Release maken:** Releases > *Draft a new release* > tag `v0.0.1` > *Publish*. Na ±1 minuut staat de APK bij de release.
- Tag-formaat `vMAJOR.MINOR.PATCH` (MINOR en PATCH 0–99); versionCode wordt `MAJOR*10000 + MINOR*100 + PATCH`
  (v0.0.1 → 1, v0.1.0 → 100, v1.0.0 → 10000), dus elke nieuwe tag moet hoger zijn dan de vorige.
- Een lokaal gebouwde APK (`./build.sh` zonder versie) heeft versie `0.0.0-dev` (versionCode 0): elke release is
  daar een update van, ook v0.0.1.
- Testen zonder release: Actions > *Release APK* > *Run workflow*; de APK staat dan onder *Artifacts*.

package nl.webkiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.nfc.*;
import android.nfc.tech.Ndef;
import org.json.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.Properties;

/**
 * Fullscreen kiosk-browser. Configuratie (hoogste prioriteit eerst):
 *  1. /sdcard/webkiosk.properties  (url=..., pin=..., keep_screen_on=false, reload_minutes=0)
 *  2. intent-extras bij starten (adb: am start -n nl.webkiosk/.MainActivity --es url https://...)
 *  3. eerder opgeslagen waarden / instellen via verborgen beheermenu
 * Beheermenu: tik 10x snel op dezelfde plek, voer PIN in.
 */
public class MainActivity extends Activity {
    private static final String CONFIG_FILE = "webkiosk.properties";
    private static final String PREFS = "kiosk";
    private static final String DEFAULT_URL = "https://eventvox.nl/scanterminal?serial={serial}";
    private static final int SAME_SPOT_DP = 48;
    private static final long MAX_TAP_GAP_MS = 700, RETRY_MS = 10000;

    private WebView web;
    private SharedPreferences prefs;
    private DevicePolicyManager dpm;
    private ComponentName admin;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int tapCount = 0;
    private long lastTap = 0;
    private float tapX, tapY;
    private boolean nfcTesting = false;
    private FrameLayout root;
    private WebView adminView;
    private boolean adminUnlocked = false;
    private int pinFails = 0;
    private long pinLockedUntil = 0;
    private Updater.Info lastUpdateInfo;
    private boolean kioskSuspended = false;
    private boolean pageError = false;
    private NfcAdapter nfc;
    private Intent lastBattery;
    private String lastBatterySig = "";
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            lastBattery = i;
            JSONObject b = batteryJson(i);
            updateBatteryBanner(b);
            // alleen een event als er iets wezenlijks verandert (niet bij elke spanningswijziging)
            String sig = b.optInt("level") + "|" + b.optBoolean("charging") + "|" + b.optString("plugged") + "|" + b.optString("health");
            if (web != null && !sig.equals(lastBatterySig)) {
                lastBatterySig = sig;
                web.evaluateJavascript("(function(d){try{window.dispatchEvent(new CustomEvent('webkiosk-battery',{detail:d}));}catch(e){}"
                    + "if(typeof window.onWebKioskBattery==='function')window.onWebKioskBattery(d);})(" + b + ");", null);
            }
        }
    };

    private final Runnable retry = new Runnable() { public void run() { loadHome(); } };
    private final Runnable periodicReload = new Runnable() {
        public void run() {
            if (!kioskSuspended) loadHome();
            schedulePeriodicReload();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, AdminReceiver.class);

        applyDeviceOwnerPolicies();
        readIntentConfig(getIntent());
        readFileConfig();

        if (prefs.getBoolean("keep_screen_on", false))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        nfc = NfcAdapter.getDefaultAdapter(this);
        web = createWeb();

        lastBattery = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter wf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wf.addAction(Intent.ACTION_USER_PRESENT);
        wf.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        registerReceiver(wakeReceiver, wf);
        IntentFilter wifiF = new IntentFilter(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION);
        wifiF.addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION);
        wifiF.addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiReceiver, wifiF);
        // DataWedge-scans en de uitslag van het instellen (met en zonder categorie: DataWedge zet er geen bij)
        IntentFilter df = new IntentFilter(DataWedge.SCAN_ACTION);
        df.addAction(DataWedge.RESULT_ACTION);
        registerReceiver(scanReceiver, df);
        IntentFilter dfc = new IntentFilter(DataWedge.SCAN_ACTION);
        dfc.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(scanReceiver2, dfc);

        root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Meldingen bovenaan (accu, klok, offline): tikken gaan erdoorheen naar de pagina, behalve op een knop
        bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(bannerBox, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        showSplash();
        applyOrientation();
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        watchSystemBars();
        setupDataWedge();
        loadHome();
        schedulePeriodicReload();
        handler.postDelayed(updateCheck, 60000); // eerste controle 1 minuut na start
        handler.postDelayed(offlineCheck, 5000);
        handler.postDelayed(nightlyCheck, 60000);
        handler.postDelayed(dimCheck, 15000);
        handler.postDelayed(clockCheck, 20000);
        KioskLog.add(this, "App gestart, versie " + appVersion() + (isOwner() ? "" : " (geen device owner)"));
    }

    /** De WebView van de website, met alles wat de app eraan toevoegt (opnieuw gemaakt na een crash) */
    private WebView createWeb() {
        final WebView w = new WebView(this);
        w.setBackgroundColor(Color.WHITE);
        WebSettings s = w.getSettings();
        // Naam + versie in de user agent: de EventVOX-backend bewaart die per terminal (Kiosk-app op de terminalpagina)
        s.setUserAgentString(s.getUserAgentString() + " EventVOX-Webkiosk/" + appVersion());
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("http:") || url.startsWith("https:") || url.startsWith("about:")
                        || url.startsWith("file:") || url.startsWith("data:")) return false;
                return true; // geen externe apps (tel:, mailto:, intent:) openen
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                pageError = false;
                v.evaluateJavascript(BATTERY_POLYFILL + VIBRATE_POLYFILL, null); // zo vroeg mogelijk: vóór de scripts van de pagina
            }
            @Override public void onPageCommitVisible(WebView v, String url) {
                v.evaluateJavascript(BATTERY_POLYFILL + VIBRATE_POLYFILL, null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(BATTERY_POLYFILL + VIBRATE_POLYFILL, null);
                focusWeb();
                checkTerminalToken(v, url);
                hideSplash();
            }
            /** Android 8+: het proces van de pagina is gecrasht of door Android gestopt (geheugen); niet de hele app */
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                rebuildWeb(detail != null && detail.didCrash() ? "pagina gecrasht" : "pagina gestopt door Android (geheugen)", true);
                return true;
            }
            @SuppressWarnings("deprecation")
            @Override public void onReceivedError(WebView v, int code, String desc, String url) {
                showError(desc);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showError(String.valueOf(e.getDescription()));
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                if (prefs.getBoolean("ignore_ssl_errors", false)) h.proceed(); else { h.cancel(); showError("SSL-fout"); }
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback cb) {
                cb.invoke(o, true, false);
            }
        });

        w.addJavascriptInterface(new Object() {
            @JavascriptInterface public boolean nfcAvailable() { return nfc != null; }
            @JavascriptInterface public boolean nfcEnabled() { return nfc != null && nfc.isEnabled(); }
            @JavascriptInterface public String getBattery() { return batteryJson(lastBattery).toString(); }
            @JavascriptInterface public String getSerial() { return serial(); }
            /** pattern: JSON-array in ms (aan, uit, aan, ...) of één getal; [] of 0 stopt het trillen. */
            @JavascriptInterface public boolean vibrate(String pattern) { return doVibrate(pattern); }
            /** De wifi-verbinding: { connected, level 0-4, rssi (dBm), ssid?, link_speed (Mbps), frequency (MHz) } */
            @JavascriptInterface public String getWifi() { return wifiJson().toString(); }
            /** De staat van de app voor de heartbeat (EventVOX: terminalpagina); met log als om een diagnose is gevraagd */
            @JavascriptInterface public String getStatus() { return statusJson().toString(); }
            /** EventVOX vraagt een diagnose: het logboek gaat mee met de volgende getStatus() */
            @JavascriptInterface public void requestDiagnostics() { diagnosticsPending = true; KioskLog.add(MainActivity.this, "Diagnose gevraagd door EventVOX"); }
            /** Een opdracht uit EventVOX ({ id, command }); elke id één keer (ook na een herstart) */
            @JavascriptInterface public void command(final String json) { runOnUiThread(new Runnable() { public void run() { runCommand(json); } }); }
            /** Instellingen uit EventVOX ({ version, values }); elke versie één keer */
            @JavascriptInterface public void applySettings(final String json) { runOnUiThread(new Runnable() { public void run() { applyRemoteSettings(json); } }); }
            /** De sleutel van beheer-QR-codes van de organisatie (64 hex), versleuteld bewaard */
            @JavascriptInterface public void setAdminKey(String hex) {
                if (hex == null || !hex.matches("[0-9a-f]{64}")) return;
                if (!hex.equals(TokenVault.get(prefs, "admin_qr_key"))) {
                    TokenVault.put(prefs, "admin_qr_key", hex);
                    KioskLog.add(MainActivity.this, "Sleutel voor beheer-QR-codes ontvangen");
                }
            }
            @JavascriptInterface public String getDeviceInfo() {
                JSONObject o = new JSONObject();
                try {
                    o.put("serial", serial()); o.put("model", Build.MODEL); o.put("manufacturer", Build.MANUFACTURER);
                    o.put("android", Build.VERSION.RELEASE); o.put("sdk", Build.VERSION.SDK_INT);
                    o.put("build", Build.DISPLAY); o.put("appVersion", appVersion());
                } catch (Exception ignored) {}
                return o.toString();
            }
        }, "WebKiosk");
        return w;
    }

    // ---------- configuratie ----------

    private void readIntentConfig(Intent i) {
        if (i == null || i.getExtras() == null) return;
        SharedPreferences.Editor e = prefs.edit();
        for (String key : new String[]{"url", "pin"}) {
            String v = i.getStringExtra(key);
            if (v != null && !v.trim().isEmpty()) e.putString(key, v.trim());
        }
        e.apply();
    }

    private void readFileConfig() {
        File[] candidates = {
            new File(Environment.getExternalStorageDirectory(), CONFIG_FILE),
            new File("/enterprise/usr/" + CONFIG_FILE),
            new File(getExternalFilesDir(null), CONFIG_FILE)
        };
        for (File f : candidates) {
            if (f == null || !f.canRead()) continue;
            Properties p = new Properties();
            String stamp = f.getPath() + ":" + f.length() + ":" + f.lastModified();
            if (stamp.equals(prefs.getString("cfg_stamp", ""))) return; // ongewijzigd: menu-instellingen blijven gelden
            try (InputStream in = new FileInputStream(f)) {
                p.load(new InputStreamReader(in, "UTF-8"));
            } catch (Exception ex) { continue; }
            SharedPreferences.Editor e = prefs.edit();
            e.putString("cfg_stamp", stamp);
            String mt = p.getProperty("menu_taps");
            if (mt != null) try { e.putInt("menu_taps", Integer.parseInt(mt.trim())); } catch (NumberFormatException ignored) {}
            for (String key : new String[]{"url", "pin"}) {
                String v = p.getProperty(key);
                if (v != null && !v.trim().isEmpty()) e.putString(key, v.trim());
            }
            for (String key : new String[]{"keep_screen_on", "ignore_ssl_errors", "vibrate", "wifi_qr"}) {
                String v = p.getProperty(key);
                if (v != null) e.putBoolean(key, Boolean.parseBoolean(v.trim()));
            }
            for (String key : new String[]{"nfc_mode", "nfc_uid_format", "nfc_keyboard_value", "scan_mode", "datawedge"}) {
                String v = p.getProperty(key);
                if (v != null && !v.trim().isEmpty()) e.putString(key, v.trim().toLowerCase());
            }
            String uu = p.getProperty("update_url");
            if (uu != null) e.putString("update_url", uu.trim());
            String au = p.getProperty("auto_update");
            if (au != null) e.putBoolean("auto_update", Boolean.parseBoolean(au.trim()));
            for (String key : new String[]{"nightly_reload"}) {
                String v = p.getProperty(key);
                if (v != null) e.putBoolean(key, Boolean.parseBoolean(v.trim()));
            }
            String ch = p.getProperty("update_channel");
            if (ch != null && !ch.trim().isEmpty()) e.putString("update_channel", ch.trim().toLowerCase());
            String or = p.getProperty("orientation");
            if (or != null && or.trim().matches("portrait|landscape|auto")) e.putString("orientation", or.trim());
            for (String key : new String[]{"nfc_refresh_seconds", "nfc_debounce_ms", "update_check_hours", "volume_lock", "dim_minutes", "dim_level", "nightly_hour", "low_battery"}) {
                String v = p.getProperty(key);
                if (v != null) try { e.putInt(key, Integer.parseInt(v.trim())); } catch (NumberFormatException ignored) {}
            }
            String ne = p.getProperty("nfc_send_enter");
            if (ne != null) e.putBoolean("nfc_send_enter", Boolean.parseBoolean(ne.trim()));
            String r = p.getProperty("reload_minutes");
            if (r != null) try { e.putInt("reload_minutes", Integer.parseInt(r.trim())); } catch (NumberFormatException ignored) {}
            e.apply();
            if (WifiProfiles.mentioned(p)) applyWifi(p);
            return;
        }
    }

    private String homeUrl() { return prefs.getString("url", DEFAULT_URL); }

    /** URL met {serial}/{model}/{version} ingevuld; eindigt de URL op "serial=" dan wordt het serienummer erachter gezet. */
    private String resolvedUrl() {
        String u = homeUrl();
        String sn = Uri.encode(serial());
        if (u.endsWith("serial=")) u = u + sn;
        return u.replace("{serial}", sn).replace("{model}", Uri.encode(Build.MODEL)).replace("{version}", Uri.encode(appVersion()));
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private String serial() {
        String s = null;
        if (Build.VERSION.SDK_INT >= 26) {
            try { s = (String) Build.class.getMethod("getSerial").invoke(null); } catch (Exception ignored) {}
        }
        if (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s)) s = Build.SERIAL;
        if (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s))
            s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return s == null ? "" : s;
    }

    private static final String[] HEALTH = {"", "unknown", "good", "overheat", "dead", "over_voltage", "failure", "cold"};

    private JSONObject batteryJson(Intent i) {
        JSONObject o = new JSONObject();
        if (i == null) return o;
        try {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1), plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 1);
            o.put("level", scale > 0 ? Math.round(level * 100f / scale) : level);   // procent 0-100
            o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
            o.put("full", status == BatteryManager.BATTERY_STATUS_FULL);
            o.put("plugged", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "ac" : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "usb"
                    : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : plugged == 0 ? "none" : String.valueOf(plugged));
            o.put("health", health > 0 && health < HEALTH.length ? HEALTH[health] : String.valueOf(health));
            o.put("temperature", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);   // graden C
            o.put("voltage", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0);          // volt
            o.put("technology", i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
            // Alle overige (o.a. Zebra-specifieke) gegevens, zoals serienummer accu, laadcycli, gezondheid %:
            JSONObject raw = new JSONObject();
            Bundle ex = i.getExtras();
            if (ex != null) for (String k : ex.keySet()) {
                Object v = ex.get(k);
                if (v instanceof Number || v instanceof Boolean || v instanceof String) raw.put(k, v);
            }
            o.put("raw", raw);
            JSONObject pack = batteryPack(ex);
            if (pack.length() > 0) o.put("pack", pack);
        } catch (Exception ignored) {}
        return o;
    }

    /**
     * De accu zelf (slijtage), uit de extra gegevens die Zebra aan ACTION_BATTERY_CHANGED toevoegt. Welke er zijn,
     * verschilt per toestel en firmware; alleen wat er is komt erin (leeg object: niets bekend, bv. geen Zebra).
     *   percent: gezondheid 0-100 · cycles: laadcycli · decommission: Zebra raadt vervangen aan ·
     *   serial, part_number, manufactured (datum), rated_capacity (mAh)
     */
    private static JSONObject batteryPack(Bundle ex) throws Exception {
        JSONObject p = new JSONObject();
        if (ex == null) return p;
        Integer health = intExtra(ex, "health_percentage", "battery_health_percentage", "health_percent");
        Integer rated = intExtra(ex, "ratedcapacity", "rated_capacity", "design_capacity");
        Integer present = intExtra(ex, "present_capacity", "full_charge_capacity");
        if (health == null && rated != null && present != null && rated > 0) health = Math.round(present * 100f / rated);
        if (health != null && health >= 0 && health <= 100) p.put("percent", health);
        Integer cycles = intExtra(ex, "cycle_count", "battery_cycle_count", "cycles");
        Integer cumulative = intExtra(ex, "total_cumulative_charge");
        if (cycles == null && cumulative != null && rated != null && rated > 0) cycles = cumulative / rated;
        if (cycles != null && cycles >= 0) p.put("cycles", cycles);
        Integer decom = intExtra(ex, "battery_decommission");
        if (decom != null && (decom == 0 || decom == 1)) p.put("decommission", decom == 1);
        String serial = strExtra(ex, "serialnumber", "battery_serial");
        if (serial != null) p.put("serial", serial);
        String part = strExtra(ex, "partnumber", "battery_partnumber");
        if (part != null) p.put("part_number", part);
        String mfd = strExtra(ex, "mfd", "manufacture_date");
        if (mfd != null) p.put("manufactured", mfd);
        if (rated != null && rated > 0) p.put("rated_capacity", rated);
        return p;
    }

    private static Integer intExtra(Bundle ex, String... keys) {
        for (String k : keys) {
            Object v = ex.get(k);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) try { return Integer.parseInt(((String) v).trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String strExtra(Bundle ex, String... keys) {
        for (String k : keys) {
            Object v = ex.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim();
        }
        return null;
    }
    private String pin() { return prefs.getString("pin", "1234"); }

    // ---------- laden ----------

    private void loadHome() {
        handler.removeCallbacks(retry);
        String url = homeUrl();
        if (url.isEmpty()) {
            web.loadDataWithBaseURL(null, page("Geen URL ingesteld",
                "Tik 10x snel op dezelfde plek en kies <b>Website-adres wijzigen</b>,<br>of plaats " + CONFIG_FILE + " op de interne opslag."),
                "text/html", "UTF-8", null);
        } else {
            web.loadUrl(resolvedUrl());
        }
    }

    private void showError(String desc) {
        if (pageError) return;
        KioskLog.add(this, "Pagina laadt niet: " + desc);
        pageError = true;
        web.loadDataWithBaseURL(null, page("Geen verbinding",
            "De pagina kon niet worden geladen (" + escape(desc) + ").<br>Opnieuw proberen over 10 seconden&hellip;"),
            "text/html", "UTF-8", null);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    private void schedulePeriodicReload() {
        handler.removeCallbacks(periodicReload);
        int min = prefs.getInt("reload_minutes", 0);
        if (min > 0) handler.postDelayed(periodicReload, min * 60000L);
    }

    /**
     * navigator.vibrate via de trilmotor van Android (WebKiosk.vibrate), zoals de EventVOX-scanterminal hem per uitslag
     * aanroept (js/scan-texts.js VIBRATE). De WebView zelf trilt alleen na een tik op de pagina en met de VIBRATE-
     * toestemming; een scan met DataWedge of NFC is geen tik, dus zonder dit zou er niets trillen. Elke waarde wordt
     * 0-10000 ms, hoogstens 32 stappen, zoals in Chrome.
     */
    private static final String VIBRATE_POLYFILL =
        "(function(){"
          + "if(!window.WebKiosk||typeof WebKiosk.vibrate!=='function'||window.__wkVibrate)return;window.__wkVibrate=1;"
          + "var vib=function(p){try{var a=Array.isArray(p)?p:[p];"
          + "a=a.slice(0,32).map(function(x){x=Number(x);return isFinite(x)&&x>0?Math.min(Math.round(x),10000):0;});"
          + "return WebKiosk.vibrate(JSON.stringify(a))!==false;}catch(e){return false;}};"
          + "try{Object.defineProperty(Navigator.prototype,'vibrate',{value:vib,configurable:true,writable:true});}catch(e){}"
          + "try{Object.defineProperty(navigator,'vibrate',{value:vib,configurable:true,writable:true});}catch(e){navigator.vibrate=vib;}"
          + "})();";

    /**
     * Battery Status API op basis van Android's BatteryManager (via WebKiosk.getBattery), ook als de WebView hem niet heeft.
     * Altijd deze (niet de eventuele eigen van de WebView): chargingTime blijft Infinity (onbekend), zodat een volle,
     * ladende accu niet lijkt op een toestel zonder accu (zo herkent de EventVOX-scanterminal een Raspberry Pi).
     * Wordt zo vroeg mogelijk in elke pagina gezet (onPageStarted/onPageCommitVisible) en nogmaals bij onPageFinished.
     */
    private static final String BATTERY_POLYFILL =
        "(function(){"
          + "if(!window.WebKiosk||window.__wkBattery)return;window.__wkBattery=1;"
          + "function rd(){try{return JSON.parse(WebKiosk.getBattery());}catch(e){return {level:100,charging:false};}}"
          + "var bm={level:1,charging:false,chargingTime:Infinity,dischargingTime:Infinity,"
          + "onlevelchange:null,onchargingchange:null,onchargingtimechange:null,ondischargingtimechange:null,_l:{},"
          + "addEventListener:function(t,f){(this._l[t]=this._l[t]||[]).push(f);},"
          + "removeEventListener:function(t,f){var a=this._l[t]||[];var i=a.indexOf(f);if(i>=0)a.splice(i,1);},"
          + "dispatchEvent:function(e){this._fire(e.type);return true;},"
          + "_fire:function(t){var e={type:t,target:bm,currentTarget:bm};(this._l[t]||[]).slice().forEach(function(f){try{f.call(bm,e);}catch(x){}});"
          + "if(typeof this['on'+t]==='function')try{this['on'+t](e);}catch(x){}}};"
          + "function upd(d){if(!d||typeof d.level!=='number')return;var l=d.level/100,c=!!d.charging,lc=l!==bm.level,cc=c!==bm.charging;"
          + "bm.level=l;bm.charging=c;if(lc)bm._fire('levelchange');if(cc){bm._fire('chargingchange');bm._fire('chargingtimechange');}}"
          + "upd(rd());"
          + "window.addEventListener('webkiosk-battery',function(e){upd(e.detail);});"
          + "var gb=function(){return Promise.resolve(bm);};"
          + "try{Object.defineProperty(Navigator.prototype,'getBattery',{value:gb,configurable:true,writable:true});}catch(e){}"
          + "try{Object.defineProperty(navigator,'getBattery',{value:gb,configurable:true,writable:true});}catch(e){navigator.getBattery=gb;}"
          + "})();";

    private static String page(String title, String body) {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='font-family:sans-serif;text-align:center;padding:40px 16px;color:#333;word-break:break-word;overflow-wrap:anywhere'>"
            + "<h2>" + title + "</h2><p>" + body + "</p></body></html>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- kiosk / device owner ----------

    private boolean isOwner() { return dpm != null && dpm.isDeviceOwnerApp(getPackageName()); }

    private void applyDeviceOwnerPolicies() {
        if (!isOwner()) {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            return;
        }
        try {
            dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, home, new ComponentName(this, MainActivity.class));
            if (Build.VERSION.SDK_INT >= 23) {
                dpm.setPermissionGrantState(admin, getPackageName(),
                    android.Manifest.permission.READ_EXTERNAL_STORAGE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                dpm.setPermissionGrantState(admin, getPackageName(),
                    android.Manifest.permission.READ_PHONE_STATE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                // de wifi-netwerknaam (SSID) geeft Android alleen met locatie-toestemming
                dpm.setPermissionGrantState(admin, getPackageName(),
                    android.Manifest.permission.ACCESS_COARSE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                // GPS voor "Zoek mij" (alleen dan wordt de locatie bepaald)
                dpm.setPermissionGrantState(admin, getPackageName(),
                    android.Manifest.permission.ACCESS_FINE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                dpm.setStatusBarDisabled(admin, true);
                dpm.setKeyguardDisabled(admin, true);
            }
        } catch (Exception ignored) {}
    }

    private void enterKiosk() {
        if (kioskSuspended || !isOwner()) return;
        try { startLockTask(); } catch (Exception ignored) {}
    }

    private void leaveKiosk() {
        kioskSuspended = true;
        try { if (isOwner()) dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME); } catch (Exception ignored) {}
        try { stopLockTask(); } catch (Exception ignored) {}
        if (isOwner() && Build.VERSION.SDK_INT >= 23) {
            try { dpm.setStatusBarDisabled(admin, false); } catch (Exception ignored) {}
        }
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) {}
    }

    private static final int IMMERSIVE =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
    }

    /*
     * Na het toetsenbord blijft op Android 6-8 de navigatiebalk (met terugknop) staan: het toetsenbord haalt de
     * immersive-vlaggen weg en Android zet ze niet terug. Daarom: zodra de balken weer zichtbaar worden, of het
     * toetsenbord dichtgaat, de balken opnieuw verbergen (niet zolang het toetsenbord open is: dan hoort de
     * balk er te zijn om het toetsenbord te kunnen sluiten).
     */
    private boolean keyboardShown = false;

    private final Runnable rehideBars = new Runnable() {
        public void run() {
            if (keyboardVisible()) { handler.postDelayed(this, 1000); return; }
            hideSystemUi();
        }
    };

    private boolean keyboardVisible() {
        View root = getWindow().getDecorView();
        android.graphics.Rect r = new android.graphics.Rect();
        root.getWindowVisibleDisplayFrame(r);
        int screen = root.getRootView().getHeight();
        return screen > 0 && screen - r.bottom > screen * 0.15;
    }

    private void watchSystemBars() {
        final View decor = getWindow().getDecorView();
        decor.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            public void onSystemUiVisibilityChange(int vis) {
                if ((vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0 || (vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    handler.removeCallbacks(rehideBars);
                    handler.postDelayed(rehideBars, 1500);
                }
            }
        });
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                boolean shown = keyboardVisible();
                if (keyboardShown && !shown) { // toetsenbord net dicht
                    handler.removeCallbacks(rehideBars);
                    handler.postDelayed(rehideBars, 300);
                }
                keyboardShown = shown;
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (kioskSuspended) { // terug vanuit instellingen: kiosk weer aan
            kioskSuspended = false;
            applyDeviceOwnerPolicies();
        }
        resumed = true;
        focusWeb();
        applyVolume();
        if (isOwner()) try { dpm.setAutoTimeRequired(admin, true); } catch (Exception ignored) {}
        hideSystemUi();
        enterKiosk();
        restartNfc("onResume");
        schedulePeriodicNfcRefresh();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(nfcRestart);
        handler.removeCallbacks(nfcPeriodic);
        if (nfc != null) try { nfc.disableReaderMode(this); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) { hideSystemUi(); restartNfc("focus"); focusWeb(); }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && i.getExtras() != null && (i.hasExtra("url") || i.hasExtra("pin"))) {
            readIntentConfig(i);
            loadHome();
        }
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        readFileConfig();
        loadHome();
    }

    @Override public void onBackPressed() {
        if (adminView != null) { adminEmit("back", new JSONObject()); return; }
        if (wifiView != null) { hideWifiView(); return; }
        if (web.canGoBack()) web.goBack();
        // anders: niets doen, app kan niet via terug-knop worden verlaten
    }

    // ---------- wifi-profielen ----------

    /** Wifi-profielen uit het config-bestand zetten (alleen als device owner; alleen bij een gewijzigd bestand). */
    private void applyWifi(final Properties p) {
        if (!isOwner()) {
            // stempel weg: bij de volgende start (als device owner) wordt het bestand opnieuw gelezen
            prefs.edit().putString("wifi_result", "niet gezet: app is geen device owner").remove("cfg_stamp").apply();
            return;
        }
        final java.util.List<WifiProfiles.Profile> list = WifiProfiles.parse(p);
        final boolean removeOthers = Boolean.parseBoolean(p.getProperty("wifi_remove_others", "false").trim());
        final boolean lockdown = Boolean.parseBoolean(p.getProperty("wifi_lockdown", "false").trim());
        new Thread(new Runnable() { public void run() {
            java.util.Set<String> previous = prefs.getStringSet("wifi_ssids", new java.util.HashSet<String>());
            java.util.Set<String> applied = new java.util.HashSet<>();
            String result;
            try { result = WifiProfiles.apply(MainActivity.this, list, previous, removeOthers, applied,
                prefs.getStringSet("wifi_qr_ssids", new java.util.HashSet<String>())); }
            catch (Exception ex) { result = "fout: " + ex.getMessage(); }
            WifiProfiles.lockdown(MainActivity.this, dpm, admin, lockdown);
            prefs.edit().putStringSet("wifi_ssids", applied)
                .putString("wifi_result", result + " (" + new java.text.SimpleDateFormat("d-M HH:mm").format(new java.util.Date()) + ")").apply();
        }}).start();
    }

    // ---------- NFC ----------

    private void startNfc() {
        if (nfc == null) return;
        if ("off".equals(prefs.getString("nfc_mode", "both")) && !nfcTesting) return;
        startNfcForced();
    }

    /*
     * Bekend Android-probleem: na slaapstand (scherm uit/aan) is reader mode soms niet meer actief,
     * omdat enableReaderMode() in onResume te vroeg komt (NFC-dienst ziet de app nog niet als voorgrond)
     * of omdat de NFC-chip na het ontwaken opnieuw wordt opgestart. Oplossing: reader mode opnieuw
     * registreren bij scherm-aan, ontgrendelen, venster-focus en NFC-aan, elk met een korte vertraging
     * en een tweede poging. Optioneel daarnaast periodiek verversen (nfc_refresh_seconds).
     */
    private boolean resumed = false;
    private String nfcRestartReason = "";
    private long nfcLastRestart = 0;
    private int nfcRestarts = 0;
    private final Runnable nfcRestart = new Runnable() {
        public void run() {
            if (!resumed || nfc == null) return;
            try { nfc.disableReaderMode(MainActivity.this); } catch (Exception ignored) {}
            startNfc();
            nfcLastRestart = System.currentTimeMillis();
            nfcRestarts++;
        }
    };
    private final Runnable nfcPeriodic = new Runnable() {
        public void run() {
            if (resumed && System.currentTimeMillis() - lastTagTime > 3000) restartNfc("periodiek");
            schedulePeriodicNfcRefresh();
        }
    };

    private void restartNfc(String reason) {
        if (nfc == null) return;
        nfcRestartReason = reason;
        handler.removeCallbacks(nfcRestart);
        handler.postDelayed(nfcRestart, 400);
        handler.postDelayed(nfcRestart, 1500); // tweede poging: NFC-chip is soms nog niet klaar
    }

    private void schedulePeriodicNfcRefresh() {
        handler.removeCallbacks(nfcPeriodic);
        int sec = prefs.getInt("nfc_refresh_seconds", 0);
        if (sec > 0) handler.postDelayed(nfcPeriodic, sec * 1000L);
    }

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(a)) {
                if (i.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, 0) == NfcAdapter.STATE_ON) restartNfc("NFC aan");
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                restartNfc("scherm aan");
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                restartNfc("ontgrendeld");
            }
        }
    };

    private long lastTagTime = 0;
    private String lastTagUid = "";

    private void startNfcForced() {
        if (nfc == null) return;
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_NFC_F
                  | NfcAdapter.FLAG_READER_NFC_V | NfcAdapter.FLAG_READER_NFC_BARCODE;
        try {
            nfc.enableReaderMode(this, new NfcAdapter.ReaderCallback() {
                public void onTagDiscovered(Tag tag) { handleTag(tag); }
            }, flags, null);
        } catch (Exception ignored) {}
    }

    /** Wordt op een achtergrondthread aangeroepen. */
    private void handleTag(Tag tag) {
        markActivity();
        final String uid = formatUid(tag.getId());
        long now = System.currentTimeMillis();
        boolean dup = uid.equals(lastTagUid) && now - lastTagTime < prefs.getInt("nfc_debounce_ms", 1500);
        lastTagTime = now;
        lastTagUid = uid;
        if (dup) return; // zelfde tag nog in het veld (bv. na herstart reader mode): niet dubbel doorgeven
        final JSONObject o = new JSONObject();
        final JSONArray records = new JSONArray();
        String firstText = null;
        try {
            o.put("uid", uid);
            o.put("uidHex", hex(tag.getId(), false));
            JSONArray techs = new JSONArray();
            for (String t : tag.getTechList()) techs.put(t.substring(t.lastIndexOf('.') + 1));
            o.put("techs", techs);
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                NdefMessage msg = ndef.getCachedNdefMessage();
                if (msg == null) { ndef.connect(); msg = ndef.getNdefMessage(); ndef.close(); }
                if (msg != null) for (NdefRecord rec : msg.getRecords()) {
                    JSONObject jr = new JSONObject();
                    String[] tv = decodeRecord(rec);
                    jr.put("type", tv[0]);
                    jr.put("value", tv[1]);
                    records.put(jr);
                    if (firstText == null && tv[1] != null && !tv[1].isEmpty()) firstText = tv[1];
                }
            }
            o.put("ndef", records);
        } catch (Exception ignored) {}
        final String kbValue = "ndef".equals(prefs.getString("nfc_keyboard_value", "uid")) && firstText != null ? firstText : uid;
        runOnUiThread(new Runnable() { public void run() { deliverNfc(o, kbValue); } });
    }

    private void deliverNfc(JSONObject o, String kbValue) {
        if (nfcTesting && adminView != null) { // test in het beheerscherm: niet aan de website doorgeven
            try { o.put("typed", kbValue); } catch (Exception ignored) {}
            adminEmit("nfc", o);
            return;
        }
        String mode = prefs.getString("nfc_mode", "both");
        if (mode.equals("event") || mode.equals("both")) {
            String js = "(function(d){try{window.dispatchEvent(new CustomEvent('webkiosk-nfc',{detail:d}));}catch(e){}"
                      + "if(typeof window.onWebKioskNfc==='function')window.onWebKioskNfc(d);})(" + o.toString() + ");";
            web.evaluateJavascript(js, null);
        }
        if (mode.equals("keyboard") || mode.equals("both")) typeText(kbValue, prefs.getBoolean("nfc_send_enter", true));
    }

    // ---------- wifi-sterkte ----------

    @SuppressWarnings("deprecation")
    private JSONObject wifiJson() {
        JSONObject o = new JSONObject();
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            boolean connected = ni != null && ni.isConnected() && info != null && info.getNetworkId() != -1;
            o.put("connected", connected);
            o.put("enabled", wm != null && wm.isWifiEnabled());
            if (connected) {
                int rssi = info.getRssi();
                o.put("rssi", rssi);
                o.put("level", android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5)); // 0-4
                o.put("link_speed", info.getLinkSpeed());
                o.put("frequency", info.getFrequency());
                String ssid = info.getSSID();
                // Zonder locatie-toestemming of met locatie uit geeft Android "<unknown ssid>"
                if (ssid != null && !ssid.contains("unknown ssid")) o.put("ssid", ssid.replaceAll("^\"|\"$", ""));
            }
        } catch (Exception ignored) {}
        return o;
    }

    private String lastWifiSig = "";
    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            JSONObject w = wifiJson();
            // Alleen bij een andere stand, niet bij elke dBm: anders tientallen events per minuut
            String sig = w.optBoolean("connected") + "|" + w.optInt("level", -1) + "|" + w.optString("ssid");
            if (web == null || sig.equals(lastWifiSig)) return;
            lastWifiSig = sig;
            KioskLog.add(MainActivity.this, w.optBoolean("connected") ? "Wifi verbonden" + (w.has("ssid") ? " met " + w.optString("ssid") : "")
                + ", niveau " + w.optInt("level") + " (" + w.optInt("rssi") + " dBm)" : "Wifi niet verbonden");
            web.evaluateJavascript("(function(d){try{window.dispatchEvent(new CustomEvent('webkiosk-wifi',{detail:d}));}catch(e){}"
                + "if(typeof window.onWebKioskWifi==='function')window.onWebKioskWifi(d);})(" + w + ");", null);
        }
    };

    // ---------- netwerk toevoegen (assets/wifi.html): wifi-QR-code of een netwerk in de buurt ----------

    /** wifi_qr=false: geen offline-balk en wifi-QR-codes negeren */
    private boolean wifiQrEnabled() { return prefs.getBoolean("wifi_qr", true); }

    private boolean isOnline() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private static final long OFFLINE_BANNER_MS = 30000;
    private long offlineSince = 0;
    private View offlineBanner;
    private WebView wifiView;
    private WifiProfiles.Profile pendingWifi;
    private String wifiTrySsid = "";
    private int wifiTryId = -1;
    private long wifiTryUntil = 0;
    private boolean wifiScanRegistered = false;

    /** Elke 5 s: na 30 s zonder netwerk een balk bovenaan; alleen de knop vangt tikken, de pagina blijft bruikbaar */
    private final Runnable offlineCheck = new Runnable() {
        public void run() {
            handler.postDelayed(this, 5000);
            boolean online = isOnline();
            long now = SystemClock.uptimeMillis();
            if (online) offlineSince = 0;
            else if (offlineSince == 0) offlineSince = now;
            boolean show = !online && wifiQrEnabled() && now - offlineSince >= OFFLINE_BANNER_MS && wifiView == null && adminView == null;
            if (show && offlineBanner == null) showOfflineBanner();
            else if (!show && offlineBanner != null) { bannerBox.removeView(offlineBanner); offlineBanner = null; }
        }
    };

    private void showOfflineBanner() {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#F25E2572"));
        int pad = Math.round(10 * dp);
        bar.setPadding(Math.round(16 * dp), pad, Math.round(10 * dp), pad);
        TextView text = new TextView(this);
        text.setText("Je bent offline");
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView button = new TextView(this);
        button.setText("Netwerk toevoegen");
        button.setTextColor(Color.parseColor("#5e2572"));
        button.setTextSize(15);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setPadding(Math.round(14 * dp), Math.round(8 * dp), Math.round(14 * dp), Math.round(8 * dp));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(10 * dp);
        button.setBackground(bg);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pendingWifi = null; showWifiView(); }
        });
        bar.addView(button);
        // De balk zelf laat tikken door (niet clickable); alleen de knop reageert
        bar.setClickable(false);
        offlineBanner = bar;
        bannerBox.addView(bar);
    }

    /**
     * Een gescande wifi-QR-code: met het netwerkscherm open daar tonen; in het beheerscherm meteen toevoegen; zonder
     * netwerk het netwerkscherm openen; met netwerk niets (een vreemde code mag een werkend toestel niet omzetten).
     */
    private void handleWifiQr(String data) {
        if (!wifiQrEnabled()) return;
        WifiProfiles.Profile w = WifiProfiles.parseQr(data);
        if (wifiView != null) {
            if (w == null) { wifiEmit("failed", "Geen geldige wifi-QR-code"); return; }
            pendingWifi = w;
            wifiView.evaluateJavascript("window.wifiEvent&&window.wifiEvent({type:'qr',qr:" + qrJson(w) + "})", null);
            return;
        }
        if (adminView != null) {
            if (w == null) { adminToast("Geen geldige wifi-QR-code"); return; }
            pendingWifi = w;
            tryProfile(w, true);
            return;
        }
        if (w == null) { toast("Geen geldige wifi-QR-code"); return; }
        if (isOnline()) { toast("Er is al netwerk. Een wifi-netwerk toevoegen kan via het beheerscherm."); return; }
        pendingWifi = w;
        showWifiView();
    }

    private static JSONObject qrJson(WifiProfiles.Profile w) {
        JSONObject o = new JSONObject();
        try {
            o.put("ssid", w.ssid);
            o.put("security", w.security.equals("OPEN") ? "Open (geen wachtwoord)" : w.security.equals("WEP") ? "WEP" : "WPA/WPA2");
            o.put("hidden", w.hidden);
        } catch (Exception ignored) {}
        return o;
    }

    private void showWifiView() {
        if (wifiView != null) return;
        if (offlineBanner != null) { bannerBox.removeView(offlineBanner); offlineBanner = null; }
        wifiView = new WebView(this);
        wifiView.setBackgroundColor(Color.parseColor("#5e2572"));
        wifiView.getSettings().setJavaScriptEnabled(true);
        wifiView.getSettings().setAllowFileAccess(true);
        wifiView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !url.startsWith("file:///android_asset/"); }
        });
        wifiView.addJavascriptInterface(new WifiBridge(), "WifiQr");
        wifiView.loadUrl("file:///android_asset/wifi.html");
        root.addView(wifiView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        IntentFilter f = new IntentFilter(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, f);
        wifiScanRegistered = true;
    }

    private void hideWifiView() {
        handler.removeCallbacks(wifiWait);
        if (wifiScanRegistered) { try { unregisterReceiver(wifiScanReceiver); } catch (Exception ignored) {} wifiScanRegistered = false; }
        if (wifiView == null) return;
        root.removeView(wifiView);
        wifiView.destroy();
        wifiView = null;
        pendingWifi = null;
        hideSystemUi();
        focusWeb();
    }

    private void wifiEmit(String state, String text) {
        if (!"connecting".equals(state)) KioskLog.add(this, "Wifi: " + text);
        JSONObject d = new JSONObject();
        try { d.put("type", "status"); d.put("state", state); d.put("text", text); } catch (Exception ignored) {}
        if (wifiView != null) wifiView.evaluateJavascript("window.wifiEvent&&window.wifiEvent(" + d + ")", null);
        if (adminView != null) {
            if (!"connecting".equals(state)) adminToast(text);
            adminEmit("refresh", new JSONObject());
        }
    }

    // --- netwerken in de buurt ---

    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { emitNetworks(); }
    };

    /** Locatie aan (als device owner): zonder locatie geeft Android 8 geen netwerken in de buurt */
    @SuppressWarnings("deprecation")
    private boolean locationOn() {
        try {
            int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 0);
            if (mode != Settings.Secure.LOCATION_MODE_OFF) return true;
            if (isOwner()) {
                dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, String.valueOf(Settings.Secure.LOCATION_MODE_BATTERY_SAVING));
                return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void startNetworkScan() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null) return;
        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
        locationOn();
        try { wm.startScan(); } catch (Exception ignored) {}
        emitNetworks(); // wat er al bekend is, meteen
    }

    @SuppressWarnings("deprecation")
    private void emitNetworks() {
        if (wifiView == null) return;
        JSONObject d = new JSONObject();
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            java.util.Set<String> saved = WifiProfiles.savedSsids(this);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            String current = info == null || info.getSSID() == null ? "" : info.getSSID().replaceAll("^\"|\"$", "");
            java.util.Map<String, android.net.wifi.ScanResult> best = new java.util.LinkedHashMap<>();
            java.util.List<android.net.wifi.ScanResult> results = wm == null ? null : wm.getScanResults();
            if (results != null) for (android.net.wifi.ScanResult r : results) {
                if (r.SSID == null || r.SSID.trim().isEmpty()) continue;
                android.net.wifi.ScanResult b = best.get(r.SSID);
                if (b == null || r.level > b.level) best.put(r.SSID, r);
            }
            java.util.List<android.net.wifi.ScanResult> sorted = new java.util.ArrayList<>(best.values());
            java.util.Collections.sort(sorted, new java.util.Comparator<android.net.wifi.ScanResult>() {
                public int compare(android.net.wifi.ScanResult a, android.net.wifi.ScanResult b) { return b.level - a.level; }
            });
            JSONArray list = new JSONArray();
            for (android.net.wifi.ScanResult r : sorted) {
                JSONObject n = new JSONObject();
                n.put("ssid", r.SSID);
                n.put("security", WifiProfiles.securityOf(r.capabilities));
                n.put("level", android.net.wifi.WifiManager.calculateSignalLevel(r.level, 5));
                n.put("rssi", r.level);
                n.put("band", r.frequency >= 5000 ? "5 GHz" : "2,4 GHz");
                n.put("saved", saved.contains(r.SSID));
                n.put("current", r.SSID.equals(current) && isOnline());
                list.put(n);
            }
            d.put("type", "networks");
            d.put("list", list);
            d.put("location", Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF);
        } catch (Exception e) {
            try { d.put("type", "networks"); d.put("list", new JSONArray()); d.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        wifiView.evaluateJavascript("window.wifiEvent&&window.wifiEvent(" + d + ")", null);
    }

    // --- verbinden ---

    /** Een profiel toevoegen (QR-code of gekozen netwerk met wachtwoord) en tot 60 s wachten op verbinding */
    private void tryProfile(WifiProfiles.Profile w, boolean fromAdmin) {
        int id = WifiProfiles.addAndConnect(this, w);
        if (id == -1) { wifiEmit("failed", "Toevoegen van " + w.ssid + " lukte niet"); return; }
        java.util.Set<String> kept = new java.util.HashSet<>(prefs.getStringSet("wifi_qr_ssids", new java.util.HashSet<String>()));
        kept.add(w.ssid);
        prefs.edit().putStringSet("wifi_qr_ssids", kept).apply();
        waitFor(id, w.ssid, (fromAdmin ? w.ssid + " toegevoegd, " : "") + "verbinden met " + w.ssid + "…");
    }

    private void waitFor(int id, String ssid, String text) {
        wifiTryId = id;
        wifiTrySsid = ssid;
        wifiTryUntil = SystemClock.uptimeMillis() + 60000;
        wifiEmit("connecting", text);
        handler.removeCallbacks(wifiWait);
        handler.postDelayed(wifiWait, 1000);
    }

    private final Runnable wifiWait = new Runnable() {
        @SuppressWarnings("deprecation")
        public void run() {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            boolean ok = info != null && info.getNetworkId() == wifiTryId && isOnline();
            if (ok) {
                WifiProfiles.enableAll(MainActivity.this);
                wifiEmit("connected", "Verbonden met " + wifiTrySsid);
                if (wifiView != null) handler.postDelayed(new Runnable() { public void run() { hideWifiView(); loadHome(); } }, 2500);
                return;
            }
            if (SystemClock.uptimeMillis() > wifiTryUntil) {
                WifiProfiles.enableAll(MainActivity.this);
                wifiEmit("failed", "Geen verbinding met " + wifiTrySsid + " binnen 60 s. Klopt het wachtwoord en is het netwerk in de buurt?");
                return;
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** Wordt door wifi.html aangeroepen */
    private class WifiBridge {
        /** { qr: { ssid, security, hidden } of null, offline } */
        @JavascriptInterface public String info() {
            JSONObject o = new JSONObject();
            try {
                o.put("qr", pendingWifi == null ? JSONObject.NULL : qrJson(pendingWifi));
                o.put("offline", !isOnline());
            } catch (Exception ignored) {}
            return o.toString();
        }
        @JavascriptInterface public void scan() { runOnUiThread(new Runnable() { public void run() { startNetworkScan(); } }); }
        /** De gescande wifi-QR-code toevoegen */
        @JavascriptInterface public void add() {
            runOnUiThread(new Runnable() { public void run() { if (pendingWifi != null) tryProfile(pendingWifi, false); } });
        }
        /** Een netwerk uit de lijst: opgeslagen en zonder wachtwoord verbinden, anders toevoegen met het wachtwoord */
        @JavascriptInterface public void connect(final String ssid, final String security, final String password) {
            runOnUiThread(new Runnable() { public void run() {
                if (ssid == null || ssid.isEmpty()) return;
                if ("EAP".equals(security)) { wifiEmit("failed", "Bedrijfsnetwerken (met gebruikersnaam) worden niet ondersteund"); return; }
                if ((password == null || password.isEmpty()) && WifiProfiles.savedSsids(MainActivity.this).contains(ssid)) {
                    int id = WifiProfiles.connectSaved(MainActivity.this, ssid);
                    if (id == -1) { wifiEmit("failed", "Verbinden met " + ssid + " lukte niet"); return; }
                    waitFor(id, ssid, "Verbinden met " + ssid + "…");
                    return;
                }
                WifiProfiles.Profile w = new WifiProfiles.Profile();
                w.ssid = ssid;
                w.password = password == null ? "" : password;
                w.security = "OPEN".equals(security) ? "OPEN" : "WEP".equals(security) ? "WEP" : "WPA";
                if (w.security.equals("WPA") && (w.password.length() < 8 || w.password.length() > 63)) {
                    wifiEmit("failed", "Een WPA-wachtwoord heeft 8 tot 63 tekens");
                    return;
                }
                tryProfile(w, false);
            }});
        }
        @JavascriptInterface public void close() { runOnUiThread(new Runnable() { public void run() { hideWifiView(); } }); }
    }

    // ---------- herstel na een crash van de pagina ----------

    private int renderCrashes = 0;
    private long lastCrashAt = 0;
    private final java.util.LinkedList<Long> recentCrashes = new java.util.LinkedList<>();

    /** Een nieuwe WebView in plaats van de oude (na een crash, of 's nachts om geheugen vrij te maken) en de website laden */
    private void rebuildWeb(String reason, boolean crash) {
        KioskLog.add(this, (crash ? "Herstel: " : "Herladen: ") + reason);
        long now = System.currentTimeMillis();
        if (crash) {
            renderCrashes++;
            lastCrashAt = now;
            recentCrashes.add(now);
            while (!recentCrashes.isEmpty() && now - recentCrashes.getFirst() > 60000) recentCrashes.removeFirst();
        }
        WebView old = web;
        web = createWeb();
        int index = old != null ? root.indexOfChild(old) : 0;
        if (old != null) { root.removeView(old); try { old.destroy(); } catch (Exception ignored) {} }
        root.addView(web, Math.max(0, index), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        showSplash();
        focusWeb();
        // Drie crashes binnen een minuut: even wachten, anders blijft het rondgaan
        if (crash && recentCrashes.size() >= 3) {
            KioskLog.add(this, "Herstel: 3 crashes binnen een minuut, 30 s wachten");
            handler.postDelayed(new Runnable() { public void run() { loadHome(); } }, 30000);
        } else {
            loadHome();
        }
    }

    // ---------- terminal-identiteit bewaren (token van de EventVOX-scanterminal) ----------

    /*
     * De scanterminal bewaart zijn token in localStorage. Gaat dat verloren (cache gewist, beschadigde opslag), dan zou
     * hij zich als nieuwe terminal aanmelden. De app bewaart daarom een kopie (versleuteld in de Android Keystore,
     * samen met het adres) en zet die terug als het token ontbreekt, waarna de pagina één keer herlaadt. Verdwijnt een
     * teruggezet token direct weer (EventVOX heeft de terminal ingetrokken en de pagina wist het), dan gaat de kopie
     * weg: de terminal koppelt dan gewoon opnieuw, en de app bewaart het nieuwe token.
     */
    private static final String TERMINAL_TOKEN_KEY = "eventvox.terminal.token";
    private long tokenRestoredAt = 0;

    private static String originOf(String url) {
        try { java.net.URL u = new java.net.URL(url); return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""); }
        catch (Exception e) { return null; }
    }

    private void checkTerminalToken(final WebView v, final String url) {
        if (url == null || !url.startsWith("http") || !url.contains("/scanterminal")) return;
        final String origin = originOf(url);
        if (origin == null) return;
        v.evaluateJavascript("(function(){try{return localStorage.getItem('" + TERMINAL_TOKEN_KEY + "')||''}catch(e){return null}})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String raw) {
                    if (raw == null || raw.equals("null") || v != web) return; // geen opslag, of al een andere WebView
                    String token;
                    try { token = new JSONArray("[" + raw + "]").getString(0); } catch (Exception e) { return; }
                    String saved = TokenVault.get(prefs, "terminal_token");
                    String savedOrigin = saved == null ? null : saved.substring(0, Math.max(0, saved.indexOf('\n')));
                    String savedToken = saved == null ? null : saved.substring(saved.indexOf('\n') + 1);
                    if (!token.isEmpty()) {
                        if (!token.equals(savedToken) || !origin.equals(savedOrigin)) {
                            TokenVault.put(prefs, "terminal_token", origin + "\n" + token);
                            KioskLog.add(MainActivity.this, savedToken == null ? "Terminal-identiteit bewaard" : "Terminal-identiteit bijgewerkt (nieuw gekoppeld)");
                        }
                        return;
                    }
                    if (savedToken == null || !origin.equals(savedOrigin)) return;
                    if (tokenRestoredAt > 0 && System.currentTimeMillis() - tokenRestoredAt < 600000) {
                        // Net teruggezet en weer weg: EventVOX heeft de terminal ingetrokken; niet blijven terugzetten
                        TokenVault.put(prefs, "terminal_token", null);
                        tokenRestoredAt = 0;
                        KioskLog.add(MainActivity.this, "Terminal-identiteit vervallen (ingetrokken in EventVOX): opnieuw koppelen");
                        return;
                    }
                    tokenRestoredAt = System.currentTimeMillis();
                    KioskLog.add(MainActivity.this, "Terminal-identiteit teruggezet na verlies van de opslag");
                    v.evaluateJavascript("try{localStorage.setItem('" + TERMINAL_TOKEN_KEY + "'," + JSONObject.quote(savedToken) + ")}catch(e){}",
                        new ValueCallback<String>() { public void onReceiveValue(String ignored) { v.reload(); } });
                }
            });
    }

    // ---------- 's nachts herladen ----------

    /** Elke minuut: na nightly_hour (standaard 4 uur) één keer per nacht een verse WebView, als het toestel 5 min rustig is */
    private final Runnable nightlyCheck = new Runnable() {
        public void run() {
            handler.postDelayed(this, 60000);
            if (!prefs.getBoolean("nightly_reload", true)) return;
            java.util.Calendar c = java.util.Calendar.getInstance();
            int hour = prefs.getInt("nightly_hour", 4);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(c.getTime());
            if (today.equals(prefs.getString("nightly_done", ""))) return;
            int h = c.get(java.util.Calendar.HOUR_OF_DAY);
            if (h < hour || h >= hour + 3) return; // alleen in de 3 uur erna; daarna wachten tot de volgende nacht
            if (SystemClock.uptimeMillis() - lastActivity < 300000 || adminView != null || wifiView != null) return;
            prefs.edit().putString("nightly_done", today).apply();
            rebuildWeb("nachtelijk herladen", false);
        }
    };

    // ---------- volume vast ----------

    /** volume_lock: percentage (standaard 80, 0 = uit) voor het geluid van de pagina; de volumeknoppen doen dan niets */
    @SuppressWarnings("deprecation")
    private void applyVolume() {
        int pct = prefs.getInt("volume_lock", 80);
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (isOwner()) dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME);
            if (pct <= 0 || am == null) return;
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.max(1, Math.round(max * Math.min(100, pct) / 100f)), 0);
            if (isOwner()) dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME);
        } catch (Exception ignored) {}
    }

    private boolean volumeLocked() { return prefs.getInt("volume_lock", 80) > 0; }

    // ---------- scherm dimmen ----------

    private long lastActivity = SystemClock.uptimeMillis();
    private boolean dimmed = false;

    /** Aanraking, toets, scan of NFC: het toestel wordt gebruikt (fel scherm, geen update of nachtelijk herladen) */
    private void markActivity() {
        lastActivity = SystemClock.uptimeMillis();
        lastTouch = lastActivity;
        if (dimmed) runOnUiThread(new Runnable() { public void run() { setBrightness(-1f); } });
    }

    private void setBrightness(float level) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = level; // -1: zoals Android hem heeft
        getWindow().setAttributes(lp);
        dimmed = level >= 0;
    }

    /** Alleen met "scherm altijd aan" (anders gaat het scherm vanzelf uit): na dim_minutes zonder gebruik dimmen */
    private final Runnable dimCheck = new Runnable() {
        public void run() {
            handler.postDelayed(this, 15000);
            int minutes = prefs.getInt("dim_minutes", 2);
            boolean want = prefs.getBoolean("keep_screen_on", false) && minutes > 0 && adminView == null && wifiView == null && locateView == null
                && SystemClock.uptimeMillis() - lastActivity > minutes * 60000L;
            if (want && !dimmed) setBrightness(Math.max(1, Math.min(100, prefs.getInt("dim_level", 15))) / 100f);
            else if (!want && dimmed) setBrightness(-1f);
        }
    };

    // ---------- status en diagnose voor EventVOX ----------

    private volatile boolean diagnosticsPending = false;
    private final long startedAt = System.currentTimeMillis();

    private static String iso(long ms) {
        if (ms <= 0) return null;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT);
        return f.format(new java.util.Date(ms));
    }

    private JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", appVersion());
            o.put("channel", prefs.getString("update_channel", "stable"));
            o.put("uptime_s", (System.currentTimeMillis() - startedAt) / 1000);
            o.put("crashes", renderCrashes);
            if (lastCrashAt > 0) o.put("last_crash_at", iso(lastCrashAt));
            o.put("nfc_restarts", nfcRestarts);
            if (clockCheckedAt > 0) o.put("clock_skew_s", clockSkew);
            o.put("settings_version", prefs.getInt("remote_settings_version", 0));
            o.put("command_done", prefs.getInt("last_command_id", 0));
            o.put("orientation", prefs.getString("orientation", "portrait"));
            if (foundLocation != null && System.currentTimeMillis() - foundLocationAt < LOCATION_SEND_MS) o.put("location", foundLocation);
            JSONObject w = wifiJson();
            w.remove("enabled");
            o.put("wifi", w);
            JSONObject u = new JSONObject();
            long checked = prefs.getLong("update_last_check", 0);
            if (checked > 0) u.put("checked_at", iso(checked));
            u.put("result", prefs.getString("update_last_result", ""));
            o.put("update", u);
            if (diagnosticsPending) {
                diagnosticsPending = false;
                o.put("log", KioskLog.text(this));
                prefs.edit().putLong("diagnostics_sent", System.currentTimeMillis()).apply();
            }
        } catch (Exception ignored) {}
        return o;
    }

    // ---------- meldingen bovenaan: accu en klok ----------

    private LinearLayout bannerBox;
    private TextView batteryBanner, clockBanner;

    private TextView banner(String text, String color) {
        TextView t = new TextView(this);
        float dp = getResources().getDisplayMetrics().density;
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Math.round(12 * dp), Math.round(10 * dp), Math.round(12 * dp), Math.round(10 * dp));
        t.setBackgroundColor(Color.parseColor(color));
        t.setClickable(false);
        return t;
    }

    /** low_battery (standaard 15%, 0 = uit): onder dat percentage en niet aan de lader een rode balk */
    private void updateBatteryBanner(JSONObject b) {
        int limit = prefs.getInt("low_battery", 15);
        int level = b.optInt("level", 100);
        boolean show = limit > 0 && !b.optBoolean("charging") && level <= limit && bannerBox != null;
        if (show) {
            String text = "Accu bijna leeg (" + level + "%) · leg me aan de lader";
            if (batteryBanner == null) {
                batteryBanner = banner(text, "#F2C62828");
                bannerBox.addView(batteryBanner, 0);
                KioskLog.add(this, "Accu bijna leeg: " + level + "%");
            } else batteryBanner.setText(text);
        } else if (batteryBanner != null) {
            bannerBox.removeView(batteryBanner);
            batteryBanner = null;
        }
    }

    // ---------- klokcontrole ----------

    /*
     * De scanterminal beoordeelt offline scans op de klok van het toestel. Als device owner moet Android de automatische
     * tijd aanhouden (setAutoTimeRequired); daarnaast vergelijkt de app elke 15 minuten de klok met de Date-header van de
     * server van de website. Meer dan CLOCK_WARN_S verschil: een balk, een regel in het logboek en clock_skew_s naar EventVOX.
     */
    private static final int CLOCK_WARN_S = 120;
    private volatile int clockSkew = 0;
    private volatile long clockCheckedAt = 0;

    private static String skewText(int skew) {
        int min = Math.round(Math.abs(skew) / 60f);
        String amount = min >= 120 ? Math.round(min / 60f) + " uur" : Math.max(1, min) + " min";
        return "loopt " + amount + (skew > 0 ? " voor" : " achter");
    }

    private final Runnable clockCheck = new Runnable() {
        public void run() {
            handler.postDelayed(this, 15 * 60000L);
            final String url = resolvedUrl();
            if (!url.startsWith("http") || !isOnline()) return;
            new Thread(new Runnable() { public void run() {
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    con.setRequestMethod("HEAD");
                    con.setConnectTimeout(10000);
                    con.setReadTimeout(10000);
                    con.setUseCaches(false);
                    long before = System.currentTimeMillis();
                    con.getResponseCode();
                    long after = System.currentTimeMillis();
                    long server = con.getHeaderFieldDate("Date", 0);
                    con.disconnect();
                    if (server <= 0) return;
                    // De Date-header is op de seconde; het midden van het verzoek als moment
                    final int skew = (int) Math.round(((before + after) / 2.0 - server) / 1000.0);
                    final boolean wasOff = Math.abs(clockSkew) >= CLOCK_WARN_S && clockCheckedAt > 0;
                    clockSkew = skew;
                    clockCheckedAt = System.currentTimeMillis();
                    runOnUiThread(new Runnable() { public void run() { showClock(skew, wasOff); } });
                } catch (Exception ignored) {}
            }}).start();
        }
    };

    private void showClock(int skew, boolean wasOff) {
        boolean off = Math.abs(skew) >= CLOCK_WARN_S;
        if (off && !wasOff) KioskLog.add(this, "Klok " + skewText(skew) + " op de server");
        if (!off && wasOff) KioskLog.add(this, "Klok loopt weer gelijk");
        if (off) {
            String text = "De klok van dit toestel " + skewText(skew) + " · offline scans kunnen verkeerd gaan";
            if (clockBanner == null) { clockBanner = banner(text, "#F2E65100"); bannerBox.addView(clockBanner, batteryBanner == null ? 0 : 1); }
            else clockBanner.setText(text);
        } else if (clockBanner != null) {
            bannerBox.removeView(clockBanner);
            clockBanner = null;
        }
    }

    // ---------- schermstand en laadscherm ----------

    /** orientation: portrait (standaard), landscape of auto (draait mee) */
    private void applyOrientation() {
        String o = prefs.getString("orientation", "portrait");
        setRequestedOrientation("auto".equals(o) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            : "landscape".equals(o) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private View splash;
    private final Runnable splashTimeout = new Runnable() { public void run() { hideSplash(); } };

    /** Het EventVOX-logo tot de pagina geladen is (hoogstens 20 s), in plaats van een wit scherm */
    private void showSplash() {
        if (splash != null) return;
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);
        box.setClickable(true); // de pagina eronder is nog niet klaar
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()));
        box.addView(logo, new LinearLayout.LayoutParams(Math.round(96 * dp), Math.round(96 * dp)));
        TextView name = new TextView(this);
        name.setText("EventVOX");
        name.setTextColor(Color.parseColor("#5e2572"));
        name.setTextSize(22);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Math.round(12 * dp), 0, Math.round(18 * dp));
        box.addView(name);
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        box.addView(bar, new LinearLayout.LayoutParams(Math.round(32 * dp), Math.round(32 * dp)));
        splash = box;
        int index = web == null ? 0 : root.indexOfChild(web) + 1;
        root.addView(box, index, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        handler.removeCallbacks(splashTimeout);
        handler.postDelayed(splashTimeout, 20000);
    }

    private void hideSplash() {
        handler.removeCallbacks(splashTimeout);
        final View s = splash;
        if (s == null) return;
        splash = null;
        s.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() { public void run() { root.removeView(s); } });
    }

    // ---------- bediening vanuit EventVOX: opdrachten en instellingen ----------

    private void runCommand(String json) {
        int id;
        String command;
        try { JSONObject o = new JSONObject(json); id = o.getInt("id"); command = o.getString("command"); } catch (Exception e) { return; }
        if (id <= prefs.getInt("last_command_id", 0)) return; // al uitgevoerd
        prefs.edit().putInt("last_command_id", id).commit(); // eerst opslaan: restart/update beëindigen de app
        KioskLog.add(this, "Opdracht uit EventVOX: " + command);
        switch (command) {
            case "reload": rebuildWeb("opdracht uit EventVOX", false); break;
            case "restart": restartApp(); break;
            case "update": updateNow(); break;
            case "locate": locate(); break;
            default: KioskLog.add(this, "Onbekende opdracht: " + command);
        }
    }

    /** De app helemaal opnieuw starten (na 1,5 s, via de AlarmManager) */
    private void restartApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 1, i, android.app.PendingIntent.FLAG_CANCEL_CURRENT);
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi);
        try { stopLockTask(); } catch (Exception ignored) {}
        finishAffinity();
        handler.postDelayed(new Runnable() { public void run() { android.os.Process.killProcess(android.os.Process.myPid()); } }, 300);
    }

    /** Nu bijwerken: niet wachten tot het toestel rustig is */
    private void updateNow() {
        if (updateBusy) return;
        final String url = updateUrl();
        new Thread(new Runnable() { public void run() {
            try {
                final Updater.Info info = Updater.fetch(url);
                if (info.versionCode > Updater.currentVersionCode(MainActivity.this)) {
                    runOnUiThread(new Runnable() { public void run() { downloadAndInstall(info, false); } });
                } else {
                    KioskLog.add(MainActivity.this, "Nu bijwerken: al de nieuwste versie (" + appVersion() + ")");
                }
            } catch (Exception e) {
                KioskLog.add(MainActivity.this, "Nu bijwerken mislukt: " + e.getMessage());
            }
        }}).start();
    }

    /** Instellingen uit EventVOX: elke versie één keer toepassen (met de controles van het beheerscherm) */
    private void applyRemoteSettings(String json) {
        try {
            JSONObject o = new JSONObject(json);
            int version = o.getInt("version");
            if (version == prefs.getInt("remote_settings_version", 0)) return;
            JSONObject values = o.optJSONObject("values");
            java.util.List<String> applied = new java.util.ArrayList<>();
            if (values != null) {
                java.util.Iterator<String> keys = values.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (key.equals("url") || key.equals("pin") || key.equals("update_url")) continue; // alleen op het toestel
                    String err = applySetting(key, String.valueOf(values.get(key)));
                    if (err == null) applied.add(key); else KioskLog.add(this, "Instelling uit EventVOX geweigerd: " + key + " (" + err + ")");
                }
            }
            java.util.Collections.sort(applied);
            prefs.edit().putInt("remote_settings_version", version)
                .putString("remote_settings_keys", android.text.TextUtils.join(", ", applied)).apply();
            KioskLog.add(this, applied.isEmpty() ? "Instellingen uit EventVOX: weer die van het toestel" : "Instellingen uit EventVOX toegepast: " + android.text.TextUtils.join(", ", applied));
            if (adminView != null) adminEmit("refresh", new JSONObject());
        } catch (Exception ignored) {}
    }

    // ---------- Zoek mij ----------

    private View locateView;
    private android.media.ToneGenerator locateTone;
    private final Runnable locateStep = new Runnable() {
        int n = 0;
        public void run() {
            if (locateView == null) { n = 0; return; }
            if (n++ >= 30) { n = 0; stopLocate(); return; }
            locateView.setBackgroundColor(n % 2 == 0 ? Color.parseColor("#5e2572") : Color.parseColor("#ff6a00"));
            try { if (locateTone != null) locateTone.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600); } catch (Exception ignored) {}
            doVibrate("[500]");
            handler.postDelayed(this, 1000);
        }
    };

    /** 30 s piepen, trillen en knipperen (fel scherm); een tik stopt het */
    private void locate() {
        if (locateView != null) return;
        markActivity();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#5e2572"));
        TextView t = new TextView(this);
        t.setText("Hier ben ik!\n\nTik om te stoppen");
        t.setTextColor(Color.WHITE);
        t.setTextSize(30);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(t);
        box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stopLocate(); } });
        locateView = box;
        root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setBrightness(1f);
        try { locateTone = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100); } catch (Exception e) { locateTone = null; }
        handler.post(locateStep);
        findLocation();
    }

    private void stopLocate() {
        handler.removeCallbacks(locateStep);
        if (locateTone != null) { try { locateTone.release(); } catch (Exception ignored) {} locateTone = null; }
        if (locateView != null) { root.removeView(locateView); locateView = null; }
        setBrightness(-1f);
        focusWeb();
    }

    // ---------- locatie bij Zoek mij ----------

    /*
     * Alleen na de opdracht "Zoek mij": hoogstens 60 s een positie zoeken (GPS, en het netwerk als dat er is), plus het
     * wifi-toegangspunt. Gaat als kiosk_status.location mee met de heartbeats van de 10 minuten erna; EventVOX bewaart
     * alleen de laatste en wist hem na 24 uur. Er wordt nooit zomaar een locatie vastgelegd (het toestel wordt door een
     * persoon gedragen). Het logboek krijgt geen coördinaten.
     */
    private static final long LOCATION_SEARCH_MS = 60000, LOCATION_SEND_MS = 600000;
    private volatile JSONObject foundLocation;
    private volatile long foundLocationAt = 0;
    private android.location.Location bestLocation;
    private android.location.LocationListener locationListener;

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void findLocation() {
        if (locationListener != null) return; // al bezig
        final android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        try {
            if (isOwner()) dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, String.valueOf(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));
        } catch (Exception ignored) {}
        bestLocation = null;
        // Een recente bekende positie als begin
        for (String provider : new String[]{android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER}) {
            try {
                android.location.Location last = lm.getLastKnownLocation(provider);
                if (last != null && System.currentTimeMillis() - last.getTime() < 120000) better(last);
            } catch (Exception ignored) {}
        }
        locationListener = new android.location.LocationListener() {
            public void onLocationChanged(android.location.Location l) {
                better(l);
                if (bestLocation != null && bestLocation.hasAccuracy() && bestLocation.getAccuracy() <= 20) finishLocation(lm);
            }
            public void onStatusChanged(String p, int s, Bundle b) {}
            public void onProviderEnabled(String p) {}
            public void onProviderDisabled(String p) {}
        };
        boolean any = false;
        for (String provider : new String[]{android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER}) {
            try {
                if (lm.isProviderEnabled(provider)) { lm.requestLocationUpdates(provider, 1000, 0, locationListener, Looper.getMainLooper()); any = true; }
            } catch (Exception ignored) {}
        }
        KioskLog.add(this, any ? "Zoek mij: locatie zoeken" : "Zoek mij: geen locatiebron (locatie uit op het toestel?)");
        handler.postDelayed(new Runnable() { public void run() { finishLocation(lm); } }, any ? LOCATION_SEARCH_MS : 0);
    }

    private void better(android.location.Location l) {
        if (l == null) return;
        if (bestLocation == null || (l.hasAccuracy() && (!bestLocation.hasAccuracy() || l.getAccuracy() < bestLocation.getAccuracy()))) bestLocation = l;
    }

    @SuppressWarnings("deprecation")
    private void finishLocation(android.location.LocationManager lm) {
        if (locationListener == null) return;
        try { lm.removeUpdates(locationListener); } catch (Exception ignored) {}
        locationListener = null;
        android.location.Location l = bestLocation;
        if (l == null) { KioskLog.add(this, "Zoek mij: geen locatie gevonden"); return; }
        try {
            JSONObject o = new JSONObject();
            o.put("lat", l.getLatitude());
            o.put("lon", l.getLongitude());
            if (l.hasAccuracy()) o.put("accuracy_m", Math.round(l.getAccuracy()));
            o.put("provider", android.location.LocationManager.GPS_PROVIDER.equals(l.getProvider()) ? "gps" : "network");
            o.put("at", iso(l.getTime()));
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1) {
                JSONObject w = new JSONObject();
                String ssid = info.getSSID();
                if (ssid != null && !ssid.contains("unknown ssid")) w.put("ssid", ssid.replaceAll("^\"|\"$", ""));
                if (info.getBSSID() != null && !"02:00:00:00:00:00".equals(info.getBSSID())) w.put("bssid", info.getBSSID());
                w.put("rssi", info.getRssi());
                o.put("wifi", w);
            }
            foundLocation = o;
            foundLocationAt = System.currentTimeMillis();
            KioskLog.add(this, "Zoek mij: locatie gevonden" + (l.hasAccuracy() ? " (±" + Math.round(l.getAccuracy()) + " m, " + o.optString("provider") + ")" : ""));
        } catch (Exception ignored) {}
    }

    // ---------- beheer-QR-code ----------

    /**
     * EVXADMIN:<verloop>.<HMAC-SHA256 van het verloop met de sleutel van de organisatie, hex> (gemaakt in EventVOX):
     * opent het beheerscherm zonder PIN tot het verloop. Werkt zonder netwerk; de sleutel kwam met de heartbeat.
     */
    private void handleAdminQr(String text) {
        String key = TokenVault.get(prefs, "admin_qr_key");
        if (key == null) { toast("Deze terminal kent nog geen beheer-QR-codes: hij moet eerst gekoppeld zijn in EventVOX"); return; }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)EVXADMIN:(\\d+)\\.([0-9a-f]{64})").matcher(text.trim());
        if (!m.matches()) { toast("Geen geldige beheer-QR-code"); return; }
        long expires = Long.parseLong(m.group(1));
        long now = System.currentTimeMillis() / 1000;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] k = new byte[32];
            for (int i = 0; i < 32; i++) k[i] = (byte) Integer.parseInt(key.substring(i * 2, i * 2 + 2), 16);
            mac.init(new javax.crypto.spec.SecretKeySpec(k, "HmacSHA256"));
            byte[] sig = mac.doFinal(m.group(1).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : sig) hex.append(String.format("%02x", b));
            if (!java.security.MessageDigest.isEqual(hex.toString().getBytes("UTF-8"), m.group(2).toLowerCase(java.util.Locale.ROOT).getBytes("UTF-8"))) {
                KioskLog.add(this, "Beheer-QR-code geweigerd: handtekening klopt niet (ingetrokken of van een andere organisatie)");
                toast("Deze beheer-QR-code is ingetrokken of hoort bij een andere organisatie");
                return;
            }
        } catch (Exception e) { toast("Beheer-QR-code kon niet worden gecontroleerd"); return; }
        if (expires < now) { toast("Deze beheer-QR-code is verlopen"); return; }
        if (expires > now + 8 * 86400L) { toast("Deze beheer-QR-code is te lang geldig (controleer de klok van het toestel)"); return; }
        KioskLog.add(this, "Beheerscherm geopend met een beheer-QR-code");
        if (adminView == null) showAdmin();
        adminUnlocked = true;
        adminEmit("unlocked", new JSONObject());
    }

    // ---------- trillen ----------

    /** Trillen volgens een webpatroon [aan, uit, aan, ...] (ms); uit met vibrate=false in de config of het beheerscherm. */
    @SuppressWarnings("deprecation")
    private boolean doVibrate(String json) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return false;
            JSONArray a;
            String t = json == null ? "" : json.trim();
            a = t.startsWith("[") ? new JSONArray(t) : new JSONArray().put(t.isEmpty() ? 0 : Double.parseDouble(t));
            boolean any = false;
            long[] pattern = new long[a.length() + 1]; // Android: eerst een wachttijd, dan aan/uit
            for (int i = 0; i < a.length() && i < 32; i++) {
                long ms = Math.max(0, Math.min(10000, Math.round(a.optDouble(i, 0))));
                pattern[i + 1] = ms;
                if (i % 2 == 0 && ms > 0) any = true;
            }
            v.cancel();
            if (!any) return true; // 0 of []: stoppen
            if (!prefs.getBoolean("vibrate", true)) return true; // uitgezet: de pagina hoeft dat niet te weten
            v.vibrate(pattern, -1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- toetsenbord-focus ----------

    /** De website de focus geven (niet als het beheerscherm open is), zodat toetsaanslagen daar aankomen */
    private void focusWeb() {
        if (web != null && adminView == null && !web.hasFocus()) web.requestFocus();
    }

    /**
     * Toetsaanslagen (DataWedge als toetsenbord, een USB-/Bluetooth-toetsenbord of -scanner) die nergens heen zouden
     * gaan omdat de website de focus kwijt is (bv. na het beheerscherm), alsnog naar de website.
     * Terug, home, volume en aan/uit blijven bij Android.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean system = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_VOLUME_UP
            || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_POWER || k == KeyEvent.KEYCODE_APP_SWITCH;
        markActivity();
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && volumeLocked()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) applyVolume(); // vast volume: de knoppen doen niets
            return true;
        }
        if (system) return super.dispatchKeyEvent(e);
        if (wifiQrEnabled() && catchWifiKeys(e)) return true;
        return routeKey(e);
    }

    /** Naar de website (ook als die de focus kwijt is), of naar het beheer- of wifi-scherm als dat open is */
    private boolean routeKey(KeyEvent e) {
        if (adminView == null && wifiView == null && web != null && !web.hasFocus()) {
            web.requestFocus();
            return web.dispatchKeyEvent(e);
        }
        return super.dispatchKeyEvent(e);
    }

    /*
     * Een wifi-QR-code die als toetsaanslagen binnenkomt (DataWedge met eigen profielen, een USB- of Bluetooth-scanner):
     * toetsen die het begin van "WIFI:" vormen, worden even vastgehouden. Is het een wifi-code (Enter, of een complete
     * code en dan een pauze), dan gaat hij naar handleWifiQr en niet naar de website; anders gaan de vastgehouden toetsen
     * alsnog, in dezelfde volgorde, door. Een scanner typt veel sneller dan een mens: na een pauze van meer dan
     * WEDGE_GAP_MS gaat alles gewoon door, zodat iemand die zelf "wifi:" typt niets merkt.
     */
    private static final long WEDGE_GAP_MS = 100;
    private final java.util.ArrayList<KeyEvent> heldKeys = new java.util.ArrayList<>();
    private final StringBuilder heldText = new StringBuilder();
    private long heldAt = 0;
    private final Runnable heldTimeout = new Runnable() { public void run() { finishHeld(false); } };

    /** Codes die de app zelf afhandelt (nooit naar de website): wifi-QR-codes en beheer-QR-codes */
    private static final String[] APP_PREFIXES = {"WIFI:", "EVXADMIN:"};

    private static boolean wifiPrefix(String text) {
        String t = text.toUpperCase(java.util.Locale.ROOT);
        for (String p : APP_PREFIXES) if (t.length() <= p.length() ? p.startsWith(t) : t.startsWith(p)) return true;
        return false;
    }

    private static boolean isAppCode(String text) {
        String t = text.toUpperCase(java.util.Locale.ROOT);
        for (String p : APP_PREFIXES) if (t.length() > p.length() && t.startsWith(p)) return true;
        return false;
    }

    /** Een code van de app: wifi of beheer */
    private void handleAppCode(String text) {
        if (text.regionMatches(true, 0, "EVXADMIN:", 0, 9)) handleAdminQr(text);
        else handleWifiQr(text);
    }

    /** True als de toets is vastgehouden of als deel van een wifi-code is gebruikt */
    private boolean catchWifiKeys(KeyEvent e) {
        boolean holding = !heldKeys.isEmpty();
        long now = SystemClock.uptimeMillis();
        if (holding && now - heldAt > WEDGE_GAP_MS) { finishHeld(false); holding = !heldKeys.isEmpty(); }
        int action = e.getAction();
        if (action == KeyEvent.ACTION_UP || KeyEvent.isModifierKey(e.getKeyCode())) {
            if (!holding) return false;
            heldKeys.add(e); // hoort bij een vastgehouden toets (loslaten, shift)
            return true;
        }
        if (action == KeyEvent.ACTION_MULTIPLE && e.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN && e.getCharacters() != null) {
            // Een reeks tekens in één keer ("tekens als events" in DataWedge)
            return holdText(e, e.getCharacters(), holding, now);
        }
        if (action != KeyEvent.ACTION_DOWN) { if (holding) flushHeld(); return false; }
        if (e.getKeyCode() == KeyEvent.KEYCODE_ENTER || e.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!holding) return false;
            if (heldText.length() >= 5) { heldKeys.add(e); finishHeld(true); return true; }
            flushHeld();
            return false;
        }
        int ch = e.getUnicodeChar();
        if (ch == 0) { if (holding) flushHeld(); return false; }
        return holdText(e, String.valueOf((char) ch), holding, now);
    }

    private boolean holdText(KeyEvent e, String chars, boolean holding, long now) {
        String next = heldText.toString() + chars;
        if (!wifiPrefix(next)) { if (holding) flushHeld(); return false; }
        heldKeys.add(e);
        heldText.setLength(0);
        heldText.append(next);
        heldAt = now;
        handler.removeCallbacks(heldTimeout);
        handler.postDelayed(heldTimeout, WEDGE_GAP_MS);
        return true;
    }

    /** enter: de scanner sloot af met Enter. Een (complete) wifi-code naar handleWifiQr, anders alles doorsturen. */
    private void finishHeld(boolean enter) {
        handler.removeCallbacks(heldTimeout);
        String text = heldText.toString();
        boolean complete = text.regionMatches(true, 0, "EVXADMIN:", 0, 9) ? text.matches("(?i)EVXADMIN:\\d+\\.[0-9a-f]{64}")
            : WifiProfiles.parseQr(text) != null;
        boolean code = isAppCode(text) && (enter || complete);
        if (code) {
            heldKeys.clear();
            heldText.setLength(0);
            handleAppCode(text);
        } else {
            flushHeld();
        }
    }

    private void flushHeld() {
        handler.removeCallbacks(heldTimeout);
        java.util.List<KeyEvent> keys = new java.util.ArrayList<>(heldKeys);
        heldKeys.clear();
        heldText.setLength(0);
        for (KeyEvent k : keys) routeKey(k);
    }

    // ---------- scanner (DataWedge) ----------

    /** scan_mode: keyboard (typen + Enter, standaard), event (alleen JavaScript-event webkiosk-scan), both, off */
    private String scanMode() { return prefs.getString("scan_mode", "keyboard"); }

    /** datawedge: app (de app stelt zijn eigen DataWedge-profiel in, standaard) of off (DataWedge niet aanraken) */
    private boolean manageDataWedge() { return !"off".equals(prefs.getString("datawedge", "app")); }

    private int dwFailed = 0;
    private String lastScan = "";

    private void setupDataWedge() {
        if (!DataWedge.installed(this)) { prefs.edit().putString("dw_status", "DataWedge niet gevonden (geen Zebra?)").apply(); return; }
        if (!manageDataWedge()) { prefs.edit().putString("dw_status", "Niet door de app ingesteld: DataWedge gebruikt zijn eigen profielen").apply(); return; }
        dwFailed = 0;
        prefs.edit().putString("dw_status", "Profiel " + DataWedge.PROFILE + " wordt ingesteld…").apply();
        DataWedge.configure(this);
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onDataWedge(i); }
    };
    private final BroadcastReceiver scanReceiver2 = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onDataWedge(i); }
    };

    private long lastScanAt = 0;
    private String lastScanData = "";

    private void onDataWedge(Intent i) {
        if (DataWedge.RESULT_ACTION.equals(i.getAction())) {
            String id = i.getStringExtra("COMMAND_IDENTIFIER");
            if (id == null || !id.startsWith("webkiosk-")) return;
            if (!"SUCCESS".equals(i.getStringExtra("RESULT"))) {
                dwFailed++;
                Bundle info = i.getBundleExtra("RESULT_INFO");
                Object code = info == null ? null : info.get("RESULT_CODE");
                prefs.edit().putString("dw_status", "Instellen mislukt (" + id.substring(9) + (code == null ? "" : ": " + code) + ")").apply();
            } else if ("webkiosk-keystroke".equals(id) && dwFailed == 0) {
                prefs.edit().putString("dw_status", "Profiel " + DataWedge.PROFILE + " actief: scans gaan via de app").apply();
            }
            return;
        }
        String data = i.getStringExtra(DataWedge.EXTRA_DATA);
        if (data == null || data.isEmpty()) return;
        // Dezelfde scan via beide ontvangers (met en zonder categorie): één keer
        long now = SystemClock.uptimeMillis();
        if (data.equals(lastScanData) && now - lastScanAt < 300) return;
        lastScanData = data;
        lastScanAt = now;
        String type = i.getStringExtra(DataWedge.EXTRA_TYPE);
        deliverScan(data, type == null ? "" : type.replace("LABEL-TYPE-", ""));
    }

    private void deliverScan(String data, String symbology) {
        markActivity();
        if (isAppCode(data)) { handleAppCode(data); return; } // nooit naar de website
        lastScan = data + (symbology.isEmpty() ? "" : " (" + symbology + ")");
        JSONObject o = new JSONObject();
        try { o.put("data", data); o.put("symbology", symbology); } catch (Exception ignored) {}
        if (adminView != null) { adminEmit("scan", o); return; } // beheerscherm open: daar tonen, niet aan de website
        String mode = scanMode();
        if (mode.equals("event") || mode.equals("both")) {
            web.evaluateJavascript("(function(d){try{window.dispatchEvent(new CustomEvent('webkiosk-scan',{detail:d}));}catch(e){}"
                + "if(typeof window.onWebKioskScan==='function')window.onWebKioskScan(d);})(" + o + ");", null);
        }
        if (mode.equals("keyboard") || mode.equals("both")) typeText(data, true);
    }

    /** Typt tekst als echte toetsaanslagen in het actieve invoerveld (zoals DataWedge). */
    private void typeText(String text, boolean enter) {
        focusWeb();
        KeyCharacterMap km = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] evs = km.getEvents(text.toCharArray());
        if (evs != null) for (KeyEvent e : evs) web.dispatchKeyEvent(e);
        else web.dispatchKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
        if (enter) {
            web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private String formatUid(byte[] id) {
        String f = prefs.getString("nfc_uid_format", "hex");
        boolean rev = f.endsWith("_reversed");
        if (f.startsWith("dec")) {
            byte[] b = rev ? reverse(id) : id;
            return new java.math.BigInteger(1, b).toString();
        }
        String h = hex(id, rev);
        if (f.startsWith("hex_colon")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < h.length(); i += 2) { if (i > 0) sb.append(':'); sb.append(h, i, i + 2); }
            return sb.toString();
        }
        return h;
    }

    private static byte[] reverse(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }

    private static String hex(byte[] b, boolean rev) {
        if (rev) b = reverse(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private static String[] decodeRecord(NdefRecord r) {
        try {
            byte[] p = r.getPayload();
            if (r.getTnf() == NdefRecord.TNF_WELL_KNOWN && java.util.Arrays.equals(r.getType(), NdefRecord.RTD_TEXT)) {
                String enc = (p[0] & 0x80) == 0 ? "UTF-8" : "UTF-16";
                int lang = p[0] & 0x3F;
                return new String[]{"text", new String(p, 1 + lang, p.length - 1 - lang, enc)};
            }
            android.net.Uri u = r.toUri();
            if (u != null) return new String[]{"uri", u.toString()};
            if (r.getTnf() == NdefRecord.TNF_MIME_MEDIA)
                return new String[]{new String(r.getType(), "US-ASCII"), new String(p, "UTF-8")};
            return new String[]{"unknown", new String(p, "UTF-8")};
        } catch (Exception e) {
            return new String[]{"error", ""};
        }
    }

    // ---------- updates ----------

    private long lastTouch = 0;
    private volatile boolean updateBusy = false;
    private static final long IDLE_BEFORE_INSTALL_MS = 120000;

    private String updateUrl() {
        String u = prefs.getString("update_url", "");
        if (!u.isEmpty()) return u;
        return "beta".equals(prefs.getString("update_channel", "stable")) ? Updater.BETA_URL : Updater.DEFAULT_URL;
    }

    /** Bij het starten (1 minuut erna) en, alleen als update_check_hours > 0, daarna periodiek. */
    private final Runnable updateCheck = new Runnable() {
        public void run() {
            handler.removeCallbacks(updateCheck);
            int hours = prefs.getInt("update_check_hours", 0);
            if (hours > 0) handler.postDelayed(updateCheck, hours * 3600000L);
            if (prefs.getBoolean("auto_update", true)) checkForUpdate(false);
        }
    };

    /** interactive=true: resultaat tonen in een dialoog; anders stil en automatisch installeren. */
    private void checkForUpdate(final boolean interactive) {
        if (updateBusy) { if (interactive) adminToast("Update is al bezig"); return; }
        updateBusy = true;
        final String url = updateUrl();
        new Thread(new Runnable() { public void run() {
            Updater.Info info = null; String err = null;
            try { info = Updater.fetch(url); } catch (Exception e) { err = e.getMessage(); }
            final Updater.Info fi = info; final String fe = err;
            prefs.edit().putLong("update_last_check", System.currentTimeMillis())
                .putString("update_last_result", fe != null ? "fout: " + fe
                    : (fi.versionCode > Updater.currentVersionCode(MainActivity.this) ? "nieuw: " + fi.versionName : "actueel")).apply();
            updateBusy = false;
            KioskLog.add(MainActivity.this, "Update-controle: " + prefs.getString("update_last_result", ""));
            runOnUiThread(new Runnable() { public void run() { onUpdateInfo(fi, fe, interactive); } });
        }}).start();
    }

    private void onUpdateInfo(final Updater.Info info, String err, boolean interactive) {
        int cur = Updater.currentVersionCode(this);
        if (!interactive) {
            if (info != null && info.versionCode > cur) installWhenIdle(info);
            return;
        }
        JSONObject d = new JSONObject();
        try {
            if (err != null) d.put("error", err + " (" + updateUrl() + ")");
            else {
                lastUpdateInfo = info;
                d.put("newer", info.versionCode > cur);
                d.put("versionName", info.versionName);
                d.put("notes", info.notes);
            }
        } catch (Exception ignored) {}
        adminEmit("update", d);
    }

    /** Niet installeren terwijl iemand het toestel gebruikt: wacht tot 2 minuten geen aanraking. */
    private void installWhenIdle(final Updater.Info info) {
        long idle = SystemClock.uptimeMillis() - lastTouch;
        if (lastTouch != 0 && idle < IDLE_BEFORE_INSTALL_MS) {
            handler.postDelayed(new Runnable() { public void run() { installWhenIdle(info); } }, 300000);
            return;
        }
        downloadAndInstall(info, false);
    }

    private void downloadAndInstall(final Updater.Info info, final boolean interactive) {
        if (updateBusy) return;
        updateBusy = true;
        if (interactive) adminToast("Update " + info.versionName + " downloaden en installeren…");
        new Thread(new Runnable() { public void run() {
            String err = null;
            try {
                KioskLog.add(MainActivity.this, "Update " + info.versionName + " downloaden en installeren");
                java.io.File apk = Updater.download(MainActivity.this, info);
                Updater.install(MainActivity.this, apk);
            } catch (Exception e) { err = e.getMessage(); }
            updateBusy = false;
            if (err != null) {
                final String fe = err;
                prefs.edit().putString("update_last_result", "fout: " + fe).apply();
                runOnUiThread(new Runnable() { public void run() { if (interactive) adminToast("Update mislukt: " + fe); } });
            }
        }}).start();
    }

    // ---------- verborgen beheermenu ----------

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        markActivity();
        if (adminView != null) {
            handler.removeCallbacks(adminTimeout);
            handler.postDelayed(adminTimeout, ADMIN_IDLE_MS);
            return super.dispatchTouchEvent(ev);
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float spot = SAME_SPOT_DP * getResources().getDisplayMetrics().density;
            long now = SystemClock.uptimeMillis();
            boolean same = Math.abs(ev.getX() - tapX) < spot && Math.abs(ev.getY() - tapY) < spot;
            if (now - lastTap > MAX_TAP_GAP_MS || !same) { tapCount = 0; tapX = ev.getX(); tapY = ev.getY(); }
            lastTap = now;
            if (++tapCount >= prefs.getInt("menu_taps", 10)) { tapCount = 0; showAdmin(); }
        }
        return super.dispatchTouchEvent(ev);
    }

    private String nfcStatus() {
        if (nfcLastRestart == 0) return "NFC nog niet herstart";
        long ago = (System.currentTimeMillis() - nfcLastRestart) / 1000;
        return "Laatst herstart " + ago + " s geleden (" + nfcRestartReason + "), " + nfcRestarts + "× totaal";
    }

    private String wifiStatus() {
        java.util.Set<String> qr = prefs.getStringSet("wifi_qr_ssids", null);
        String viaQr = qr == null || qr.isEmpty() ? "" : " Via QR-code toegevoegd: " + android.text.TextUtils.join(", ", new java.util.TreeSet<>(qr)) + ".";
        return wifiStatusConfig() + viaQr;
    }

    private String wifiStatusConfig() {
        java.util.Set<String> ssids = prefs.getStringSet("wifi_ssids", null);
        String r = prefs.getString("wifi_result", "");
        if (ssids == null || ssids.isEmpty()) return r.isEmpty() ? "Geen wifi-profielen uit de configuratie." : "Profielen: " + r;
        return "Profielen uit de configuratie: " + android.text.TextUtils.join(", ", new java.util.TreeSet<>(ssids)) + (r.isEmpty() ? "" : " · " + r);
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    private void applyRuntimeSettings() {
        if (prefs.getBoolean("keep_screen_on", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        restartNfc("instellingen");
        schedulePeriodicNfcRefresh();
    }

    @SuppressWarnings("deprecation")
    private void removeOwner() {
        if (!isOwner()) { Toast.makeText(this, "Geen device owner", Toast.LENGTH_SHORT).show(); return; }
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, getPackageName());
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME);
            if (Build.VERSION.SDK_INT >= 23) { dpm.setStatusBarDisabled(admin, false); dpm.setKeyguardDisabled(admin, false); }
            try { stopLockTask(); } catch (Exception ignored) {}
            dpm.clearDeviceOwnerApp(getPackageName());
            Toast.makeText(this, "Kiosk-modus uitgeschakeld; app kan nu verwijderd worden", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Mislukt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- beheerscherm (assets/admin.html) ----------

    private static final long ADMIN_IDLE_MS = 180000;
    private final Runnable adminTimeout = new Runnable() { public void run() { hideAdmin(); } };

    private void showAdmin() {
        if (adminView != null) return;
        markActivity();
        adminUnlocked = false;
        adminView = new WebView(this);
        adminView.setBackgroundColor(Color.parseColor("#5e2572"));
        WebSettings as = adminView.getSettings();
        as.setJavaScriptEnabled(true);
        as.setAllowFileAccess(true);
        as.setSupportZoom(false);
        adminView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !url.startsWith("file:///android_asset/"); }
        });
        adminView.addJavascriptInterface(new AdminBridge(), "Kiosk");
        adminView.loadUrl("file:///android_asset/admin.html");
        root.addView(adminView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        handler.postDelayed(adminTimeout, ADMIN_IDLE_MS);
    }

    private void hideAdmin() {
        handler.removeCallbacks(adminTimeout);
        if (adminView == null) return;
        if (nfcTesting) { nfcTesting = false; applyRuntimeSettings(); }
        adminUnlocked = false;
        root.removeView(adminView);
        adminView.destroy();
        adminView = null;
        hideSystemUi();
        focusWeb();
        // toetsenbord dicht
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private void adminEmit(String type, JSONObject data) {
        if (adminView == null) return;
        adminView.evaluateJavascript("window.kioskEvent&&window.kioskEvent(" + JSONObject.quote(type) + "," + data + ")", null);
    }

    private void adminToast(String text) {
        if (adminView == null) { toast(text); return; }
        JSONObject d = new JSONObject();
        try { d.put("text", text); } catch (Exception ignored) {}
        adminEmit("toast", d);
    }

    private JSONObject adminState() throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", appVersion());
        o.put("serial", serial());
        o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("android", Build.VERSION.RELEASE + " (" + Build.DISPLAY + ")");
        o.put("owner", isOwner());
        o.put("url", homeUrl());
        o.put("resolvedUrl", resolvedUrl());
        JSONObject st = new JSONObject();
        st.put("nfc_mode", prefs.getString("nfc_mode", "both"));
        st.put("nfc_uid_format", prefs.getString("nfc_uid_format", "hex"));
        st.put("nfc_keyboard_value", prefs.getString("nfc_keyboard_value", "uid"));
        st.put("nfc_send_enter", prefs.getBoolean("nfc_send_enter", true));
        st.put("nfc_refresh_seconds", prefs.getInt("nfc_refresh_seconds", 0));
        st.put("auto_update", prefs.getBoolean("auto_update", true));
        st.put("update_check_hours", prefs.getInt("update_check_hours", 0));
        st.put("keep_screen_on", prefs.getBoolean("keep_screen_on", false));
        st.put("reload_minutes", prefs.getInt("reload_minutes", 0));
        st.put("ignore_ssl_errors", prefs.getBoolean("ignore_ssl_errors", false));
        st.put("vibrate", prefs.getBoolean("vibrate", true));
        st.put("update_channel", prefs.getString("update_channel", "stable"));
        st.put("volume_lock", prefs.getInt("volume_lock", 80));
        st.put("dim_minutes", prefs.getInt("dim_minutes", 2));
        st.put("dim_level", prefs.getInt("dim_level", 15));
        st.put("nightly_reload", prefs.getBoolean("nightly_reload", true));
        st.put("nightly_hour", prefs.getInt("nightly_hour", 4));
        st.put("low_battery", prefs.getInt("low_battery", 15));
        st.put("orientation", prefs.getString("orientation", "portrait"));
        st.put("menu_taps", prefs.getInt("menu_taps", 10));
        st.put("scan_mode", scanMode());
        st.put("datawedge", manageDataWedge() ? "app" : "off");
        o.put("settings", st);
        JSONObject n = new JSONObject();
        n.put("available", nfc != null);
        n.put("enabled", nfc != null && nfc.isEnabled());
        n.put("status", nfcStatus());
        o.put("nfc", n);
        JSONObject u = new JSONObject();
        long last = prefs.getLong("update_last_check", 0);
        u.put("lastCheck", last == 0 ? "" : new java.text.SimpleDateFormat("d-M HH:mm").format(new java.util.Date(last)));
        u.put("lastResult", prefs.getString("update_last_result", ""));
        u.put("url", prefs.getString("update_url", ""));
        u.put("defaultUrl", Updater.DEFAULT_URL);
        o.put("update", u);
        o.put("wifi", wifiStatus());
        o.put("pinDefault", "1234".equals(pin()));
        JSONObject health = new JSONObject();
        health.put("crashes", renderCrashes);
        health.put("lastCrash", lastCrashAt > 0 ? new java.text.SimpleDateFormat("d-M HH:mm").format(new java.util.Date(lastCrashAt)) : "");
        long diag = prefs.getLong("diagnostics_sent", 0);
        health.put("diagnosticsSent", diag > 0 ? new java.text.SimpleDateFormat("d-M HH:mm").format(new java.util.Date(diag)) : "");
        health.put("diagnosticsPending", diagnosticsPending);
        health.put("identity", TokenVault.get(prefs, "terminal_token") != null);
        health.put("clock", clockCheckedAt == 0 ? "" : Math.abs(clockSkew) < CLOCK_WARN_S ? "loopt gelijk met de server" : skewText(clockSkew));
        health.put("remote", prefs.getInt("remote_settings_version", 0) > 0 ? prefs.getString("remote_settings_keys", "") : "");
        health.put("adminQr", TokenVault.get(prefs, "admin_qr_key") != null);
        String log = KioskLog.text(this);
        String[] lines = log.split("\n");
        StringBuilder tail = new StringBuilder();
        for (int i = Math.max(0, lines.length - 40); i < lines.length; i++) tail.append(lines[i]).append('\n');
        health.put("log", tail.toString());
        o.put("health", health);
        JSONObject sc = new JSONObject();
        sc.put("installed", DataWedge.installed(this));
        sc.put("status", prefs.getString("dw_status", ""));
        sc.put("last", lastScan);
        o.put("scanner", sc);
        o.put("battery", batteryJson(lastBattery));
        return o;
    }

    /** Wordt door admin.js aangeroepen (op een achtergrondthread van de WebView). */
    private class AdminBridge {
        @JavascriptInterface public String pin(String code) {
            JSONObject r = new JSONObject();
            try {
                long now = System.currentTimeMillis();
                if (now < pinLockedUntil) { r.put("locked", (pinLockedUntil - now + 999) / 1000); return r.toString(); }
                if (code != null && code.equals(MainActivity.this.pin())) {
                    adminUnlocked = true; pinFails = 0; r.put("ok", true);
                } else if (code != null && code.length() >= MainActivity.this.pin().length()) {
                    r.put("wrong", true);
                    if (++pinFails >= 5) { pinFails = 0; pinLockedUntil = now + 60000; r.put("locked", 60); }
                }
            } catch (Exception ignored) {}
            return r.toString();
        }

        @JavascriptInterface public String state() {
            if (!adminUnlocked) return "{}";
            final String[] out = {"{}"};
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(new Runnable() { public void run() {
                try { out[0] = adminState().toString(); } catch (Exception ignored) {}
                done.countDown();
            }});
            try { done.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            return out[0];
        }

        @JavascriptInterface public String set(final String key, final String value) {
            JSONObject r = new JSONObject();
            try {
                if (!adminUnlocked) { r.put("error", "Niet ontgrendeld"); return r.toString(); }
                String err = applySetting(key, value == null ? "" : value.trim());
                if (err != null) r.put("error", err); else r.put("ok", true);
                if (err == null && ("url".equals(key) || "pin".equals(key) || "update_url".equals(key))) r.put("message", "Opgeslagen");
            } catch (Exception e) { try { r.put("error", e.getMessage()); } catch (Exception ignored) {} }
            return r.toString();
        }

        @JavascriptInterface public void action(final String name, final String arg) {
            if (!"close".equals(name) && !adminUnlocked) return;
            runOnUiThread(new Runnable() { public void run() { adminAction(name, arg); } });
        }
    }

    private static final java.util.List<String> STRING_KEYS = java.util.Arrays.asList("nfc_mode", "nfc_uid_format", "nfc_keyboard_value", "scan_mode", "datawedge", "update_channel", "orientation");
    private static final java.util.List<String> BOOL_KEYS = java.util.Arrays.asList("nfc_send_enter", "auto_update", "keep_screen_on", "ignore_ssl_errors", "vibrate", "nightly_reload");
    private static final java.util.List<String> INT_KEYS = java.util.Arrays.asList("nfc_refresh_seconds", "update_check_hours", "reload_minutes", "menu_taps", "volume_lock", "dim_minutes", "dim_level", "nightly_hour", "low_battery");

    /** Eén instelling opslaan en toepassen; geeft een foutmelding of null. */
    private String applySetting(String key, String v) {
        SharedPreferences.Editor e = prefs.edit();
        if ("url".equals(key)) {
            if (!v.isEmpty() && !v.matches("(?i)^(https?|file|about):.*")) v = "https://" + v;
            if (v.isEmpty()) return "Vul een adres in";
            e.putString("url", v).commit();
            runOnUiThread(new Runnable() { public void run() { web.clearHistory(); loadHome(); } });
            return null;
        }
        if ("pin".equals(key)) {
            if (!v.matches("[0-9]{4,8}")) return "De PIN moet 4 tot 8 cijfers zijn";
            if (v.equals("1234")) return "1234 is de standaard-PIN: kies een andere";
            e.putString("pin", v).commit();
            return null;
        }
        if ("update_url".equals(key)) {
            if (!v.isEmpty() && !v.matches("(?i)^https?://.+")) return "Het update-adres moet met https:// beginnen";
            e.putString("update_url", v).commit();
            return null;
        }
        if ("datawedge".equals(key)) {
            e.putString(key, v).commit();
            runOnUiThread(new Runnable() { public void run() {
                if (manageDataWedge()) setupDataWedge();
                else {
                    if (DataWedge.installed(MainActivity.this)) DataWedge.remove(MainActivity.this);
                    prefs.edit().putString("dw_status", "Profiel verwijderd: DataWedge gebruikt zijn eigen profielen").apply();
                }
            }});
            return null;
        }
        if ("orientation".equals(key) && !v.matches("portrait|landscape|auto")) return "orientation moet portrait, landscape of auto zijn";
        if (STRING_KEYS.contains(key)) e.putString(key, v);
        else if (BOOL_KEYS.contains(key)) e.putBoolean(key, Boolean.parseBoolean(v));
        else if (INT_KEYS.contains(key)) {
            int n;
            try { n = Integer.parseInt(v); } catch (NumberFormatException x) { return "Ongeldig getal"; }
            if ("menu_taps".equals(key) && (n < 5 || n > 20)) return "Kies 5 tot 20 tikken";
            if ("volume_lock".equals(key)) n = Math.min(100, n);
            if ("dim_level".equals(key) && (n < 5 || n > 60)) return "Kies 5 tot 60%";
            if ("nightly_hour".equals(key)) n = Math.min(23, n);
            if ("low_battery".equals(key)) n = Math.min(50, n);
            e.putInt(key, Math.max(0, n));
        } else return "Onbekende instelling: " + key;
        e.commit();
        runOnUiThread(new Runnable() { public void run() {
            applyRuntimeSettings();
            applyVolume();
            applyOrientation();
            if (lastBattery != null) updateBatteryBanner(batteryJson(lastBattery));
            if (dimmed) setBrightness(-1f);
            schedulePeriodicReload();
            handler.removeCallbacks(updateCheck);
            int h = prefs.getInt("update_check_hours", 0);
            if (h > 0) handler.postDelayed(updateCheck, h * 3600000L);
        }});
        return null;
    }

    private void adminAction(String name, String arg) {
        switch (name) {
            case "close": hideAdmin(); break;
            case "diagnostics":
                diagnosticsPending = true;
                KioskLog.add(this, "Diagnose verstuurd vanaf het toestel");
                adminToast("De diagnose gaat mee met de volgende heartbeat (binnen een minuut)");
                break;
            case "networks": hideAdmin(); pendingWifi = null; showWifiView(); break;
            case "vibrateTest":
                // de patronen van de scanterminal: toegelaten, waarschuwing, geweigerd
                String pat = "warning".equals(arg) ? "[90,110,90]" : "error".equals(arg) ? "[350,150,350,150,350]" : "[70]";
                if (!prefs.getBoolean("vibrate", true)) adminToast("Trillen staat uit");
                else if (!doVibrate(pat)) adminToast("Dit toestel kan niet trillen");
                break;
            case "dataWedge":
                setupDataWedge();
                adminToast("DataWedge-profiel wordt ingesteld");
                handler.postDelayed(new Runnable() { public void run() { adminEmit("refresh", new JSONObject()); } }, 1500);
                break;
            case "reload": loadHome(); adminToast("Pagina wordt herladen"); break;
            case "clearCache":
                web.clearCache(true);
                CookieManager.getInstance().removeAllCookies(null);
                WebStorage.getInstance().deleteAllData();
                loadHome();
                adminToast("Cache en cookies gewist");
                break;
            case "nfcRestart":
                restartNfc("handmatig");
                adminToast("NFC wordt herstart");
                handler.postDelayed(new Runnable() { public void run() { adminEmit("refresh", new JSONObject()); } }, 1800);
                break;
            case "nfcTest":
                nfcTesting = "start".equals(arg);
                lastTagUid = "";
                if (nfcTesting && nfc == null) adminToast("Dit toestel heeft geen NFC");
                applyRuntimeSettings();
                break;
            case "nfcSettings":
                hideAdmin();
                kioskSuspended = true;
                try { stopLockTask(); } catch (Exception ignored) {}
                try { startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
                break;
            case "wifiSettings":
                hideAdmin();
                kioskSuspended = true;
                try { stopLockTask(); } catch (Exception ignored) {}
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case "checkUpdate": checkForUpdate(true); break;
            case "installUpdate":
                if (lastUpdateInfo != null) downloadAndInstall(lastUpdateInfo, true);
                break;
            case "leave": hideAdmin(); leaveKiosk(); break;
            case "removeOwner": hideAdmin(); removeOwner(); break;
        }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(wakeReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(scanReceiver2); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

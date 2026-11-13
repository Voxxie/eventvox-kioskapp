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

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("http:") || url.startsWith("https:") || url.startsWith("about:")
                        || url.startsWith("file:") || url.startsWith("data:")) return false;
                return true; // geen externe apps (tel:, mailto:, intent:) openen
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                pageError = false;
                v.evaluateJavascript(BATTERY_POLYFILL, null); // zo vroeg mogelijk: vóór de scripts van de pagina
            }
            @Override public void onPageCommitVisible(WebView v, String url) {
                v.evaluateJavascript(BATTERY_POLYFILL, null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(BATTERY_POLYFILL, null);
                focusWeb();
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
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback cb) {
                cb.invoke(o, true, false);
            }
        });

        nfc = NfcAdapter.getDefaultAdapter(this);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public boolean nfcAvailable() { return nfc != null; }
            @JavascriptInterface public boolean nfcEnabled() { return nfc != null && nfc.isEnabled(); }
            @JavascriptInterface public String getBattery() { return batteryJson(lastBattery).toString(); }
            @JavascriptInterface public String getSerial() { return serial(); }
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

        lastBattery = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter wf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wf.addAction(Intent.ACTION_USER_PRESENT);
        wf.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        registerReceiver(wakeReceiver, wf);
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
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        watchSystemBars();
        setupDataWedge();
        loadHome();
        schedulePeriodicReload();
        handler.postDelayed(updateCheck, 60000); // eerste controle 1 minuut na start
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
            for (String key : new String[]{"keep_screen_on", "ignore_ssl_errors"}) {
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
            for (String key : new String[]{"nfc_refresh_seconds", "nfc_debounce_ms", "update_check_hours"}) {
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
            try { result = WifiProfiles.apply(MainActivity.this, list, previous, removeOthers, applied); }
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
        if (!system && adminView == null && web != null && !web.hasFocus()) {
            web.requestFocus();
            return web.dispatchKeyEvent(e);
        }
        return super.dispatchKeyEvent(e);
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
        return u.isEmpty() ? Updater.DEFAULT_URL : u;
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
        lastTouch = SystemClock.uptimeMillis();
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

    private static final java.util.List<String> STRING_KEYS = java.util.Arrays.asList("nfc_mode", "nfc_uid_format", "nfc_keyboard_value", "scan_mode", "datawedge");
    private static final java.util.List<String> BOOL_KEYS = java.util.Arrays.asList("nfc_send_enter", "auto_update", "keep_screen_on", "ignore_ssl_errors");
    private static final java.util.List<String> INT_KEYS = java.util.Arrays.asList("nfc_refresh_seconds", "update_check_hours", "reload_minutes", "menu_taps");

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
        if (STRING_KEYS.contains(key)) e.putString(key, v);
        else if (BOOL_KEYS.contains(key)) e.putBoolean(key, Boolean.parseBoolean(v));
        else if (INT_KEYS.contains(key)) {
            int n;
            try { n = Integer.parseInt(v); } catch (NumberFormatException x) { return "Ongeldig getal"; }
            if ("menu_taps".equals(key) && (n < 5 || n > 20)) return "Kies 5 tot 20 tikken";
            e.putInt(key, Math.max(0, n));
        } else return "Onbekende instelling: " + key;
        e.commit();
        runOnUiThread(new Runnable() { public void run() {
            applyRuntimeSettings();
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
        try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(scanReceiver2); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

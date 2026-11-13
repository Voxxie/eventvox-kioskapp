package nl.webkiosk;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.Properties;

/**
 * Fullscreen kiosk-browser. Configuratie (hoogste prioriteit eerst):
 *  1. /sdcard/webkiosk.properties  (url=..., pin=..., keep_screen_on=true, reload_minutes=0)
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
    private AlertDialog nfcTestDialog;
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

        if (prefs.getBoolean("keep_screen_on", true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
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
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(BATTERY_POLYFILL, null);
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

        setContentView(web);
        loadHome();
        schedulePeriodicReload();
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
            for (String key : new String[]{"nfc_mode", "nfc_uid_format", "nfc_keyboard_value"}) {
                String v = p.getProperty(key);
                if (v != null && !v.trim().isEmpty()) e.putString(key, v.trim().toLowerCase());
            }
            String ne = p.getProperty("nfc_send_enter");
            if (ne != null) e.putBoolean("nfc_send_enter", Boolean.parseBoolean(ne.trim()));
            String r = p.getProperty("reload_minutes");
            if (r != null) try { e.putInt("reload_minutes", Integer.parseInt(r.trim())); } catch (NumberFormatException ignored) {}
            e.apply();
            return;
        }
    }

    private String homeUrl() { return prefs.getString("url", DEFAULT_URL); }

    /** URL met {serial}/{model} ingevuld; eindigt de URL op "serial=" dan wordt het serienummer erachter gezet. */
    private String resolvedUrl() {
        String u = homeUrl();
        String sn = Uri.encode(serial());
        if (u.endsWith("serial=")) u = u + sn;
        return u.replace("{serial}", sn).replace("{model}", Uri.encode(Build.MODEL));
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
        } catch (Exception ignored) {}
        return o;
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

    /** Zorgt dat navigator.getBattery() altijd werkt, ook als de WebView-versie hem niet heeft. */
    private static final String BATTERY_POLYFILL =
        "(function(){if(!window.WebKiosk||window.__wkBattery)return;window.__wkBattery=1;"
      + "function rd(){var d=JSON.parse(WebKiosk.getBattery());return d;}"
      + "var bm={level:1,charging:false,chargingTime:Infinity,dischargingTime:Infinity,"
      + "onlevelchange:null,onchargingchange:null,_l:{},"
      + "addEventListener:function(t,f){(this._l[t]=this._l[t]||[]).push(f);},"
      + "removeEventListener:function(t,f){var a=this._l[t]||[];var i=a.indexOf(f);if(i>=0)a.splice(i,1);},"
      + "_fire:function(t){var e={type:t,target:this};(this._l[t]||[]).forEach(function(f){f.call(bm,e);});if(typeof this['on'+t]==='function')this['on'+t](e);}};"
      + "function upd(d){var l=d.level/100,c=!!d.charging,lc=l!==bm.level,cc=c!==bm.charging;bm.level=l;bm.charging=c;"
      + "bm.chargingTime=d.full?0:Infinity;if(lc)bm._fire('levelchange');if(cc)bm._fire('chargingchange');}"
      + "upd(rd());window.addEventListener('webkiosk-battery',function(e){upd(e.detail);});"
      + "if(!navigator.getBattery){navigator.getBattery=function(){return Promise.resolve(bm);};}})();";

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

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override protected void onResume() {
        super.onResume();
        if (kioskSuspended) { // terug vanuit instellingen: kiosk weer aan
            kioskSuspended = false;
            applyDeviceOwnerPolicies();
        }
        hideSystemUi();
        enterKiosk();
        startNfc();
    }

    @Override protected void onPause() {
        if (nfc != null) try { nfc.disableReaderMode(this); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) hideSystemUi();
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
        if (web.canGoBack()) web.goBack();
        // anders: niets doen, app kan niet via terug-knop worden verlaten
    }

    // ---------- NFC ----------

    private void startNfc() {
        if (nfc == null || "off".equals(prefs.getString("nfc_mode", "both"))) return;
        startNfcForced();
    }

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
        if (nfcTestDialog != null) {
            StringBuilder m = new StringBuilder();
            m.append("Tag-ID: ").append(o.optString("uid")).append("\nHex: ").append(o.optString("uidHex"))
             .append("\nType: ").append(o.optJSONArray("techs"));
            JSONArray nd = o.optJSONArray("ndef");
            if (nd != null) for (int i = 0; i < nd.length(); i++) {
                JSONObject r = nd.optJSONObject(i);
                m.append("\nInhoud (").append(r.optString("type")).append("): ").append(r.optString("value"));
            }
            m.append("\n\nWordt getypt als: ").append(kbValue);
            nfcTestDialog.setMessage(m.toString());
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

    /** Typt tekst als echte toetsaanslagen in het actieve invoerveld (zoals DataWedge). */
    private void typeText(String text, boolean enter) {
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

    // ---------- verborgen beheermenu ----------

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float spot = SAME_SPOT_DP * getResources().getDisplayMetrics().density;
            long now = SystemClock.uptimeMillis();
            boolean same = Math.abs(ev.getX() - tapX) < spot && Math.abs(ev.getY() - tapY) < spot;
            if (now - lastTap > MAX_TAP_GAP_MS || !same) { tapCount = 0; tapX = ev.getX(); tapY = ev.getY(); }
            lastTap = now;
            if (++tapCount >= prefs.getInt("menu_taps", 10)) { tapCount = 0; askPin(); }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void askPin() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("Beheer-PIN").setView(in)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (in.getText().toString().equals(pin())) adminMenu();
                    else Toast.makeText(MainActivity.this, "Onjuiste PIN", Toast.LENGTH_SHORT).show();
                }
            }).setNegativeButton("Annuleren", null).show();
    }

    private interface Choice { void chosen(int which); }

    private void list(String title, String[] items, final Choice c, final Runnable back) {
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title)
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { c.chosen(w); }
            });
        if (back != null) b.setNegativeButton("Terug", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { back.run(); }
        }); else b.setNegativeButton("Sluiten", null);
        b.show();
    }

    private void single(String title, final String[] labels, final String[] values, String current,
                        final String key, final Runnable back) {
        int sel = java.util.Arrays.asList(values).indexOf(current);
        new AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, sel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putString(key, values[w]).apply();
                    d.dismiss();
                    applyRuntimeSettings();
                    back.run();
                }
            }).setNegativeButton("Terug", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { back.run(); }
            }).show();
    }

    private void input(String title, String value, int type, final Choice ok, final Runnable back) {
        final EditText in = new EditText(this);
        in.setInputType(type);
        in.setText(value);
        in.setSelection(in.getText().length());
        new AlertDialog.Builder(this).setTitle(title).setView(in)
            .setPositiveButton("Opslaan", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { inputValue = in.getText().toString().trim(); ok.chosen(0); }
            }).setNegativeButton("Terug", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { back.run(); }
            }).show();
    }
    private String inputValue = "";

    private static String onOff(boolean b) { return b ? "aan" : "uit"; }

    private void adminMenu() {
        String url = homeUrl().isEmpty() ? "(niet ingesteld)" : resolvedUrl();
        String[] items = {
            "Website-adres wijzigen\n" + url,
            "NFC-instellingen\n" + labelOf(NFC_MODE_L, NFC_MODE_V, prefs.getString("nfc_mode", "both")),
            "Overige instellingen",
            "Pagina herladen",
            "Cache en cookies wissen",
            "App sluiten (naar Android-instellingen)",
            "Kiosk-modus definitief uitschakelen"
        };
        list("WebKiosk " + appVersion() + " · SN " + serial() + (isOwner() ? "" : " · geen device owner"), items, new Choice() {
            public void chosen(int w) {
                switch (w) {
                    case 0: editUrl(); break;
                    case 1: nfcMenu(); break;
                    case 2: otherMenu(); break;
                    case 3: loadHome(); break;
                    case 4: web.clearCache(true); CookieManager.getInstance().removeAllCookies(null);
                            WebStorage.getInstance().deleteAllData(); loadHome();
                            toast("Cache en cookies gewist"); break;
                    case 5: confirm("App sluiten?", "De kiosk wordt tijdelijk verlaten. Open WebKiosk opnieuw (of herstart het toestel) om terug te gaan.",
                                new Runnable() { public void run() { leaveKiosk(); } }); break;
                    case 6: confirm("Kiosk-modus uitschakelen?", "Het toestel wordt weer een normaal Android-toestel. Opnieuw inrichten kan alleen via het provision-script.",
                                new Runnable() { public void run() { removeOwner(); } }); break;
                }
            }
        }, null);
    }

    private static final String[] NFC_MODE_L = {"Uit", "Typen in invoerveld", "JavaScript-event", "Typen + JavaScript-event"};
    private static final String[] NFC_MODE_V = {"off", "keyboard", "event", "both"};
    private static final String[] NFC_FMT_L = {"Hex (04A1B2C3)", "Hex omgekeerd", "Hex met dubbele punten (04:A1:B2)", "Decimaal", "Decimaal omgekeerd"};
    private static final String[] NFC_FMT_V = {"hex", "hex_reversed", "hex_colon", "dec", "dec_reversed"};
    private static final String[] NFC_VAL_L = {"Tag-ID (UID)", "Tekst/URL op de tag (anders UID)"};
    private static final String[] NFC_VAL_V = {"uid", "ndef"};

    private static String labelOf(String[] l, String[] v, String cur) {
        int i = java.util.Arrays.asList(v).indexOf(cur);
        return i < 0 ? cur : l[i];
    }

    private void nfcMenu() {
        final Runnable self = new Runnable() { public void run() { nfcMenu(); } };
        String hw = nfc == null ? "niet aanwezig" : (nfc.isEnabled() ? "aan" : "UIT in Android");
        String[] items = {
            "Modus\n" + labelOf(NFC_MODE_L, NFC_MODE_V, prefs.getString("nfc_mode", "both")),
            "Formaat tag-ID\n" + labelOf(NFC_FMT_L, NFC_FMT_V, prefs.getString("nfc_uid_format", "hex")),
            "Wat typen\n" + labelOf(NFC_VAL_L, NFC_VAL_V, prefs.getString("nfc_keyboard_value", "uid")),
            "Enter na typen: " + onOff(prefs.getBoolean("nfc_send_enter", true)),
            "NFC testen",
            "NFC in Android aan/uit zetten"
        };
        list("NFC (hardware: " + hw + ")", items, new Choice() {
            public void chosen(int w) {
                switch (w) {
                    case 0: single("NFC-modus", NFC_MODE_L, NFC_MODE_V, prefs.getString("nfc_mode", "both"), "nfc_mode", self); break;
                    case 1: single("Formaat tag-ID", NFC_FMT_L, NFC_FMT_V, prefs.getString("nfc_uid_format", "hex"), "nfc_uid_format", self); break;
                    case 2: single("Wat typen", NFC_VAL_L, NFC_VAL_V, prefs.getString("nfc_keyboard_value", "uid"), "nfc_keyboard_value", self); break;
                    case 3: prefs.edit().putBoolean("nfc_send_enter", !prefs.getBoolean("nfc_send_enter", true)).apply(); nfcMenu(); break;
                    case 4: nfcTest(self); break;
                    case 5: kioskSuspended = true;
                            try { stopLockTask(); } catch (Exception ignored) {}
                            try { startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); }
                            catch (Exception e) { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
                            break;
                }
            }
        }, new Runnable() { public void run() { adminMenu(); } });
    }

    private void nfcTest(final Runnable back) {
        if (nfc == null) { toast("Dit toestel heeft geen NFC"); back.run(); return; }
        startNfcForced();
        nfcTestDialog = new AlertDialog.Builder(this).setTitle("NFC testen")
            .setMessage("Houd een tag tegen de achterkant van het toestel…")
            .setNegativeButton("Terug", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { back.run(); }
            }).create();
        nfcTestDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) { nfcTestDialog = null; applyRuntimeSettings(); }
        });
        nfcTestDialog.show();
    }

    private void otherMenu() {
        final Runnable self = new Runnable() { public void run() { otherMenu(); } };
        final int reload = prefs.getInt("reload_minutes", 0);
        String[] items = {
            "Beheer-PIN wijzigen",
            "Aantal tikken voor dit menu: " + prefs.getInt("menu_taps", 10),
            "Scherm altijd aan: " + onOff(prefs.getBoolean("keep_screen_on", true)),
            "Automatisch herladen: " + (reload > 0 ? "elke " + reload + " min" : "uit"),
            "SSL-fouten negeren: " + onOff(prefs.getBoolean("ignore_ssl_errors", false)),
            "Wifi-instellingen"
        };
        list("Overige instellingen", items, new Choice() {
            public void chosen(int w) {
                switch (w) {
                    case 0: input("Nieuwe PIN (min. 4 cijfers)", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                            new Choice() { public void chosen(int x) {
                                if (inputValue.length() >= 4) { prefs.edit().putString("pin", inputValue).apply(); toast("PIN gewijzigd"); }
                                else toast("PIN moet minimaal 4 cijfers zijn");
                                otherMenu(); } }, self); break;
                    case 1: input("Aantal tikken (5–20)", String.valueOf(prefs.getInt("menu_taps", 10)), InputType.TYPE_CLASS_NUMBER,
                            new Choice() { public void chosen(int x) {
                                try { int n = Integer.parseInt(inputValue); if (n >= 5 && n <= 20) prefs.edit().putInt("menu_taps", n).apply(); else toast("Kies 5 t/m 20"); }
                                catch (NumberFormatException e) { toast("Ongeldig getal"); }
                                otherMenu(); } }, self); break;
                    case 2: prefs.edit().putBoolean("keep_screen_on", !prefs.getBoolean("keep_screen_on", true)).apply();
                            applyRuntimeSettings(); otherMenu(); break;
                    case 3: input("Herladen elke X minuten (0 = uit)", String.valueOf(reload), InputType.TYPE_CLASS_NUMBER,
                            new Choice() { public void chosen(int x) {
                                try { prefs.edit().putInt("reload_minutes", Math.max(0, Integer.parseInt(inputValue))).apply(); }
                                catch (NumberFormatException e) { toast("Ongeldig getal"); }
                                schedulePeriodicReload(); otherMenu(); } }, self); break;
                    case 4: prefs.edit().putBoolean("ignore_ssl_errors", !prefs.getBoolean("ignore_ssl_errors", false)).apply(); otherMenu(); break;
                    case 5: kioskSuspended = true;
                            try { stopLockTask(); } catch (Exception ignored) {}
                            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); break;
                }
            }
        }, new Runnable() { public void run() { adminMenu(); } });
    }

    private void confirm(String title, String msg, final Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("Ja", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { yes.run(); }
            }).setNegativeButton("Nee", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { adminMenu(); }
            }).show();
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    private void applyRuntimeSettings() {
        if (prefs.getBoolean("keep_screen_on", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (nfc != null) try { nfc.disableReaderMode(this); } catch (Exception ignored) {}
        startNfc();
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

    private void editUrl() {
        input("Website-adres ({serial} = serienummer)", homeUrl().isEmpty() ? DEFAULT_URL : homeUrl(),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, new Choice() {
                public void chosen(int x) {
                    String u = inputValue;
                    if (!u.isEmpty() && !u.matches("(?i)^(https?|file|about):.*")) u = "https://" + u;
                    prefs.edit().putString("url", u).apply();
                    web.clearHistory();
                    loadHome();
                    toast("Website-adres opgeslagen");
                }
            }, new Runnable() { public void run() { adminMenu(); } });
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

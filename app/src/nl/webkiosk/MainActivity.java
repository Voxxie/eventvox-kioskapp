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
    static final String CONFIG_FILE = "webkiosk.properties";
    static final String PREFS = "kiosk";
    static final String DEFAULT_URL = "https://eventvox.nl/scanterminal?serial={serial}";
    static final int SAME_SPOT_DP = 48;
    static final long MAX_TAP_GAP_MS = 700, RETRY_MS = 10000;

    WebView web;
    SharedPreferences prefs;
    DevicePolicyManager dpm;
    ComponentName admin;
    final Handler handler = new Handler(Looper.getMainLooper());
    final NetworkScreen net = new NetworkScreen(this);
    final FindMe findMe = new FindMe(this);
    final ScreenRefresh screenRefresh = new ScreenRefresh(this);
    final AdminScreen adminScreen = new AdminScreen(this);
    int tapCount = 0;
    long lastTap = 0;
    float tapX, tapY;
    boolean nfcTesting = false;
    FrameLayout root;
    WebView adminView;
    boolean adminUnlocked = false;
    int pinFails = 0;
    long pinLockedUntil = 0;
    Updater.Info lastUpdateInfo;
    boolean kioskSuspended = false;
    boolean pageError = false;
    NfcAdapter nfc;
    Intent lastBattery;
    String lastBatterySig = "";
    final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            lastBattery = i;
            JSONObject b = Battery.batteryJson(i);
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

    final Runnable retry = new Runnable() { public void run() { loadHome(); } };
    final Runnable periodicReload = new Runnable() {
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
        wf.addAction(Intent.ACTION_SCREEN_OFF);
        wf.addAction(Intent.ACTION_USER_PRESENT);
        wf.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        registerReceiver(wakeReceiver, wf);
        registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
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
        applyProvisionExtras(); // na createWeb: een url laadt meteen
        loadHome();
        schedulePeriodicReload();
        handler.postDelayed(updateCheck, 60000); // eerste controle 1 minuut na start
        handler.postDelayed(net.offlineCheck, 5000);
        handler.postDelayed(nightlyCheck, 60000);
        handler.postDelayed(dimCheck, 15000);
        handler.postDelayed(clockCheck, 20000);
        KioskLog.add(this, "App gestart, versie " + appVersion() + (isOwner() ? "" : " (geen device owner)"));
        KioskLog.add(this, "WebView: " + webViewVersion());
    }

    /** De WebView van de website, met alles wat de app eraan toevoegt (opnieuw gemaakt na een crash) */
    WebView createWeb() {
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
                v.evaluateJavascript(Scripts.BATTERY_POLYFILL + Scripts.VIBRATE_POLYFILL, null); // zo vroeg mogelijk: vóór de scripts van de pagina
            }
            @Override public void onPageCommitVisible(WebView v, String url) {
                v.evaluateJavascript(Scripts.BATTERY_POLYFILL + Scripts.VIBRATE_POLYFILL, null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(Scripts.BATTERY_POLYFILL + Scripts.VIBRATE_POLYFILL, null);
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
            @JavascriptInterface public String getBattery() { return Battery.batteryJson(lastBattery).toString(); }
            @JavascriptInterface public String getSerial() { return serial(); }
            /** pattern: JSON-array in ms (aan, uit, aan, ...) of één getal; [] of 0 stopt het trillen. */
            @JavascriptInterface public boolean vibrate(String pattern) { return doVibrate(pattern); }
            /** De wifi-verbinding: { connected, level 0-4, rssi (dBm), ssid?, link_speed (Mbps), frequency (MHz) } */
            /** Wifi; null als het toestel via de netwerkkabel internet heeft (zoals EventVOX-Pi): geen "geen wifi" in de terminal */
            @JavascriptInterface public String getWifi() { return "ethernet".equals(uplink()) ? "null" : wifiJson().toString(); }
            /** Het netwerk: { uplink: ethernet|wifi|null, ethernet: { connected, speed_mbps? }|null }; de scanterminal toont via de kabel een kabel-icoon */
            @JavascriptInterface public String getNetwork() {
                JSONObject o = new JSONObject();
                try {
                    String up = uplink();
                    o.put("uplink", up == null ? JSONObject.NULL : up);
                    JSONObject eth = ethernetJson();
                    o.put("ethernet", eth == null ? JSONObject.NULL : eth);
                } catch (Exception ignored) {}
                return o.toString();
            }
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
            /** Wifi-netwerken uit EventVOX (admin-tenant): { version, networks: [{ ssid, security, password, hidden, priority }] } */
            @JavascriptInterface public void setWifiProfiles(final String json) { runOnUiThread(new Runnable() { public void run() { applyEventvoxWifi(json); } }); }
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

    void readIntentConfig(Intent i) {
        if (i == null || i.getExtras() == null) return;
        SharedPreferences.Editor e = prefs.edit();
        for (String key : new String[]{"url", "pin"}) {
            String v = i.getStringExtra(key);
            if (v != null && !v.trim().isEmpty()) e.putString(key, v.trim());
        }
        e.apply();
    }

    void readFileConfig() {
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

    /**
     * Instellingen uit de QR-code waarmee het toestel is ingericht (AdminReceiver), één keer, via dezelfde controle als
     * het beheerscherm. Alleen de namen in het logboek, nooit de waarden (PIN).
     */
    void applyProvisionExtras() {
        String json = prefs.getString("provision_extras", null);
        if (json == null) return;
        prefs.edit().remove("provision_extras").commit();
        java.util.List<String> applied = new java.util.ArrayList<>(), refused = new java.util.ArrayList<>();
        try {
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.equals("update_url")) { refused.add(key); continue; }
                String err = adminScreen.applySetting(key, o.optString(key, "").trim());
                if (err == null) applied.add(key); else refused.add(key);
            }
        } catch (Exception ignored) {}
        KioskLog.add(this, "Instellingen uit de inricht-QR-code: " + (applied.isEmpty() ? "geen" : android.text.TextUtils.join(", ", applied))
            + (refused.isEmpty() ? "" : " (geweigerd: " + android.text.TextUtils.join(", ", refused) + ")"));
    }

    String homeUrl() { return prefs.getString("url", DEFAULT_URL); }

    /** URL met {serial}/{model}/{version} ingevuld; eindigt de URL op "serial=" dan wordt het serienummer erachter gezet. */
    String resolvedUrl() {
        String u = homeUrl();
        String sn = Uri.encode(serial());
        if (u.endsWith("serial=")) u = u + sn;
        return u.replace("{serial}", sn).replace("{model}", Uri.encode(Build.MODEL)).replace("{version}", Uri.encode(appVersion()));
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    String serial() {
        String s = null;
        if (Build.VERSION.SDK_INT >= 26) {
            try { s = (String) Build.class.getMethod("getSerial").invoke(null); } catch (Exception ignored) {}
        }
        if (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s)) s = Build.SERIAL;
        if (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s))
            s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return s == null ? "" : s;
    }

    String pin() { return prefs.getString("pin", "1234"); }

    // ---------- laden ----------

    void loadHome() {
        handler.removeCallbacks(retry);
        String url = homeUrl();
        if (url.isEmpty()) {
            web.loadDataWithBaseURL(null, Scripts.page("Geen URL ingesteld",
                "Tik 10x snel op dezelfde plek en kies <b>Website-adres wijzigen</b>,<br>of plaats " + CONFIG_FILE + " op de interne opslag."),
                "text/html", "UTF-8", null);
        } else {
            web.loadUrl(resolvedUrl());
        }
    }

    void showError(String desc) {
        if (pageError) return;
        KioskLog.add(this, "Pagina laadt niet: " + desc);
        pageError = true;
        web.loadDataWithBaseURL(null, Scripts.page("Geen verbinding",
            "De pagina kon niet worden geladen (" + Scripts.escape(desc) + ").<br>Opnieuw proberen over 10 seconden&hellip;"),
            "text/html", "UTF-8", null);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    void schedulePeriodicReload() {
        handler.removeCallbacks(periodicReload);
        int min = prefs.getInt("reload_minutes", 0);
        if (min > 0) handler.postDelayed(periodicReload, min * 60000L);
    }

    // ---------- kiosk / device owner ----------

    boolean isOwner() { return dpm != null && dpm.isDeviceOwnerApp(getPackageName()); }

    void applyDeviceOwnerPolicies() {
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

    void enterKiosk() {
        if (kioskSuspended || !isOwner()) return;
        try { startLockTask(); } catch (Exception ignored) {}
    }

    void leaveKiosk() {
        kioskSuspended = true;
        try { if (isOwner()) dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME); } catch (Exception ignored) {}
        try { stopLockTask(); } catch (Exception ignored) {}
        if (isOwner() && Build.VERSION.SDK_INT >= 23) {
            try { dpm.setStatusBarDisabled(admin, false); } catch (Exception ignored) {}
        }
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) {}
    }

    static final int IMMERSIVE =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

    void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
    }

    /*
     * Na het toetsenbord blijft op Android 6-8 de navigatiebalk (met terugknop) staan: het toetsenbord haalt de
     * immersive-vlaggen weg en Android zet ze niet terug. Daarom: zodra de balken weer zichtbaar worden, of het
     * toetsenbord dichtgaat, de balken opnieuw verbergen (niet zolang het toetsenbord open is: dan hoort de
     * balk er te zijn om het toetsenbord te kunnen sluiten).
     */
    boolean keyboardShown = false;

    final Runnable rehideBars = new Runnable() {
        public void run() {
            if (keyboardVisible()) { handler.postDelayed(this, 1000); return; }
            hideSystemUi();
        }
    };

    boolean keyboardVisible() {
        View root = getWindow().getDecorView();
        android.graphics.Rect r = new android.graphics.Rect();
        root.getWindowVisibleDisplayFrame(r);
        int screen = root.getRootView().getHeight();
        return screen > 0 && screen - r.bottom > screen * 0.15;
    }

    void watchSystemBars() {
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
        if (pausedAt > 0 && SystemClock.uptimeMillis() - pausedAt > 60000) scheduleWakeCheck();
        schedulePeriodicNfcRefresh();
    }

    @Override protected void onPause() {
        resumed = false;
        pausedAt = SystemClock.uptimeMillis();
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
        if (i != null && i.getBooleanExtra("provisioned", false)) { applyDeviceOwnerPolicies(); applyProvisionExtras(); loadHome(); return; }
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
        if (adminView != null) { adminScreen.adminEmit("back", new JSONObject()); return; }
        if (net.wifiView != null) { net.hideWifiView(); return; }
        if (web.canGoBack()) web.goBack();
        // anders: niets doen, app kan niet via terug-knop worden verlaten
    }

    // ---------- wifi-profielen ----------

    /** Wifi-profielen uit het config-bestand zetten (alleen als device owner; alleen bij een gewijzigd bestand). */
    void applyWifi(final Properties p) {
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
            // Nooit weghalen: netwerken via een wifi-QR-code en die uit EventVOX
            java.util.Set<String> keep = new java.util.HashSet<>(prefs.getStringSet("wifi_qr_ssids", new java.util.HashSet<String>()));
            keep.addAll(prefs.getStringSet("wifi_ev_ssids", new java.util.HashSet<String>()));
            try { result = WifiProfiles.apply(MainActivity.this, list, previous, removeOthers, applied, keep); }
            catch (Exception ex) { result = "fout: " + ex.getMessage(); }
            WifiProfiles.lockdown(MainActivity.this, dpm, admin, lockdown);
            prefs.edit().putStringSet("wifi_ssids", applied)
                .putString("wifi_result", result + " (" + new java.text.SimpleDateFormat("d-M HH:mm").format(new java.util.Date()) + ")").apply();
        }}).start();
    }

    /*
     * Wifi-netwerken uit EventVOX (beheerd in de admin-tenant, voor alle of gekozen organisaties). De backend stuurt ze
     * alleen als de versie anders is dan die wij melden (wifi_version in getStatus). Alleen de netwerken die eerder uit
     * EventVOX kwamen worden weggehaald; die uit het config-bestand of een wifi-QR-code blijven. Nooit wachtwoorden in
     * het logboek of de status.
     */
    boolean eventvoxWifiBusy = false;

    void applyEventvoxWifi(String json) {
        final String version;
        final java.util.List<WifiProfiles.Profile> list = new java.util.ArrayList<>();
        try {
            JSONObject o = new JSONObject(json);
            version = o.getString("version");
            if (!version.matches("[0-9a-f]{16}") || version.equals(prefs.getString("wifi_ev_version", "")) || eventvoxWifiBusy) return;
            JSONArray nets = o.optJSONArray("networks");
            for (int i = 0; nets != null && i < nets.length() && i < WifiProfiles.MAX; i++) {
                JSONObject n = nets.getJSONObject(i);
                WifiProfiles.Profile w = new WifiProfiles.Profile();
                w.ssid = n.optString("ssid", "").trim();
                w.password = n.optString("password", "");
                w.security = n.optString("security", "WPA");
                w.hidden = n.optBoolean("hidden", false);
                w.priority = n.optInt("priority", 0);
                if (!w.ssid.isEmpty() && w.security.matches("WPA|WEP|OPEN")) list.add(w);
            }
        } catch (Exception e) { return; }
        if (!isOwner()) {
            prefs.edit().putString("wifi_ev_result", "Niet gezet: de app is geen device owner").apply();
            return; // versie niet opslaan: als device owner wordt het opnieuw geprobeerd
        }
        eventvoxWifiBusy = true;
        new Thread(new Runnable() { public void run() {
            java.util.Set<String> previous = prefs.getStringSet("wifi_ev_ssids", new java.util.HashSet<String>());
            java.util.Set<String> keep = new java.util.HashSet<>(prefs.getStringSet("wifi_qr_ssids", new java.util.HashSet<String>()));
            keep.addAll(prefs.getStringSet("wifi_ssids", new java.util.HashSet<String>()));
            java.util.Set<String> applied = new java.util.HashSet<>();
            String result;
            try { result = WifiProfiles.apply(MainActivity.this, list, previous, false, applied, keep); }
            catch (Exception ex) { result = "fout: " + ex.getMessage(); }
            String names = applied.isEmpty() ? "" : " (" + android.text.TextUtils.join(", ", new java.util.TreeSet<>(applied)) + ")";
            prefs.edit().putStringSet("wifi_ev_ssids", applied).putString("wifi_ev_version", version)
                .putString("wifi_ev_result", (list.isEmpty() ? "Geen netwerken uit EventVOX" : result) + names).commit();
            KioskLog.add(MainActivity.this, "Wifi uit EventVOX: " + result + names);
            eventvoxWifiBusy = false;
        }}).start();
    }

    // ---------- NFC ----------

    void startNfc() {
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
    boolean resumed = false;
    String nfcRestartReason = "";
    long nfcLastRestart = 0;
    int nfcRestarts = 0;
    final Runnable nfcRestart = new Runnable() {
        public void run() {
            if (!resumed || nfc == null) return;
            try { nfc.disableReaderMode(MainActivity.this); } catch (Exception ignored) {}
            startNfc();
            nfcLastRestart = System.currentTimeMillis();
            nfcRestarts++;
        }
    };
    final Runnable nfcPeriodic = new Runnable() {
        public void run() {
            if (resumed && System.currentTimeMillis() - lastTagTime > 3000) restartNfc("periodiek");
            schedulePeriodicNfcRefresh();
        }
    };

    void restartNfc(String reason) {
        if (nfc == null) return;
        nfcRestartReason = reason;
        handler.removeCallbacks(nfcRestart);
        handler.postDelayed(nfcRestart, 400);
        handler.postDelayed(nfcRestart, 1500); // tweede poging: NFC-chip is soms nog niet klaar
    }

    void schedulePeriodicNfcRefresh() {
        handler.removeCallbacks(nfcPeriodic);
        int sec = prefs.getInt("nfc_refresh_seconds", 0);
        if (sec > 0) handler.postDelayed(nfcPeriodic, sec * 1000L);
    }

    long screenOffAt = 0;

    static String durationText(long ms) {
        long min = ms / 60000;
        return min >= 60 ? (min / 60) + " u " + (min % 60) + " min" : min + " min";
    }

    final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(a)) {
                if (i.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, 0) == NfcAdapter.STATE_ON) restartNfc("NFC aan");
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOffAt = System.currentTimeMillis();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                // In het logboek hoe lang hij sliep (vanaf een minuut): zo is een wit scherm terug te vinden
                long slept = screenOffAt > 0 ? System.currentTimeMillis() - screenOffAt : 0;
                if (slept >= 60000) KioskLog.add(MainActivity.this, "Scherm aan na " + durationText(slept) + " slaapstand");
                screenOffAt = 0;
                restartNfc("scherm aan");
                scheduleWakeCheck();
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                restartNfc("ontgrendeld");
                scheduleWakeCheck();
            }
        }
    };

    long lastTagTime = 0;
    String lastTagUid = "";

    void startNfcForced() {
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
    void handleTag(Tag tag) {
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

    void deliverNfc(JSONObject o, String kbValue) {
        if (screenRefresh.view != null) { screenRefresh.stop("gestopt met een NFC-tag"); return; } // niet naar de pagina
        if (nfcTesting && adminView != null) { // test in het beheerscherm: niet aan de website doorgeven
            try { o.put("typed", kbValue); } catch (Exception ignored) {}
            adminScreen.adminEmit("nfc", o);
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
    JSONObject wifiJson() {
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

    /** De netwerkkabel (zoals een Zebra CC600): { connected, speed_mbps? }; null als het toestel geen netwerkpoort heeft */
    @SuppressWarnings("deprecation")
    JSONObject ethernetJson() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_ETHERNET);
            if (ni == null) return null;
            JSONObject o = new JSONObject();
            o.put("connected", ni.isConnected());
            if (ni.isConnected()) {
                try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/net/eth0/speed"))) {
                    int speed = Integer.parseInt(r.readLine().trim());
                    if (speed > 0) o.put("speed_mbps", speed);
                } catch (Exception ignored) {}
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Waarover het toestel nu internet heeft: "ethernet", "wifi" of null (geen, of iets anders zoals mobiel) */
    @SuppressWarnings("deprecation")
    String uplink() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return null;
            if (ni.getType() == android.net.ConnectivityManager.TYPE_ETHERNET) return "ethernet";
            if (ni.getType() == android.net.ConnectivityManager.TYPE_WIFI) return "wifi";
        } catch (Exception ignored) {}
        return null;
    }

    String lastWifiSig = "";
    final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            JSONObject w = wifiJson();
            // Alleen bij een andere stand, niet bij elke dBm: anders tientallen events per minuut
            String sig = w.optBoolean("connected") + "|" + w.optInt("level", -1) + "|" + w.optString("ssid");
            if (web == null || sig.equals(lastWifiSig)) return;
            // Via de netwerkkabel: wifi doet er niet toe (geen "geen wifi" in de terminal)
            if ("ethernet".equals(uplink())) { lastWifiSig = sig; return; }
            lastWifiSig = sig;
            KioskLog.add(MainActivity.this, w.optBoolean("connected") ? "Wifi verbonden" + (w.has("ssid") ? " met " + w.optString("ssid") : "")
                + ", niveau " + w.optInt("level") + " (" + w.optInt("rssi") + " dBm)" : "Wifi niet verbonden");
            web.evaluateJavascript("(function(d){try{window.dispatchEvent(new CustomEvent('webkiosk-wifi',{detail:d}));}catch(e){}"
                + "if(typeof window.onWebKioskWifi==='function')window.onWebKioskWifi(d);})(" + w + ");", null);
        }
    };

    // ---------- na de slaapstand: leeft de pagina nog? ----------

    /*
     * Soms bleef het scherm na een tijd slaapstand wit. Bij scherm aan, ontgrendelen en terug na meer dan een minuut:
     * de pagina een teken van leven vragen. Geen antwoord binnen 3 s, of een lege pagina: een nieuwe WebView met de
     * website (zoals na een crash; de scanterminal bewaart zijn wachtrij zelf). Wel antwoord: alleen opnieuw tekenen.
     */
    long pausedAt = 0;
    long lastWakeCheck = 0;

    void scheduleWakeCheck() {
        long now = SystemClock.uptimeMillis();
        if (now - lastWakeCheck < 5000) return;
        lastWakeCheck = now;
        handler.postDelayed(new Runnable() { public void run() { wakeCheck(); } }, 800);
    }

    void wakeCheck() {
        final WebView w = web;
        if (w == null || adminView != null) return;
        final boolean[] answered = {false};
        final Runnable noAnswer = new Runnable() {
            public void run() {
                if (answered[0] || w != web) return;
                rebuildWeb("pagina reageert niet na slaapstand", false);
            }
        };
        handler.postDelayed(noAnswer, 3000);
        try {
            w.evaluateJavascript("(function(){var b=document.body;return document.readyState+'|'+(b?b.childElementCount:0);})()",
                new ValueCallback<String>() {
                    public void onReceiveValue(String v) {
                        answered[0] = true;
                        handler.removeCallbacks(noAnswer);
                        if (w != web) return;
                        String s = v == null ? "" : v.replace("\"", "");
                        if (s.equals("complete|0")) {
                            rebuildWeb("lege pagina na slaapstand", false);
                        } else {
                            w.invalidate(); // het beeld opnieuw laten tekenen
                            w.requestLayout();
                        }
                    }
                });
        } catch (Exception e) {
            handler.removeCallbacks(noAnswer);
            rebuildWeb("pagina reageert niet na slaapstand", false);
        }
    }

    /** Welke WebView de app gebruikt (pakket en versie), voor het logboek: verandert die, dan staat dat in de Diagnose */
    String webViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Object info = WebView.class.getMethod("getCurrentWebViewPackage").invoke(null);
                if (info instanceof android.content.pm.PackageInfo) {
                    android.content.pm.PackageInfo p = (android.content.pm.PackageInfo) info;
                    return p.packageName + " " + p.versionName;
                }
            }
            String ua = WebSettings.getDefaultUserAgent(this);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(ua);
            return m.find() ? "Chrome " + m.group(1) : "onbekend";
        } catch (Exception e) {
            return "onbekend";
        }
    }

    // ---------- herstel na een crash van de pagina ----------

    int renderCrashes = 0;
    long lastCrashAt = 0;
    final java.util.LinkedList<Long> recentCrashes = new java.util.LinkedList<>();

    /** Een nieuwe WebView in plaats van de oude (na een crash, of 's nachts om geheugen vrij te maken) en de website laden */
    void rebuildWeb(String reason, boolean crash) {
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
    static final String TERMINAL_TOKEN_KEY = "eventvox.terminal.token";
    long tokenRestoredAt = 0;

    static String originOf(String url) {
        try { java.net.URL u = new java.net.URL(url); return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""); }
        catch (Exception e) { return null; }
    }

    void checkTerminalToken(final WebView v, final String url) {
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
    final Runnable nightlyCheck = new Runnable() {
        public void run() {
            handler.postDelayed(this, 60000);
            if (!prefs.getBoolean("nightly_reload", true)) return;
            java.util.Calendar c = java.util.Calendar.getInstance();
            int hour = prefs.getInt("nightly_hour", 4);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(c.getTime());
            if (today.equals(prefs.getString("nightly_done", ""))) return;
            int h = c.get(java.util.Calendar.HOUR_OF_DAY);
            if (h < hour || h >= hour + 3) return; // alleen in de 3 uur erna; daarna wachten tot de volgende nacht
            if (SystemClock.uptimeMillis() - lastActivity < 300000 || adminView != null || net.wifiView != null) return;
            // Niet met het scherm uit of zonder netwerk: een TC51 in de slaapstand zet de wifi uit, en een pagina die
            // dan opnieuw laadt bleef hangen (wit scherm bij het oppakken). Dan deze nacht overslaan of later proberen.
            if (!screenOn() || !net.isOnline()) return;
            prefs.edit().putString("nightly_done", today).apply();
            rebuildWeb("nachtelijk herladen", false);
        }
    };

    /** Staat het scherm aan (niet in de slaapstand)? */
    boolean screenOn() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Exception e) { return true; }
    }

    // ---------- volume vast ----------

    /*
     * volume_lock: percentage (standaard 0 = uit: de volumeknoppen werken) voor het geluid van de pagina (STREAM_MUSIC: Web Audio, de piep
     * van de scanterminal). De volumeknoppen vangt dispatchKeyEvent af; verandert het volume toch (een andere app, de
     * instellingen), dan zet volumeReceiver het terug.
     * Nooit UserManager.DISALLOW_ADJUST_VOLUME gebruiken: die zet het hoofdvolume op stil ("the master volume will be
     * muted"). Versies tot 0.1.x deden dat; deze haalt de beperking weg en zet het dempen uit.
     */
    @SuppressWarnings("deprecation")
    void applyVolume() {
        int pct = prefs.getInt("volume_lock", 0);
        try {
            if (isOwner()) {
                dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_ADJUST_VOLUME);
                dpm.setMasterVolumeMuted(admin, false);
            }
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (pct <= 0 || am == null) return;
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int want = Math.max(1, Math.round(max * Math.min(100, pct) / 100f));
            if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) != want) {
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, want, 0);
            }
            if (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0);
            }
        } catch (Exception ignored) {}
    }

    /** Het volume is veranderd (niet via de knoppen, die vangt de app af): terug naar volume_lock */
    final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!volumeLocked()) return;
            handler.removeCallbacks(volumeRestore);
            handler.postDelayed(volumeRestore, 300);
        }
    };
    final Runnable volumeRestore = new Runnable() { public void run() { applyVolume(); } };

    boolean volumeLocked() { return prefs.getInt("volume_lock", 0) > 0; }

    // ---------- scherm dimmen ----------

    long lastActivity = SystemClock.uptimeMillis();
    boolean dimmed = false;

    /** Aanraking, toets, scan of NFC: het toestel wordt gebruikt (fel scherm, geen update of nachtelijk herladen) */
    void markActivity() {
        lastActivity = SystemClock.uptimeMillis();
        lastTouch = lastActivity;
        if (dimmed) runOnUiThread(new Runnable() { public void run() { setBrightness(-1f); } });
    }

    /**
     * Het scherm aanzetten en aan houden (ms), ook als het toestel in slaap was: voor Zoek mij en Scherm opfrissen. Zonder
     * dit liepen die met het scherm uit (piepen wel, maar een zwart scherm).
     */
    PowerManager.WakeLock screenWake;
    @SuppressWarnings("deprecation")
    void wakeScreen(long ms) {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (screenWake != null && screenWake.isHeld()) screenWake.release();
            screenWake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "webkiosk:scherm");
            screenWake.acquire(ms);
        } catch (Exception ignored) {}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }

    void releaseScreen() {
        try { if (screenWake != null && screenWake.isHeld()) screenWake.release(); } catch (Exception ignored) {}
        screenWake = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }

    void setBrightness(float level) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = level; // -1: zoals Android hem heeft
        getWindow().setAttributes(lp);
        dimmed = level >= 0;
    }

    /** Alleen met "scherm altijd aan" (anders gaat het scherm vanzelf uit): na dim_minutes zonder gebruik dimmen */
    final Runnable dimCheck = new Runnable() {
        public void run() {
            handler.postDelayed(this, 15000);
            if (screenRefresh.view != null) return; // tijdens Scherm opfrissen: volle helderheid
            int minutes = prefs.getInt("dim_minutes", 2);
            boolean want = prefs.getBoolean("keep_screen_on", false) && minutes > 0 && adminView == null && net.wifiView == null && findMe.locateView == null
                && SystemClock.uptimeMillis() - lastActivity > minutes * 60000L;
            if (want && !dimmed) setBrightness(Math.max(1, Math.min(100, prefs.getInt("dim_level", 15))) / 100f);
            else if (!want && dimmed) setBrightness(-1f);
        }
    };

    // ---------- status en diagnose voor EventVOX ----------

    volatile boolean diagnosticsPending = false;
    final long startedAt = System.currentTimeMillis();

    static String iso(long ms) {
        if (ms <= 0) return null;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT);
        return f.format(new java.util.Date(ms));
    }

    JSONObject statusJson() {
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
            // Ook leeg: zo weet EventVOX dat deze versie wifi-netwerken kan ontvangen
            o.put("wifi_version", prefs.getString("wifi_ev_version", ""));
            String wr = prefs.getString("wifi_ev_result", "");
            if (!wr.isEmpty()) o.put("wifi_result", wr);
            if (findMe.foundLocation != null && System.currentTimeMillis() - findMe.foundLocationAt < FindMe.LOCATION_SEND_MS) o.put("location", findMe.foundLocation);
            JSONObject w = wifiJson();
            w.remove("enabled");
            o.put("wifi", w);
            // De netwerkkabel en waarover het toestel internet heeft (zoals EventVOX-Pi)
            JSONObject eth = ethernetJson();
            if (eth != null) o.put("ethernet", eth);
            String up = uplink();
            if (up != null) o.put("uplink", up);
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

    LinearLayout bannerBox;
    TextView batteryBanner, clockBanner;

    TextView banner(String text, String color) {
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
    void updateBatteryBanner(JSONObject b) {
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
    static final int CLOCK_WARN_S = 120;
    volatile int clockSkew = 0;
    volatile long clockCheckedAt = 0;

    static String skewText(int skew) {
        int min = Math.round(Math.abs(skew) / 60f);
        String amount = min >= 120 ? Math.round(min / 60f) + " uur" : Math.max(1, min) + " min";
        return "loopt " + amount + (skew > 0 ? " voor" : " achter");
    }

    final Runnable clockCheck = new Runnable() {
        public void run() {
            handler.postDelayed(this, 15 * 60000L);
            final String url = resolvedUrl();
            if (!url.startsWith("http") || !net.isOnline()) return;
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

    void showClock(int skew, boolean wasOff) {
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
    void applyOrientation() {
        String o = prefs.getString("orientation", "portrait");
        setRequestedOrientation("auto".equals(o) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            : "landscape".equals(o) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    View splash;
    final Runnable splashTimeout = new Runnable() { public void run() { hideSplash(); } };

    /** Het EventVOX-logo tot de pagina geladen is (hoogstens 20 s), in plaats van een wit scherm */
    void showSplash() {
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

    void hideSplash() {
        handler.removeCallbacks(splashTimeout);
        final View s = splash;
        if (s == null) return;
        splash = null;
        s.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() { public void run() { root.removeView(s); } });
    }

    // ---------- bediening vanuit EventVOX: opdrachten en instellingen ----------

    void runCommand(String json) {
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
            case "locate": findMe.locate(); break;
            case "refresh_screen": screenRefresh.start(); break;
            default: KioskLog.add(this, "Onbekende opdracht: " + command);
        }
    }

    /** De app helemaal opnieuw starten (na 1,5 s, via de AlarmManager) */
    void restartApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 1, i, android.app.PendingIntent.FLAG_CANCEL_CURRENT);
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi);
        try { stopLockTask(); } catch (Exception ignored) {}
        finishAffinity();
        handler.postDelayed(new Runnable() { public void run() { android.os.Process.killProcess(android.os.Process.myPid()); } }, 300);
    }

    /** Nu bijwerken: niet wachten tot het toestel rustig is */
    void updateNow() {
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
    void applyRemoteSettings(String json) {
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
                    String err = adminScreen.applySetting(key, String.valueOf(values.get(key)));
                    if (err == null) applied.add(key); else KioskLog.add(this, "Instelling uit EventVOX geweigerd: " + key + " (" + err + ")");
                }
            }
            java.util.Collections.sort(applied);
            prefs.edit().putInt("remote_settings_version", version)
                .putString("remote_settings_keys", android.text.TextUtils.join(", ", applied)).apply();
            KioskLog.add(this, applied.isEmpty() ? "Instellingen uit EventVOX: weer die van het toestel" : "Instellingen uit EventVOX toegepast: " + android.text.TextUtils.join(", ", applied));
            if (adminView != null) adminScreen.adminEmit("refresh", new JSONObject());
        } catch (Exception ignored) {}
    }

    // ---------- trillen ----------

    /** Trillen volgens een webpatroon [aan, uit, aan, ...] (ms); uit met vibrate=false in de config of het beheerscherm. */
    @SuppressWarnings("deprecation")
    boolean doVibrate(String json) {
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
    void focusWeb() {
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
        // Scherm opfrissen: een toets of scan stopt het (en gaat niet naar de pagina)
        if (screenRefresh.view != null && !system) {
            if (e.getAction() == KeyEvent.ACTION_UP) screenRefresh.stop("gestopt met een toets of scan");
            return true;
        }
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && volumeLocked()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) applyVolume(); // vast volume: de knoppen doen niets
            return true;
        }
        if (system) return super.dispatchKeyEvent(e);
        if (net.wifiQrEnabled() && catchWifiKeys(e)) return true;
        return routeKey(e);
    }

    /** Naar de website (ook als die de focus kwijt is), of naar het beheer- of wifi-scherm als dat open is */
    boolean routeKey(KeyEvent e) {
        if (adminView == null && net.wifiView == null && web != null && !web.hasFocus()) {
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
    static final long WEDGE_GAP_MS = 100;
    final java.util.ArrayList<KeyEvent> heldKeys = new java.util.ArrayList<>();
    final StringBuilder heldText = new StringBuilder();
    long heldAt = 0;
    final Runnable heldTimeout = new Runnable() { public void run() { finishHeld(false); } };

    /** Codes die de app zelf afhandelt (nooit naar de website): wifi-QR-codes en beheer-QR-codes */
    static final String[] APP_PREFIXES = {"WIFI:", "EVXADMIN:"};

    static boolean wifiPrefix(String text) {
        String t = text.toUpperCase(java.util.Locale.ROOT);
        for (String p : APP_PREFIXES) if (t.length() <= p.length() ? p.startsWith(t) : t.startsWith(p)) return true;
        return false;
    }

    static boolean isAppCode(String text) {
        String t = text.toUpperCase(java.util.Locale.ROOT);
        for (String p : APP_PREFIXES) if (t.length() > p.length() && t.startsWith(p)) return true;
        return false;
    }

    /** Een code van de app: wifi of beheer */
    void handleAppCode(String text) {
        if (text.regionMatches(true, 0, "EVXADMIN:", 0, 9)) adminScreen.handleAdminQr(text);
        else net.handleWifiQr(text);
    }

    /** True als de toets is vastgehouden of als deel van een wifi-code is gebruikt */
    boolean catchWifiKeys(KeyEvent e) {
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

    boolean holdText(KeyEvent e, String chars, boolean holding, long now) {
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
    void finishHeld(boolean enter) {
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

    void flushHeld() {
        handler.removeCallbacks(heldTimeout);
        java.util.List<KeyEvent> keys = new java.util.ArrayList<>(heldKeys);
        heldKeys.clear();
        heldText.setLength(0);
        for (KeyEvent k : keys) routeKey(k);
    }

    // ---------- scanner (DataWedge) ----------

    /** scan_mode: keyboard (typen + Enter, standaard), event (alleen JavaScript-event webkiosk-scan), both, off */
    String scanMode() { return prefs.getString("scan_mode", "keyboard"); }

    /** datawedge: app (de app stelt zijn eigen DataWedge-profiel in, standaard) of off (DataWedge niet aanraken) */
    boolean manageDataWedge() { return !"off".equals(prefs.getString("datawedge", "app")); }

    int dwFailed = 0;
    String lastScan = "";

    void setupDataWedge() {
        if (!DataWedge.installed(this)) { prefs.edit().putString("dw_status", "DataWedge niet gevonden (geen Zebra?)").apply(); return; }
        if (!manageDataWedge()) { prefs.edit().putString("dw_status", "Niet door de app ingesteld: DataWedge gebruikt zijn eigen profielen").apply(); return; }
        dwFailed = 0;
        prefs.edit().putString("dw_status", "Profiel " + DataWedge.PROFILE + " wordt ingesteld…").apply();
        DataWedge.configure(this);
    }

    final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onDataWedge(i); }
    };
    final BroadcastReceiver scanReceiver2 = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onDataWedge(i); }
    };

    long lastScanAt = 0;
    String lastScanData = "";

    void onDataWedge(Intent i) {
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

    void deliverScan(String data, String symbology) {
        markActivity();
        if (screenRefresh.view != null) { screenRefresh.stop("gestopt met een scan"); return; } // niet naar de pagina
        if (isAppCode(data)) { handleAppCode(data); return; } // nooit naar de website
        lastScan = data + (symbology.isEmpty() ? "" : " (" + symbology + ")");
        JSONObject o = new JSONObject();
        try { o.put("data", data); o.put("symbology", symbology); } catch (Exception ignored) {}
        if (adminView != null) { adminScreen.adminEmit("scan", o); return; } // beheerscherm open: daar tonen, niet aan de website
        String mode = scanMode();
        if (mode.equals("event") || mode.equals("both")) {
            web.evaluateJavascript("(function(d){try{window.dispatchEvent(new CustomEvent('webkiosk-scan',{detail:d}));}catch(e){}"
                + "if(typeof window.onWebKioskScan==='function')window.onWebKioskScan(d);})(" + o + ");", null);
        }
        if (mode.equals("keyboard") || mode.equals("both")) typeText(data, true);
    }

    /** Typt tekst als echte toetsaanslagen in het actieve invoerveld (zoals DataWedge). */
    void typeText(String text, boolean enter) {
        // Scherm opfrissen: een scan of NFC-tag stopt het en gaat niet naar de pagina
        if (screenRefresh.view != null) { screenRefresh.stop("gestopt met een scan"); return; }
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

    String formatUid(byte[] id) {
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

    static byte[] reverse(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }

    static String hex(byte[] b, boolean rev) {
        if (rev) b = reverse(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static String[] decodeRecord(NdefRecord r) {
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

    long lastTouch = 0;
    volatile boolean updateBusy = false;
    static final long IDLE_BEFORE_INSTALL_MS = 120000;

    String updateUrl() {
        String u = prefs.getString("update_url", "");
        if (!u.isEmpty()) return u;
        return "beta".equals(prefs.getString("update_channel", "stable")) ? Updater.BETA_URL : Updater.DEFAULT_URL;
    }

    /** Bij het starten (1 minuut erna) en, alleen als update_check_hours > 0, daarna periodiek. */
    final Runnable updateCheck = new Runnable() {
        public void run() {
            handler.removeCallbacks(updateCheck);
            int hours = prefs.getInt("update_check_hours", 0);
            if (hours > 0) handler.postDelayed(updateCheck, hours * 3600000L);
            if (prefs.getBoolean("auto_update", true)) checkForUpdate(false);
        }
    };

    /** interactive=true: resultaat tonen in een dialoog; anders stil en automatisch installeren. */
    void checkForUpdate(final boolean interactive) {
        if (updateBusy) { if (interactive) adminScreen.adminToast("Update is al bezig"); return; }
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

    void onUpdateInfo(final Updater.Info info, String err, boolean interactive) {
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
        adminScreen.adminEmit("update", d);
    }

    /** Niet installeren terwijl iemand het toestel gebruikt: wacht tot 2 minuten geen aanraking. */
    void installWhenIdle(final Updater.Info info) {
        long idle = SystemClock.uptimeMillis() - lastTouch;
        if (lastTouch != 0 && idle < IDLE_BEFORE_INSTALL_MS) {
            handler.postDelayed(new Runnable() { public void run() { installWhenIdle(info); } }, 300000);
            return;
        }
        downloadAndInstall(info, false);
    }

    void downloadAndInstall(final Updater.Info info, final boolean interactive) {
        if (updateBusy) return;
        updateBusy = true;
        if (interactive) adminScreen.adminToast("Update " + info.versionName + " downloaden en installeren…");
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
                runOnUiThread(new Runnable() { public void run() { if (interactive) adminScreen.adminToast("Update mislukt: " + fe); } });
            }
        }}).start();
    }

    // ---------- verborgen beheermenu ----------

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        markActivity();
        if (adminView != null) {
            handler.removeCallbacks(adminScreen.adminTimeout);
            handler.postDelayed(adminScreen.adminTimeout, AdminScreen.ADMIN_IDLE_MS);
            return super.dispatchTouchEvent(ev);
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float spot = SAME_SPOT_DP * getResources().getDisplayMetrics().density;
            long now = SystemClock.uptimeMillis();
            boolean same = Math.abs(ev.getX() - tapX) < spot && Math.abs(ev.getY() - tapY) < spot;
            if (now - lastTap > MAX_TAP_GAP_MS || !same) { tapCount = 0; tapX = ev.getX(); tapY = ev.getY(); }
            lastTap = now;
            if (++tapCount >= prefs.getInt("menu_taps", 10)) { tapCount = 0; adminScreen.showAdmin(); }
        }
        return super.dispatchTouchEvent(ev);
    }

    String nfcStatus() {
        if (nfcLastRestart == 0) return "NFC nog niet herstart";
        long ago = (System.currentTimeMillis() - nfcLastRestart) / 1000;
        return "Laatst herstart " + ago + " s geleden (" + nfcRestartReason + "), " + nfcRestarts + "× totaal";
    }

    String wifiStatus() {
        java.util.Set<String> qr = prefs.getStringSet("wifi_qr_ssids", null);
        String viaQr = qr == null || qr.isEmpty() ? "" : " Via QR-code toegevoegd: " + android.text.TextUtils.join(", ", new java.util.TreeSet<>(qr)) + ".";
        java.util.Set<String> ev = prefs.getStringSet("wifi_ev_ssids", null);
        String viaEv = ev == null || ev.isEmpty() ? "" : " Uit EventVOX: " + android.text.TextUtils.join(", ", new java.util.TreeSet<>(ev)) + ".";
        return wifiStatusConfig() + viaEv + viaQr;
    }

    String wifiStatusConfig() {
        java.util.Set<String> ssids = prefs.getStringSet("wifi_ssids", null);
        String r = prefs.getString("wifi_result", "");
        if (ssids == null || ssids.isEmpty()) return r.isEmpty() ? "Geen wifi-profielen uit de configuratie." : "Profielen: " + r;
        return "Profielen uit de configuratie: " + android.text.TextUtils.join(", ", new java.util.TreeSet<>(ssids)) + (r.isEmpty() ? "" : " · " + r);
    }

    void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }

    String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    void applyRuntimeSettings() {
        if (prefs.getBoolean("keep_screen_on", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        restartNfc("instellingen");
        schedulePeriodicNfcRefresh();
    }

    @SuppressWarnings("deprecation")
    void removeOwner() {
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


    @Override protected void onDestroy() {
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(wakeReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(scanReceiver2); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

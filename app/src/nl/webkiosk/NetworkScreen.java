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

/** Offline-balk en het scherm "Netwerk toevoegen" (assets/wifi.html): wifi-QR-code of een netwerk in de buurt. */
final class NetworkScreen {
    final MainActivity a;

    NetworkScreen(MainActivity a) { this.a = a; }

    // ---------- netwerk toevoegen (assets/wifi.html): wifi-QR-code of een netwerk in de buurt ----------

    /** wifi_qr=false: geen offline-balk en wifi-QR-codes negeren */
    boolean wifiQrEnabled() { return a.prefs.getBoolean("wifi_qr", true); }

    boolean isOnline() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    static final long OFFLINE_BANNER_MS = 30000;
    long offlineSince = 0;
    View offlineBanner;
    WebView wifiView;
    WifiProfiles.Profile pendingWifi;
    String wifiTrySsid = "";
    int wifiTryId = -1;
    long wifiTryUntil = 0;
    boolean wifiScanRegistered = false;

    /** Elke 5 s: na 30 s zonder netwerk een balk bovenaan; alleen de knop vangt tikken, de pagina blijft bruikbaar */
    final Runnable offlineCheck = new Runnable() {
        public void run() {
            a.handler.postDelayed(this, 5000);
            boolean online = isOnline();
            long now = SystemClock.uptimeMillis();
            if (online) offlineSince = 0;
            else if (offlineSince == 0) offlineSince = now;
            boolean show = !online && wifiQrEnabled() && now - offlineSince >= OFFLINE_BANNER_MS && wifiView == null && a.adminView == null;
            if (show && offlineBanner == null) showOfflineBanner();
            else if (!show && offlineBanner != null) { a.bannerBox.removeView(offlineBanner); offlineBanner = null; }
        }
    };

    void showOfflineBanner() {
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#F25E2572"));
        int pad = Math.round(10 * dp);
        bar.setPadding(Math.round(16 * dp), pad, Math.round(10 * dp), pad);
        TextView text = new TextView(a);
        text.setText("Je bent offline");
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView button = new TextView(a);
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
        a.bannerBox.addView(bar);
    }

    /**
     * Een gescande wifi-QR-code: met het netwerkscherm open daar tonen; in het beheerscherm meteen toevoegen; zonder
     * netwerk het netwerkscherm openen; met netwerk niets (een vreemde code mag een werkend toestel niet omzetten).
     */
    void handleWifiQr(String data) {
        if (!wifiQrEnabled()) return;
        WifiProfiles.Profile w = WifiProfiles.parseQr(data);
        if (wifiView != null) {
            if (w == null) { wifiEmit("failed", "Geen geldige wifi-QR-code"); return; }
            pendingWifi = w;
            wifiView.evaluateJavascript("window.wifiEvent&&window.wifiEvent({type:'qr',qr:" + qrJson(w) + "})", null);
            return;
        }
        if (a.adminView != null) {
            if (w == null) { a.adminScreen.adminToast("Geen geldige wifi-QR-code"); return; }
            pendingWifi = w;
            tryProfile(w, true);
            return;
        }
        if (w == null) { a.toast("Geen geldige wifi-QR-code"); return; }
        if (isOnline()) { a.toast("Er is al netwerk. Een wifi-netwerk toevoegen kan via het beheerscherm."); return; }
        pendingWifi = w;
        showWifiView();
    }

    static JSONObject qrJson(WifiProfiles.Profile w) {
        JSONObject o = new JSONObject();
        try {
            o.put("ssid", w.ssid);
            o.put("security", w.security.equals("OPEN") ? "Open (geen wachtwoord)" : w.security.equals("WEP") ? "WEP" : "WPA/WPA2");
            o.put("hidden", w.hidden);
        } catch (Exception ignored) {}
        return o;
    }

    void showWifiView() {
        if (wifiView != null) return;
        if (offlineBanner != null) { a.bannerBox.removeView(offlineBanner); offlineBanner = null; }
        wifiView = new WebView(a);
        wifiView.setBackgroundColor(Color.parseColor("#5e2572"));
        wifiView.getSettings().setJavaScriptEnabled(true);
        wifiView.getSettings().setAllowFileAccess(true);
        wifiView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !url.startsWith("file:///android_asset/"); }
        });
        wifiView.addJavascriptInterface(new WifiBridge(), "WifiQr");
        wifiView.loadUrl("file:///android_asset/wifi.html");
        a.root.addView(wifiView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        IntentFilter f = new IntentFilter(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        a.registerReceiver(wifiScanReceiver, f);
        wifiScanRegistered = true;
    }

    void hideWifiView() {
        a.handler.removeCallbacks(wifiWait);
        if (wifiScanRegistered) { try { a.unregisterReceiver(wifiScanReceiver); } catch (Exception ignored) {} wifiScanRegistered = false; }
        if (wifiView == null) return;
        a.root.removeView(wifiView);
        wifiView.destroy();
        wifiView = null;
        pendingWifi = null;
        a.hideSystemUi();
        a.focusWeb();
    }

    void wifiEmit(String state, String text) {
        if (!"connecting".equals(state)) KioskLog.add(a, "Wifi: " + text);
        JSONObject d = new JSONObject();
        try { d.put("type", "status"); d.put("state", state); d.put("text", text); } catch (Exception ignored) {}
        if (wifiView != null) wifiView.evaluateJavascript("window.wifiEvent&&window.wifiEvent(" + d + ")", null);
        if (a.adminView != null) {
            if (!"connecting".equals(state)) a.adminScreen.adminToast(text);
            a.adminScreen.adminEmit("refresh", new JSONObject());
        }
    }

    // --- netwerken in de buurt ---

    final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { emitNetworks(); }
    };

    /** Locatie aan (als device owner): zonder locatie geeft Android 8 geen netwerken in de buurt */
    @SuppressWarnings("deprecation")
    boolean locationOn() {
        try {
            int mode = Settings.Secure.getInt(a.getContentResolver(), Settings.Secure.LOCATION_MODE, 0);
            if (mode != Settings.Secure.LOCATION_MODE_OFF) return true;
            if (a.isOwner()) {
                a.dpm.setSecureSetting(a.admin, Settings.Secure.LOCATION_MODE, String.valueOf(Settings.Secure.LOCATION_MODE_BATTERY_SAVING));
                return Settings.Secure.getInt(a.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF;
            }
        } catch (Exception ignored) {}
        return false;
    }

    void startNetworkScan() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
        locationOn();
        try { wm.startScan(); } catch (Exception ignored) {}
        emitNetworks(); // wat er al bekend is, meteen
    }

    @SuppressWarnings("deprecation")
    void emitNetworks() {
        if (wifiView == null) return;
        JSONObject d = new JSONObject();
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            java.util.Set<String> saved = WifiProfiles.savedSsids(a);
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
            d.put("location", Settings.Secure.getInt(a.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF);
        } catch (Exception e) {
            try { d.put("type", "networks"); d.put("list", new JSONArray()); d.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        wifiView.evaluateJavascript("window.wifiEvent&&window.wifiEvent(" + d + ")", null);
    }

    // --- verbinden ---

    /** Een profiel toevoegen (QR-code of gekozen netwerk met wachtwoord) en tot 60 s wachten op verbinding */
    void tryProfile(WifiProfiles.Profile w, boolean fromAdmin) {
        int id = WifiProfiles.addAndConnect(a, w);
        if (id == -1) { wifiEmit("failed", "Toevoegen van " + w.ssid + " lukte niet"); return; }
        java.util.Set<String> kept = new java.util.HashSet<>(a.prefs.getStringSet("wifi_qr_ssids", new java.util.HashSet<String>()));
        kept.add(w.ssid);
        a.prefs.edit().putStringSet("wifi_qr_ssids", kept).apply();
        waitFor(id, w.ssid, (fromAdmin ? w.ssid + " toegevoegd, " : "") + "verbinden met " + w.ssid + "…");
    }

    void waitFor(int id, String ssid, String text) {
        wifiTryId = id;
        wifiTrySsid = ssid;
        wifiTryUntil = SystemClock.uptimeMillis() + 60000;
        wifiEmit("connecting", text);
        a.handler.removeCallbacks(wifiWait);
        a.handler.postDelayed(wifiWait, 1000);
    }

    final Runnable wifiWait = new Runnable() {
        @SuppressWarnings("deprecation")
        public void run() {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            boolean ok = info != null && info.getNetworkId() == wifiTryId && isOnline();
            if (ok) {
                WifiProfiles.enableAll(a);
                wifiEmit("connected", "Verbonden met " + wifiTrySsid);
                if (wifiView != null) a.handler.postDelayed(new Runnable() { public void run() { hideWifiView(); a.loadHome(); } }, 2500);
                return;
            }
            if (SystemClock.uptimeMillis() > wifiTryUntil) {
                WifiProfiles.enableAll(a);
                wifiEmit("failed", "Geen verbinding met " + wifiTrySsid + " binnen 60 s. Klopt het wachtwoord en is het netwerk in de buurt?");
                return;
            }
            a.handler.postDelayed(this, 1000);
        }
    };

    /** Wordt door wifi.html aangeroepen */
    class WifiBridge {
        /** { qr: { ssid, security, hidden } of null, offline } */
        @JavascriptInterface public String info() {
            JSONObject o = new JSONObject();
            try {
                o.put("qr", pendingWifi == null ? JSONObject.NULL : qrJson(pendingWifi));
                o.put("offline", !isOnline());
            } catch (Exception ignored) {}
            return o.toString();
        }
        @JavascriptInterface public void scan() { a.runOnUiThread(new Runnable() { public void run() { startNetworkScan(); } }); }
        /** De gescande wifi-QR-code toevoegen */
        @JavascriptInterface public void add() {
            a.runOnUiThread(new Runnable() { public void run() { if (pendingWifi != null) tryProfile(pendingWifi, false); } });
        }
        /** Een netwerk uit de lijst: opgeslagen en zonder wachtwoord verbinden, anders toevoegen met het wachtwoord */
        @JavascriptInterface public void connect(final String ssid, final String security, final String password) {
            a.runOnUiThread(new Runnable() { public void run() {
                if (ssid == null || ssid.isEmpty()) return;
                if ("EAP".equals(security)) { wifiEmit("failed", "Bedrijfsnetwerken (met gebruikersnaam) worden niet ondersteund"); return; }
                if ((password == null || password.isEmpty()) && WifiProfiles.savedSsids(a).contains(ssid)) {
                    int id = WifiProfiles.connectSaved(a, ssid);
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
        @JavascriptInterface public void close() { a.runOnUiThread(new Runnable() { public void run() { hideWifiView(); } }); }
    }


}

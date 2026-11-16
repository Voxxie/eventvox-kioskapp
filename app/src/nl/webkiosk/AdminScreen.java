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

/** Beheerscherm (assets/admin.html, window.Kiosk) en beheer-QR-codes (EVXADMIN:). */
final class AdminScreen {
    final MainActivity a;

    AdminScreen(MainActivity a) { this.a = a; }

    // ---------- beheer-QR-code ----------

    /**
     * EVXADMIN:<verloop>.<HMAC-SHA256 van het verloop met de sleutel van de organisatie, hex> (gemaakt in EventVOX):
     * opent het beheerscherm zonder PIN tot het verloop. Werkt zonder netwerk; de sleutel kwam met de heartbeat.
     */
    void handleAdminQr(String text) {
        String key = TokenVault.get(a.prefs, "admin_qr_key");
        if (key == null) { a.toast("Deze terminal kent nog geen beheer-QR-codes: hij moet eerst gekoppeld zijn in EventVOX"); return; }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)EVXADMIN:(\\d+)\\.([0-9a-f]{64})").matcher(text.trim());
        if (!m.matches()) { a.toast("Geen geldige beheer-QR-code"); return; }
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
                KioskLog.add(a, "Beheer-QR-code geweigerd: handtekening klopt niet (ingetrokken of van een andere organisatie)");
                a.toast("Deze beheer-QR-code is ingetrokken of hoort bij een andere organisatie");
                return;
            }
        } catch (Exception e) { a.toast("Beheer-QR-code kon niet worden gecontroleerd"); return; }
        if (expires < now) { a.toast("Deze beheer-QR-code is verlopen"); return; }
        if (expires > now + 8 * 86400L) { a.toast("Deze beheer-QR-code is te lang geldig (controleer de klok van het toestel)"); return; }
        KioskLog.add(a, "Beheerscherm geopend met een beheer-QR-code");
        if (a.adminView == null) showAdmin();
        a.adminUnlocked = true;
        adminEmit("unlocked", new JSONObject());
    }


    // ---------- beheerscherm (assets/admin.html) ----------

    static final long ADMIN_IDLE_MS = 180000;
    final Runnable adminTimeout = new Runnable() { public void run() { hideAdmin(); } };

    void showAdmin() {
        if (a.adminView != null) return;
        a.markActivity();
        a.adminUnlocked = false;
        a.adminView = new WebView(a);
        a.adminView.setBackgroundColor(Color.parseColor("#5e2572"));
        WebSettings as = a.adminView.getSettings();
        as.setJavaScriptEnabled(true);
        as.setAllowFileAccess(true);
        as.setSupportZoom(false);
        a.adminView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !url.startsWith("file:///android_asset/"); }
        });
        a.adminView.addJavascriptInterface(new AdminBridge(), "Kiosk");
        a.adminView.loadUrl("file:///android_asset/admin.html");
        a.root.addView(a.adminView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        a.handler.postDelayed(adminTimeout, ADMIN_IDLE_MS);
    }

    void hideAdmin() {
        a.handler.removeCallbacks(adminTimeout);
        if (a.adminView == null) return;
        if (a.nfcTesting) { a.nfcTesting = false; a.applyRuntimeSettings(); }
        a.adminUnlocked = false;
        a.root.removeView(a.adminView);
        a.adminView.destroy();
        a.adminView = null;
        a.hideSystemUi();
        a.focusWeb();
        // toetsenbord dicht
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(a.root.getWindowToken(), 0);
    }

    void adminEmit(String type, JSONObject data) {
        if (a.adminView == null) return;
        a.adminView.evaluateJavascript("window.kioskEvent&&window.kioskEvent(" + JSONObject.quote(type) + "," + data + ")", null);
    }

    void adminToast(String text) {
        if (a.adminView == null) { a.toast(text); return; }
        JSONObject d = new JSONObject();
        try { d.put("text", text); } catch (Exception ignored) {}
        adminEmit("toast", d);
    }

    JSONObject adminState() throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", a.appVersion());
        o.put("serial", a.serial());
        o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("android", Build.VERSION.RELEASE + " (" + Build.DISPLAY + ")");
        o.put("owner", a.isOwner());
        o.put("url", a.homeUrl());
        o.put("resolvedUrl", a.resolvedUrl());
        JSONObject st = new JSONObject();
        st.put("nfc_mode", a.prefs.getString("nfc_mode", "both"));
        st.put("nfc_uid_format", a.prefs.getString("nfc_uid_format", "hex"));
        st.put("nfc_keyboard_value", a.prefs.getString("nfc_keyboard_value", "uid"));
        st.put("nfc_send_enter", a.prefs.getBoolean("nfc_send_enter", true));
        st.put("nfc_refresh_seconds", a.prefs.getInt("nfc_refresh_seconds", 0));
        st.put("auto_update", a.prefs.getBoolean("auto_update", true));
        st.put("update_check_hours", a.prefs.getInt("update_check_hours", 0));
        st.put("keep_screen_on", a.prefs.getBoolean("keep_screen_on", false));
        st.put("reload_minutes", a.prefs.getInt("reload_minutes", 0));
        st.put("ignore_ssl_errors", a.prefs.getBoolean("ignore_ssl_errors", false));
        st.put("vibrate", a.prefs.getBoolean("vibrate", true));
        st.put("update_channel", a.prefs.getString("update_channel", "stable"));
        st.put("volume_lock", a.prefs.getInt("volume_lock", 0));
        st.put("dim_minutes", a.prefs.getInt("dim_minutes", 2));
        st.put("dim_level", a.prefs.getInt("dim_level", 15));
        st.put("nightly_reload", a.prefs.getBoolean("nightly_reload", true));
        st.put("nightly_hour", a.prefs.getInt("nightly_hour", 4));
        st.put("low_battery", a.prefs.getInt("low_battery", 15));
        st.put("orientation", a.prefs.getString("orientation", "portrait"));
        st.put("menu_taps", a.prefs.getInt("menu_taps", 10));
        st.put("scan_mode", a.scanMode());
        st.put("datawedge", a.manageDataWedge() ? "app" : "off");
        o.put("settings", st);
        JSONObject n = new JSONObject();
        n.put("available", a.nfc != null);
        n.put("enabled", a.nfc != null && a.nfc.isEnabled());
        n.put("status", a.nfcStatus());
        o.put("nfc", n);
        JSONObject u = new JSONObject();
        long last = a.prefs.getLong("update_last_check", 0);
        u.put("lastCheck", last == 0 ? "" : new java.text.SimpleDateFormat("d-M HH:mm").format(new java.util.Date(last)));
        u.put("lastResult", a.prefs.getString("update_last_result", ""));
        u.put("url", a.prefs.getString("update_url", ""));
        u.put("defaultUrl", Updater.DEFAULT_URL);
        o.put("update", u);
        o.put("wifi", a.wifiStatus());
        o.put("pinDefault", "1234".equals(a.pin()));
        // Voor het overzicht van het beheerscherm: de huidige wifi-verbinding en welke instellingen uit EventVOX komen
        o.put("wifiNow", a.wifiJson());
        JSONArray remoteKeys = new JSONArray();
        if (a.prefs.getInt("remote_settings_version", 0) > 0) {
            for (String k : a.prefs.getString("remote_settings_keys", "").split(",\\s*")) if (!k.isEmpty()) remoteKeys.put(k);
        }
        o.put("remoteKeys", remoteKeys);
        JSONObject health = new JSONObject();
        health.put("crashes", a.renderCrashes);
        health.put("lastCrash", a.lastCrashAt > 0 ? new java.text.SimpleDateFormat("d-M HH:mm").format(new java.util.Date(a.lastCrashAt)) : "");
        long diag = a.prefs.getLong("diagnostics_sent", 0);
        health.put("diagnosticsSent", diag > 0 ? new java.text.SimpleDateFormat("d-M HH:mm").format(new java.util.Date(diag)) : "");
        health.put("diagnosticsPending", a.diagnosticsPending);
        health.put("identity", TokenVault.get(a.prefs, "terminal_token") != null);
        health.put("clock", a.clockCheckedAt == 0 ? "" : Math.abs(a.clockSkew) < MainActivity.CLOCK_WARN_S ? "loopt gelijk met de server" : MainActivity.skewText(a.clockSkew));
        health.put("remote", a.prefs.getInt("remote_settings_version", 0) > 0 ? a.prefs.getString("remote_settings_keys", "") : "");
        health.put("adminQr", TokenVault.get(a.prefs, "admin_qr_key") != null);
        String log = KioskLog.text(a);
        String[] lines = log.split("\n");
        StringBuilder tail = new StringBuilder();
        for (int i = Math.max(0, lines.length - 40); i < lines.length; i++) tail.append(lines[i]).append('\n');
        health.put("log", tail.toString());
        o.put("health", health);
        JSONObject sc = new JSONObject();
        sc.put("installed", DataWedge.installed(a));
        sc.put("status", a.prefs.getString("dw_status", ""));
        sc.put("last", a.lastScan);
        o.put("scanner", sc);
        o.put("battery", Battery.batteryJson(a.lastBattery));
        return o;
    }

    /** Wordt door admin.js aangeroepen (op een achtergrondthread van de WebView). */
    class AdminBridge {
        @JavascriptInterface public String pin(String code) {
            JSONObject r = new JSONObject();
            try {
                long now = System.currentTimeMillis();
                if (now < a.pinLockedUntil) { r.put("locked", (a.pinLockedUntil - now + 999) / 1000); return r.toString(); }
                if (code != null && code.equals(a.pin())) {
                    a.adminUnlocked = true; a.pinFails = 0; r.put("ok", true);
                } else if (code != null && code.length() >= a.pin().length()) {
                    r.put("wrong", true);
                    if (++a.pinFails >= 5) { a.pinFails = 0; a.pinLockedUntil = now + 60000; r.put("locked", 60); }
                }
            } catch (Exception ignored) {}
            return r.toString();
        }

        @JavascriptInterface public String state() {
            if (!a.adminUnlocked) return "{}";
            final String[] out = {"{}"};
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            a.runOnUiThread(new Runnable() { public void run() {
                try { out[0] = adminState().toString(); } catch (Exception ignored) {}
                done.countDown();
            }});
            try { done.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            return out[0];
        }

        @JavascriptInterface public String set(final String key, final String value) {
            JSONObject r = new JSONObject();
            try {
                if (!a.adminUnlocked) { r.put("error", "Niet ontgrendeld"); return r.toString(); }
                String err = applySetting(key, value == null ? "" : value.trim());
                if (err != null) r.put("error", err); else r.put("ok", true);
                if (err == null && ("url".equals(key) || "pin".equals(key) || "update_url".equals(key))) r.put("message", "Opgeslagen");
            } catch (Exception e) { try { r.put("error", e.getMessage()); } catch (Exception ignored) {} }
            return r.toString();
        }

        @JavascriptInterface public void action(final String name, final String arg) {
            if (!"close".equals(name) && !a.adminUnlocked) return;
            a.runOnUiThread(new Runnable() { public void run() { adminAction(name, arg); } });
        }
    }

    static final java.util.List<String> STRING_KEYS = java.util.Arrays.asList("nfc_mode", "nfc_uid_format", "nfc_keyboard_value", "scan_mode", "datawedge", "update_channel", "orientation");
    static final java.util.List<String> BOOL_KEYS = java.util.Arrays.asList("nfc_send_enter", "auto_update", "keep_screen_on", "ignore_ssl_errors", "vibrate", "nightly_reload");
    static final java.util.List<String> INT_KEYS = java.util.Arrays.asList("nfc_refresh_seconds", "update_check_hours", "reload_minutes", "menu_taps", "volume_lock", "dim_minutes", "dim_level", "nightly_hour", "low_battery");

    /** Eén instelling opslaan en toepassen; geeft een foutmelding of null. */
    String applySetting(String key, String v) {
        SharedPreferences.Editor e = a.prefs.edit();
        if ("url".equals(key)) {
            if (!v.isEmpty() && !v.matches("(?i)^(https?|file|about):.*")) v = "https://" + v;
            if (v.isEmpty()) return "Vul een adres in";
            e.putString("url", v).commit();
            a.runOnUiThread(new Runnable() { public void run() { a.web.clearHistory(); a.loadHome(); } });
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
            a.runOnUiThread(new Runnable() { public void run() {
                if (a.manageDataWedge()) a.setupDataWedge();
                else {
                    if (DataWedge.installed(a)) DataWedge.remove(a);
                    a.prefs.edit().putString("dw_status", "Profiel verwijderd: DataWedge gebruikt zijn eigen profielen").apply();
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
        a.runOnUiThread(new Runnable() { public void run() {
            a.applyRuntimeSettings();
            a.applyVolume();
            a.applyOrientation();
            if (a.lastBattery != null) a.updateBatteryBanner(Battery.batteryJson(a.lastBattery));
            if (a.dimmed) a.setBrightness(-1f);
            a.schedulePeriodicReload();
            a.handler.removeCallbacks(a.updateCheck);
            int h = a.prefs.getInt("update_check_hours", 0);
            if (h > 0) a.handler.postDelayed(a.updateCheck, h * 3600000L);
        }});
        return null;
    }

    void adminAction(String name, String arg) {
        switch (name) {
            case "close": hideAdmin(); break;
            case "diagnostics":
                a.diagnosticsPending = true;
                KioskLog.add(a, "Diagnose verstuurd vanaf het toestel");
                adminToast("De diagnose gaat mee met de volgende heartbeat (binnen een minuut)");
                break;
            case "networks": hideAdmin(); a.net.pendingWifi = null; a.net.showWifiView(); break;
            case "vibrateTest":
                // de patronen van de scanterminal: toegelaten, waarschuwing, geweigerd
                String pat = "warning".equals(arg) ? "[90,110,90]" : "error".equals(arg) ? "[350,150,350,150,350]" : "[70]";
                if (!a.prefs.getBoolean("vibrate", true)) adminToast("Trillen staat uit");
                else if (!a.doVibrate(pat)) adminToast("Dit toestel kan niet trillen");
                break;
            case "dataWedge":
                a.setupDataWedge();
                adminToast("DataWedge-profiel wordt ingesteld");
                a.handler.postDelayed(new Runnable() { public void run() { adminEmit("refresh", new JSONObject()); } }, 1500);
                break;
            case "reload": a.loadHome(); adminToast("Pagina wordt herladen"); break;
            case "clearCache":
                a.web.clearCache(true);
                CookieManager.getInstance().removeAllCookies(null);
                WebStorage.getInstance().deleteAllData();
                a.loadHome();
                adminToast("Cache en cookies gewist");
                break;
            case "nfcRestart":
                a.restartNfc("handmatig");
                adminToast("NFC wordt herstart");
                a.handler.postDelayed(new Runnable() { public void run() { adminEmit("refresh", new JSONObject()); } }, 1800);
                break;
            case "nfcTest":
                a.nfcTesting = "start".equals(arg);
                a.lastTagUid = "";
                if (a.nfcTesting && a.nfc == null) adminToast("Dit toestel heeft geen NFC");
                a.applyRuntimeSettings();
                break;
            case "nfcSettings":
                hideAdmin();
                a.kioskSuspended = true;
                try { a.stopLockTask(); } catch (Exception ignored) {}
                try { a.startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); }
                catch (Exception e) { a.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
                break;
            case "wifiSettings":
                hideAdmin();
                a.kioskSuspended = true;
                try { a.stopLockTask(); } catch (Exception ignored) {}
                a.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case "checkUpdate": a.checkForUpdate(true); break;
            case "installUpdate":
                if (a.lastUpdateInfo != null) a.downloadAndInstall(a.lastUpdateInfo, true);
                break;
            case "leave": hideAdmin(); a.leaveKiosk(); break;
            case "removeOwner": hideAdmin(); a.removeOwner(); break;
        }
    }

}

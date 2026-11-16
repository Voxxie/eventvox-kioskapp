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

/** Zoek mij: piepen, trillen en knipperen, en alleen dan de locatie zoeken (10 minuten meegestuurd met de status). */
final class FindMe {
    final MainActivity a;

    FindMe(MainActivity a) { this.a = a; }

    // ---------- Zoek mij ----------

    View locateView;
    android.media.ToneGenerator locateTone;
    final Runnable locateStep = new Runnable() {
        int n = 0;
        public void run() {
            if (locateView == null) { n = 0; return; }
            if (n++ >= 30) { n = 0; stopLocate(); return; }
            locateView.setBackgroundColor(n % 2 == 0 ? Color.parseColor("#5e2572") : Color.parseColor("#ff6a00"));
            try { if (locateTone != null) locateTone.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600); } catch (Exception ignored) {}
            a.doVibrate("[500]");
            a.handler.postDelayed(this, 1000);
        }
    };

    /** 30 s piepen, trillen en knipperen (fel scherm); een tik stopt het */
    void locate() {
        if (locateView != null) return;
        a.markActivity();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#5e2572"));
        TextView t = new TextView(a);
        t.setText("Hier ben ik!\n\nTik om te stoppen");
        t.setTextColor(Color.WHITE);
        t.setTextSize(30);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(t);
        box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stopLocate(); } });
        locateView = box;
        a.root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        a.setBrightness(1f);
        try { locateTone = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100); } catch (Exception e) { locateTone = null; }
        a.handler.post(locateStep);
        findLocation();
    }

    void stopLocate() {
        a.handler.removeCallbacks(locateStep);
        if (locateTone != null) { try { locateTone.release(); } catch (Exception ignored) {} locateTone = null; }
        if (locateView != null) { a.root.removeView(locateView); locateView = null; }
        a.setBrightness(-1f);
        a.focusWeb();
    }

    // ---------- locatie bij Zoek mij ----------

    /*
     * Alleen na de opdracht "Zoek mij": hoogstens 60 s een positie zoeken (GPS, en het netwerk als dat er is), plus het
     * wifi-toegangspunt. Gaat als kiosk_status.location mee met de heartbeats van de 10 minuten erna; EventVOX bewaart
     * alleen de laatste en wist hem na 24 uur. Er wordt nooit zomaar een locatie vastgelegd (het toestel wordt door een
     * persoon gedragen). Het logboek krijgt geen coördinaten.
     */
    static final long LOCATION_SEARCH_MS = 60000, LOCATION_SEND_MS = 600000;
    volatile JSONObject foundLocation;
    volatile long foundLocationAt = 0;
    android.location.Location bestLocation;
    android.location.LocationListener locationListener;

    @SuppressWarnings({"deprecation", "MissingPermission"})
    void findLocation() {
        if (locationListener != null) return; // al bezig
        final android.location.LocationManager lm = (android.location.LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            if (a.isOwner()) a.dpm.setSecureSetting(a.admin, Settings.Secure.LOCATION_MODE, String.valueOf(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));
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
        KioskLog.add(a, any ? "Zoek mij: locatie zoeken" : "Zoek mij: geen locatiebron (locatie uit op het toestel?)");
        a.handler.postDelayed(new Runnable() { public void run() { finishLocation(lm); } }, any ? LOCATION_SEARCH_MS : 0);
    }

    void better(android.location.Location l) {
        if (l == null) return;
        if (bestLocation == null || (l.hasAccuracy() && (!bestLocation.hasAccuracy() || l.getAccuracy() < bestLocation.getAccuracy()))) bestLocation = l;
    }

    @SuppressWarnings("deprecation")
    void finishLocation(android.location.LocationManager lm) {
        if (locationListener == null) return;
        try { lm.removeUpdates(locationListener); } catch (Exception ignored) {}
        locationListener = null;
        android.location.Location l = bestLocation;
        if (l == null) { KioskLog.add(a, "Zoek mij: geen locatie gevonden"); return; }
        try {
            JSONObject o = new JSONObject();
            o.put("lat", l.getLatitude());
            o.put("lon", l.getLongitude());
            if (l.hasAccuracy()) o.put("accuracy_m", Math.round(l.getAccuracy()));
            o.put("provider", android.location.LocationManager.GPS_PROVIDER.equals(l.getProvider()) ? "gps" : "network");
            o.put("at", MainActivity.iso(l.getTime()));
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
            KioskLog.add(a, "Zoek mij: locatie gevonden" + (l.hasAccuracy() ? " (±" + Math.round(l.getAccuracy()) + " m, " + o.optString("provider") + ")" : ""));
        } catch (Exception ignored) {}
    }


}

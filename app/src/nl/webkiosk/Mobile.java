package nl.webkiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import org.json.JSONObject;

/**
 * Mobiel internet (een Zebra met een modem, 4G/5G): verbonden, soort netwerk (MobileType), signaal 0-4, provider en
 * roaming. Voor getNetwork() (mobile) en getStatus(); bij een andere stand het event webkiosk-network naar de pagina.
 * Een toestel zonder modem (zoals de TC51) merkt hier niets van: dan is er geen mobile (null) en luistert de app niet.
 */
class Mobile {
    final MainActivity a;
    TelephonyManager tm;
    /** Signaal 0-4 (SignalStrength.getLevel), -1 onbekend */
    int level = -1;
    /** TelephonyDisplayInfo.getOverrideNetworkType (Android 11+: 5G naast 4G), 0 anders */
    int override = 0;
    String lastSig = "";
    String lastLogged = "";
    PhoneStateListener listener;
    boolean receiverOn = false;

    Mobile(MainActivity a) { this.a = a; }

    /** Heeft het toestel een modem? (De TelephonyManager pas hier: een Activity heeft vóór onCreate nog geen diensten) */
    boolean present() {
        try {
            if (tm == null) tm = (TelephonyManager) a.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;
        } catch (Exception e) { return false; }
    }

    final BroadcastReceiver connectivity = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { changed(); }
    };

    @SuppressWarnings("deprecation")
    void start() {
        if (!present() || listener != null) return;
        listener = new PhoneStateListener() {
            @Override public void onSignalStrengthsChanged(SignalStrength s) {
                try { level = s.getLevel(); } catch (Exception ignored) {}
                changed();
            }
            @Override public void onDataConnectionStateChanged(int state, int networkType) { changed(); }
            /** Android 11+ (LISTEN_DISPLAY_INFO_CHANGED): zo meldt Android 5G naast 4G; geen @Override (stub) */
            public void onDisplayInfoChanged(android.telephony.TelephonyDisplayInfo info) {
                try { override = info.getOverrideNetworkType(); } catch (Throwable ignored) {}
                changed();
            }
        };
        int events = PhoneStateListener.LISTEN_SIGNAL_STRENGTHS | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE;
        try {
            // LISTEN_DISPLAY_INFO_CHANGED (0x00100000) vraagt op sommige versies de telefoon-toestemming: dan zonder
            if (Build.VERSION.SDK_INT >= 30) tm.listen(listener, events | 0x00100000);
            else tm.listen(listener, events);
        } catch (Exception e) {
            try { tm.listen(listener, events); } catch (Exception ignored) {}
        }
        try {
            a.registerReceiver(connectivity, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            receiverOn = true;
        } catch (Exception ignored) {}
    }

    void stop() {
        if (listener != null) try { tm.listen(listener, PhoneStateListener.LISTEN_NONE); } catch (Exception ignored) {}
        listener = null;
        if (receiverOn) try { a.unregisterReceiver(connectivity); } catch (Exception ignored) {}
        receiverOn = false;
    }

    /** { connected, sim, type?, level?, operator?, roaming }; null zonder modem */
    @SuppressWarnings("deprecation")
    JSONObject json() {
        if (!present()) return null;
        JSONObject o = new JSONObject();
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo mi = cm == null ? null : cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            boolean connected = mi != null && mi.isConnected();
            int subtype = connected ? mi.getSubtype() : 0;
            if (subtype == 0) try { subtype = tm.getNetworkType(); } catch (Exception ignored) {}
            o.put("connected", connected);
            o.put("sim", tm.getSimState() == TelephonyManager.SIM_STATE_READY);
            String type = MobileType.name(subtype, override);
            if (type != null) o.put("type", type);
            if (level >= 0) o.put("level", level);
            String op = tm.getNetworkOperatorName();
            if (op != null && !op.trim().isEmpty()) o.put("operator", op.trim());
            o.put("roaming", tm.isNetworkRoaming());
        } catch (Exception ignored) {}
        return o;
    }

    /** Bij een andere stand (verbonden, soort, signaal, waarover internet): het event naar de pagina, en wisselingen in het logboek */
    void changed() {
        a.runOnUiThread(new Runnable() { public void run() {
            JSONObject m = json();
            if (m == null || a.web == null) return;
            String up = a.uplink();
            String sig = up + "|" + m.optBoolean("connected") + "|" + m.optString("type") + "|" + m.optInt("level", -1);
            if (sig.equals(lastSig)) return;
            lastSig = sig;
            String logSig = m.optBoolean("connected") + "|" + m.optString("type") + "|" + m.optBoolean("sim");
            if (!logSig.equals(lastLogged)) {
                lastLogged = logSig;
                KioskLog.add(a, !m.optBoolean("sim") ? "Mobiel: geen simkaart"
                    : m.optBoolean("connected") ? "Mobiel verbonden" + (m.has("type") ? " (" + m.optString("type") + ")" : "")
                        + (m.has("operator") ? " met " + m.optString("operator") : "") : "Mobiel niet verbonden");
            }
            a.web.evaluateJavascript("(function(d){try{window.dispatchEvent(new CustomEvent('webkiosk-network',{detail:d}));}catch(e){}"
                + "if(typeof window.onWebKioskNetwork==='function')window.onWebKioskNetwork(d);})(" + a.networkJson() + ");", null);
        }});
    }
}

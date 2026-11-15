package nl.webkiosk;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import java.util.*;

/**
 * Wifi-profielen uit webkiosk.properties op het toestel zetten:
 *   wifi.1.ssid=EventVOX-Ingang
 *   wifi.1.password=geheim123
 *   wifi.1.security=WPA        (WPA = WPA/WPA2-Personal, WEP, OPEN; standaard WPA, of OPEN zonder wachtwoord)
 *   wifi.1.hidden=false        (true voor een verborgen netwerk)
 *   wifi.1.priority=10         (hoger = eerder kiezen)
 *   wifi_remove_others=false   (true: alle andere opgeslagen netwerken verwijderen)
 *   wifi_lockdown=false        (true: gebruikers kunnen de netwerken van de app niet wijzigen of vergeten)
 * Bestaande netwerken met dezelfde SSID worden bijgewerkt. Netwerken die eerder door de app zijn gezet en niet meer
 * in het bestand staan, worden verwijderd.
 */
public class WifiProfiles {
    public static final int MAX = 20;

    public static class Profile { String ssid, password, security; boolean hidden; int priority; }

    public static List<Profile> parse(Properties p) {
        List<Profile> list = new ArrayList<>();
        for (int i = 1; i <= MAX; i++) {
            String ssid = p.getProperty("wifi." + i + ".ssid");
            if (ssid == null || ssid.trim().isEmpty()) continue;
            Profile w = new Profile();
            w.ssid = ssid.trim();
            w.password = p.getProperty("wifi." + i + ".password", "");
            String sec = p.getProperty("wifi." + i + ".security", "").trim().toUpperCase(Locale.ROOT);
            w.security = sec.isEmpty() ? (w.password.isEmpty() ? "OPEN" : "WPA") : sec;
            w.hidden = Boolean.parseBoolean(p.getProperty("wifi." + i + ".hidden", "false").trim());
            try { w.priority = Integer.parseInt(p.getProperty("wifi." + i + ".priority", "0").trim()); } catch (NumberFormatException e) { w.priority = 0; }
            list.add(w);
        }
        return list;
    }

    /** True als het bestand iets over wifi zegt (dan wordt de lijst toegepast, ook een lege). */
    public static boolean mentioned(Properties p) {
        for (String k : p.stringPropertyNames()) if (k.startsWith("wifi.") || k.startsWith("wifi_")) return true;
        return false;
    }

    /**
     * Zet de profielen. previous: SSID's die de app de vorige keer zette. Geeft een korte uitslag terug.
     */
    @SuppressWarnings("deprecation")
    public static String apply(Context c, List<Profile> profiles, Set<String> previous, boolean removeOthers, Set<String> appliedOut) {
        return apply(c, profiles, previous, removeOthers, appliedOut, null);
    }

    /** keep: SSID's die nooit verwijderd worden (netwerken die via een wifi-QR-code zijn toegevoegd) */
    public static String apply(Context c, List<Profile> profiles, Set<String> previous, boolean removeOthers, Set<String> appliedOut, Set<String> keep) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return "geen wifi";
        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
        Map<String, WifiConfiguration> existing = new HashMap<>();
        List<WifiConfiguration> configured = null;
        try { configured = wm.getConfiguredNetworks(); } catch (Exception ignored) {}
        if (configured != null) for (WifiConfiguration wc : configured) if (wc.SSID != null) existing.put(unquote(wc.SSID), wc);

        int ok = 0, failed = 0, removed = 0;
        Set<String> wanted = new HashSet<>();
        for (Profile w : profiles) {
            wanted.add(w.ssid);
            WifiConfiguration wc = build(w);
            WifiConfiguration old = existing.get(w.ssid);
            int id;
            if (old != null) { wc.networkId = old.networkId; id = wm.updateNetwork(wc); }
            else id = wm.addNetwork(wc);
            if (id == -1 && old != null) { // bijwerken mag niet (netwerk van iemand anders): opnieuw toevoegen
                wm.removeNetwork(old.networkId);
                wc.networkId = -1;
                id = wm.addNetwork(wc);
            }
            if (id == -1) { failed++; continue; }
            wm.enableNetwork(id, false);
            appliedOut.add(w.ssid);
            ok++;
        }
        if (configured != null) for (WifiConfiguration wc : configured) {
            String s = unquote(wc.SSID);
            if (wanted.contains(s) || (keep != null && keep.contains(s))) continue;
            if (removeOthers || (previous != null && previous.contains(s))) {
                if (wm.removeNetwork(wc.networkId)) removed++;
            }
        }
        wm.saveConfiguration();
        return ok + " ingesteld" + (failed > 0 ? ", " + failed + " mislukt" : "") + (removed > 0 ? ", " + removed + " verwijderd" : "");
    }

    /**
     * Een wifi-QR-code (het standaardformaat van telefoons en QR-generatoren):
     *   WIFI:T:WPA;S:netwerknaam;P:wachtwoord;H:false;;
     * T: WPA (ook WPA2/WPA3/SAE), WEP of nopass; S: naam; P: wachtwoord; H: verborgen. \ ontsnapt ; , : " en \.
     * Geeft null als het geen geldige wifi-code is.
     */
    public static Profile parseQr(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (!t.regionMatches(true, 0, "WIFI:", 0, 5)) return null;
        Map<String, String> f = new HashMap<>();
        StringBuilder cur = new StringBuilder();
        String key = null;
        for (int i = 5; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '\\' && i + 1 < t.length()) { cur.append(t.charAt(++i)); continue; }
            if (key == null && ch == ':') { key = cur.toString().trim().toUpperCase(Locale.ROOT); cur.setLength(0); continue; }
            if (ch == ';') {
                if (key != null && !f.containsKey(key)) f.put(key, cur.toString());
                key = null; cur.setLength(0); continue;
            }
            cur.append(ch);
        }
        if (key != null && !f.containsKey(key)) f.put(key, cur.toString());
        String ssid = f.get("S");
        if (ssid == null || ssid.isEmpty() || ssid.length() > 32) return null;
        Profile w = new Profile();
        w.ssid = ssid;
        w.password = f.containsKey("P") ? f.get("P") : "";
        String type = f.containsKey("T") ? f.get("T").trim().toUpperCase(Locale.ROOT) : "";
        if (type.equals("WEP")) w.security = "WEP";
        else if (type.equals("NOPASS") || type.equals("NONE") || (type.isEmpty() && w.password.isEmpty())) w.security = "OPEN";
        else w.security = "WPA"; // WPA, WPA2, WPA3/SAE (Android 8 kent alleen WPA/WPA2-Personal)
        w.hidden = "true".equalsIgnoreCase(String.valueOf(f.get("H")).trim());
        if (w.security.equals("WPA") && (w.password.length() < 8 || w.password.length() > 64)) return null;
        return w;
    }

    /** Eén netwerk toevoegen (of bijwerken) en er nu mee verbinden; geeft het networkId, of -1. */
    @SuppressWarnings("deprecation")
    public static int addAndConnect(Context c, Profile w) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return -1;
        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
        WifiConfiguration wc = build(w);
        WifiConfiguration old = null;
        List<WifiConfiguration> configured = null;
        try { configured = wm.getConfiguredNetworks(); } catch (Exception ignored) {}
        if (configured != null) for (WifiConfiguration x : configured) if (w.ssid.equals(unquote(x.SSID))) old = x;
        int id;
        if (old != null) { wc.networkId = old.networkId; id = wm.updateNetwork(wc); }
        else id = wm.addNetwork(wc);
        if (id == -1 && old != null) { wm.removeNetwork(old.networkId); wc.networkId = -1; id = wm.addNetwork(wc); }
        if (id == -1) return -1;
        wm.saveConfiguration();
        wm.enableNetwork(id, true); // nu dit netwerk proberen (zet de andere tijdelijk uit: zie enableAll)
        wm.reconnect();
        return id;
    }

    /** Met een al opgeslagen netwerk verbinden (zonder wachtwoord); geeft het networkId, of -1 als het er niet is. */
    @SuppressWarnings("deprecation")
    public static int connectSaved(Context c, String ssid) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return -1;
        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
        try {
            List<WifiConfiguration> configured = wm.getConfiguredNetworks();
            if (configured != null) for (WifiConfiguration x : configured) {
                if (ssid.equals(unquote(x.SSID))) {
                    wm.enableNetwork(x.networkId, true);
                    wm.reconnect();
                    return x.networkId;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** De namen van de opgeslagen netwerken */
    @SuppressWarnings("deprecation")
    public static Set<String> savedSsids(Context c) {
        Set<String> out = new HashSet<>();
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            List<WifiConfiguration> configured = wm == null ? null : wm.getConfiguredNetworks();
            if (configured != null) for (WifiConfiguration x : configured) if (x.SSID != null) out.add(unquote(x.SSID));
        } catch (Exception ignored) {}
        return out;
    }

    /** De beveiliging uit de capabilities van een scanresultaat: WPA, WEP, OPEN of EAP (bedrijfsnetwerk, niet ondersteund) */
    public static String securityOf(String capabilities) {
        String cap = capabilities == null ? "" : capabilities.toUpperCase(Locale.ROOT);
        if (cap.contains("EAP")) return "EAP";
        if (cap.contains("PSK") || cap.contains("SAE") || cap.contains("WPA")) return "WPA";
        if (cap.contains("WEP")) return "WEP";
        return "OPEN";
    }

    /** Alle opgeslagen netwerken weer aan (na addAndConnect, of het nu lukte of niet) */
    @SuppressWarnings("deprecation")
    public static void enableAll(Context c) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        try {
            List<WifiConfiguration> configured = wm.getConfiguredNetworks();
            if (configured != null) for (WifiConfiguration x : configured) wm.enableNetwork(x.networkId, false);
            wm.saveConfiguration();
        } catch (Exception ignored) {}
    }

    /** Gebruikers kunnen netwerken van de device owner niet wijzigen/vergeten (Android 6+). */
    public static void lockdown(Context c, DevicePolicyManager dpm, ComponentName admin, boolean on) {
        try { dpm.setGlobalSetting(admin, "wifi_device_owner_configs_lockdown", on ? "1" : "0"); } catch (Exception ignored) {}
    }

    private static WifiConfiguration build(Profile w) {
        WifiConfiguration wc = new WifiConfiguration();
        wc.SSID = quote(w.ssid);
        wc.hiddenSSID = w.hidden;
        wc.priority = w.priority;
        switch (w.security) {
            case "WEP":
                wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                wc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                wc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                wc.wepKeys[0] = isHex(w.password) && (w.password.length() == 10 || w.password.length() == 26) ? w.password : quote(w.password);
                wc.wepTxKeyIndex = 0;
                break;
            case "OPEN":
            case "NONE":
                wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                break;
            default: // WPA / WPA2 / PSK
                wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                wc.preSharedKey = w.password.length() == 64 && isHex(w.password) ? w.password : quote(w.password);
        }
        return wc;
    }

    private static String quote(String s) { return "\"" + s + "\""; }
    private static String unquote(String s) {
        return s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
    private static boolean isHex(String s) { return s.matches("[0-9A-Fa-f]+"); }
}

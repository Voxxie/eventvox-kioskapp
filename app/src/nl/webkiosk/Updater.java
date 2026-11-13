package nl.webkiosk;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Zelf-update via een update.json (wordt door de release-workflow bij elke GitHub-release gemaakt):
 *   {"versionCode":10500,"versionName":"1.5.0","url":"https://.../webkiosk.apk","sha256":"...","notes":"..."}
 * Als device owner wordt de nieuwe APK stil geïnstalleerd; anders vraagt Android om bevestiging.
 * Alle netwerk- en bestandswerk gebeurt op een achtergrondthread.
 */
public class Updater {
    public static final String DEFAULT_URL =
        "https://github.com/Voxxie/eventvox-kioskapp/releases/latest/download/update.json";

    public static class Info {
        public int versionCode; public String versionName, url, sha256, notes;
    }

    public interface Callback { void done(Info latest, String error); }

    /** Haalt update.json op. Info is null bij een fout. */
    public static Info fetch(String manifestUrl) throws Exception {
        String body = new String(read(manifestUrl, 512 * 1024), "UTF-8");
        JSONObject o = new JSONObject(body);
        Info i = new Info();
        i.versionCode = o.getInt("versionCode");
        i.versionName = o.optString("versionName", String.valueOf(i.versionCode));
        i.url = new URL(new URL(manifestUrl), o.getString("url")).toString(); // relatieve URL toegestaan
        i.sha256 = o.optString("sha256", "").trim().toLowerCase();
        i.notes = o.optString("notes", "");
        return i;
    }

    /** Download de APK naar een bestand en controleer de SHA-256. */
    public static File download(Context c, Info i) throws Exception {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getCacheDir();
        File f = new File(dir, "update.apk");
        HttpURLConnection con = open(i.url);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); }
        } finally { con.disconnect(); }
        if (!i.sha256.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            if (!sb.toString().equals(i.sha256)) { f.delete(); throw new IOException("SHA-256 klopt niet"); }
        }
        f.setReadable(true, false);
        return f;
    }

    /** Installeert de APK: stil als device owner, anders via het Android-installatiescherm. */
    public static void install(Context c, File apk) throws Exception {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.isDeviceOwnerApp(c.getPackageName())) {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            int id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent done = new Intent(c, UpdateReceiver.class);
                PendingIntent pend = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(pend.getIntentSender());
            }
        } else {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }
    }

    public static int currentVersionCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    private static HttpURLConnection open(String url) throws IOException {
        // HttpURLConnection volgt geen redirects tussen hosts zelf betrouwbaar; doe het handmatig (GitHub -> CDN)
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "EventVOX-Webkiosk");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new IOException("Redirect zonder Location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) { con.disconnect(); throw new IOException("HTTP " + code + " bij " + url); }
            return con;
        }
        throw new IOException("Te veel redirects");
    }

    private static byte[] read(String url, int max) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > max) throw new IOException("Te groot"); }
            return bo.toByteArray();
        } finally { con.disconnect(); }
    }
}

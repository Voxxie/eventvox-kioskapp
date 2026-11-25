package nl.webkiosk;

import android.content.Context;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Een kort logboek op het toestel (de laatste MAX regels, in de app-opslag): starts, crashes, netwerkwissels,
 * updates, wifi en fouten. Gaat op verzoek mee naar EventVOX (diagnose); bevat geen wachtwoorden of tokens.
 */
public class KioskLog {
    private static final int MAX = 300;
    /** Het bestand mag tot MAX + SLACK regels groeien en wordt dan in één keer ingekort tot MAX: zo is bijna elke
     * regel een korte toevoeging, niet elke keer het hele bestand opnieuw (dat gebeurt vaak op de hoofdthread). */
    private static final int SLACK = 50;
    private static final String FILE = "kiosk.log";
    private static final LinkedList<String> lines = new LinkedList<>();
    private static boolean loaded = false;

    private static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file(c)), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
            while (lines.size() > MAX + SLACK) lines.removeFirst();
        } catch (Exception ignored) {}
    }

    public static synchronized void add(Context c, String text) {
        load(c);
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + text.replace('\n', ' ');
        lines.add(line);
        boolean trimmed = false;
        if (lines.size() > MAX + SLACK) { while (lines.size() > MAX) lines.removeFirst(); trimmed = true; }
        try {
            if (trimmed) {
                try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), false), "UTF-8")) { for (String l : lines) w.write(l + "\n"); }
            } else {
                try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), "UTF-8")) { w.write(line + "\n"); }
            }
        } catch (Exception ignored) {}
    }

    public static synchronized String text(Context c) {
        load(c);
        StringBuilder sb = new StringBuilder();
        // Alleen de laatste MAX (het bestand kan er even SLACK meer hebben)
        int skip = Math.max(0, lines.size() - MAX), n = 0;
        for (String l : lines) if (n++ >= skip) sb.append(l).append('\n');
        return sb.toString();
    }
}

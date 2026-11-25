package nl.webkiosk;

/**
 * Regels van de app zonder Android, zodat tests/PoliciesTest.java ze buiten een toestel kan controleren: wanneer een
 * automatische update mag installeren, en hoe een crash van de app in het logboek komt.
 */
public final class Policies {
    private Policies() {}

    static final long IDLE_BEFORE_INSTALL_MS = 120000;
    static final long NO_SCAN_BEFORE_INSTALL_MS = 60 * 60000L;
    static final long UPDATE_WAIT_MAX_MS = 24 * 3600000L;

    /**
     * Waarom een klaarstaande update nog niet automatisch installeert; null: hij mag nu. Niet midden in een evenement:
     * 2 minuten niet aangeraakt, een uur geen scan of tag, en aan de lader of 's nachts (00-06). Wacht hij al 24 uur,
     * dan zonder lader of nacht (een toestel dat nooit aan de lader gaat, krijgt hem ook).
     * @param idleMs sinds de laatste aanraking, -1 als er nog geen was
     * @param sinceScanMs sinds de laatste scan of tag, -1 als er nog geen was
     * @param pendingMs hoe lang deze update al klaarstaat
     * @param hour het uur van de dag (0-23)
     */
    public static String installWaitReason(long idleMs, long sinceScanMs, long pendingMs, boolean charging, int hour) {
        if (idleMs >= 0 && idleMs < IDLE_BEFORE_INSTALL_MS) return "het toestel wordt gebruikt";
        if (sinceScanMs >= 0 && sinceScanMs < NO_SCAN_BEFORE_INSTALL_MS) return "minder dan een uur na de laatste scan";
        if (pendingMs >= UPDATE_WAIT_MAX_MS) return null;
        if (!charging && hour >= 6) return "niet aan de lader (of 's nachts)";
        return null;
    }

    /** Tijden (ms, met komma's, eindigend op een komma) van de vorige crashes binnen een minuut voor now */
    public static String recentTimes(String list, long now) {
        StringBuilder sb = new StringBuilder();
        for (String x : list.split(",")) {
            try { long at = Long.parseLong(x.trim()); if (now - at < 60000) sb.append(at).append(','); } catch (NumberFormatException ignored) {}
        }
        return sb.toString();
    }

    /** Soort fout en de eerste plek in de app ("NullPointerException in MainActivity.typeText:1712"), zonder de fouttekst */
    public static String crashText(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String where = "";
        for (Throwable x : new Throwable[]{root, e}) {
            for (StackTraceElement f : x.getStackTrace()) {
                if (f.getClassName().startsWith("nl.webkiosk")) {
                    String c = f.getClassName();
                    where = " in " + c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber();
                    break;
                }
            }
            if (!where.isEmpty()) break;
        }
        return root.getClass().getSimpleName() + where;
    }
}

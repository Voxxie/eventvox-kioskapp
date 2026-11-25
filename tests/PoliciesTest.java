import nl.webkiosk.Policies;

/** Policies: wanneer een automatische update mag installeren, en hoe een crash van de app in het logboek komt */
public class PoliciesTest {
    static int failed = 0;
    static final long MIN = 60000, HOUR = 60 * MIN;

    static void same(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) { failed++; System.out.println("FOUT " + what + ": " + got + " (verwacht " + want + ")"); }
    }

    public static void main(String[] args) {
        // Update: wanneer wel, wanneer niet
        same(null, Policies.installWaitReason(-1, -1, 0, true, 14), "nog nooit aangeraakt of gescand, aan de lader");
        same(null, Policies.installWaitReason(5 * MIN, 2 * HOUR, 0, true, 14), "rustig en aan de lader");
        same(null, Policies.installWaitReason(5 * MIN, 2 * HOUR, 0, false, 3), "rustig en 's nachts");
        same("het toestel wordt gebruikt", Policies.installWaitReason(30000, 2 * HOUR, 0, true, 14), "net aangeraakt");
        same("minder dan een uur na de laatste scan", Policies.installWaitReason(5 * MIN, 20 * MIN, 0, true, 3), "net gescand");
        same("niet aan de lader (of 's nachts)", Policies.installWaitReason(5 * MIN, 2 * HOUR, 3 * HOUR, false, 14), "overdag zonder lader");
        same(null, Policies.installWaitReason(5 * MIN, 2 * HOUR, 25 * HOUR, false, 14), "na 24 uur wachten ook zonder lader");
        same("minder dan een uur na de laatste scan", Policies.installWaitReason(5 * MIN, 10 * MIN, 30 * HOUR, false, 14),
            "ook na 24 uur niet vlak na een scan");
        same(null, Policies.installWaitReason(5 * MIN, 2 * HOUR, 0, false, 5), "05:59 is nog nacht");
        same("niet aan de lader (of 's nachts)", Policies.installWaitReason(5 * MIN, 2 * HOUR, 0, false, 6), "06:00 is geen nacht meer");

        // Crashes binnen een minuut
        long now = 1_000_000_000L;
        same("", Policies.recentTimes("", now), "geen eerdere crash");
        same((now - 30000) + ",", Policies.recentTimes((now - 90000) + "," + (now - 30000) + ",", now), "alleen die van de laatste minuut");
        same((now - 1000) + ",", Policies.recentTimes("onzin," + (now - 1000), now), "onzin wordt overgeslagen");

        // De crash in het logboek: soort fout en plek in de app, nooit de fouttekst
        Exception e = new IllegalStateException("geheim-token-123");
        e.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            new StackTraceElement("nl.webkiosk.MainActivity", "typeText", "MainActivity.java", 1712)});
        same("IllegalStateException in MainActivity.typeText:1712", Policies.crashText(e), "crashText");
        Exception wrapped = new RuntimeException("buiten", new NullPointerException("binnen"));
        wrapped.getCause().setStackTrace(new StackTraceElement[]{new StackTraceElement("nl.webkiosk.Mobile", "json", "Mobile.java", 80)});
        same("NullPointerException in Mobile.json:80", Policies.crashText(wrapped), "de oorzaak");
        same(true, !Policies.crashText(e).contains("geheim"), "geen fouttekst");

        if (failed > 0) System.exit(1);
        System.out.println("ok   Policies: update op een rustig moment (lader/nacht/24 uur), crashes in het logboek");
    }
}

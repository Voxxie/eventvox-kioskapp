import nl.webkiosk.MobileTypeAccess;

/** MobileType.name: de soort mobiel netwerk in woorden */
public class MobileTypeTest {
    static int failed = 0;

    static void same(String want, String got, String what) {
        if (want == null ? got != null : !want.equals(got)) { failed++; System.out.println("FOUT " + what + ": " + got + " (verwacht " + want + ")"); }
    }

    public static void main(String[] args) {
        same("4G", MobileTypeAccess.name(13, 0), "LTE");
        same("4G+", MobileTypeAccess.name(19, 0), "LTE_CA");
        same("5G", MobileTypeAccess.name(20, 0), "NR (SA)");
        same("5G", MobileTypeAccess.name(13, 3), "LTE met 5G NSA");
        same("5G", MobileTypeAccess.name(13, 5), "NR_ADVANCED");
        same("4G+", MobileTypeAccess.name(13, 2), "LTE Advanced Pro");
        same("3G", MobileTypeAccess.name(15, 0), "HSPA+");
        same("2G", MobileTypeAccess.name(2, 0), "EDGE");
        same(null, MobileTypeAccess.name(0, 0), "onbekend");
        same(null, MobileTypeAccess.name(18, 0), "IWLAN");
        if (failed > 0) System.exit(1);
        System.out.println("ok   MobileType: 2G tot 5G, ook 5G NSA en 4G+");
    }
}

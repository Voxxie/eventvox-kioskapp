import nl.webkiosk.WifiProfiles;

/** WifiProfiles.parseQr: het standaardformaat van wifi-QR-codes, met ontsnapte tekens, en wat geen wifi-code is. */
public class WifiQrTest {
    static int failed = 0;

    static void expect(String in, String ssid, String password, String security, boolean hidden) throws Exception {
        WifiProfiles.Profile p = WifiProfiles.parseQr(in);
        String got = p == null ? "null" : field(p, "ssid") + "|" + field(p, "password") + "|" + field(p, "security") + "|" + field(p, "hidden");
        String want = ssid == null ? "null" : ssid + "|" + password + "|" + security + "|" + hidden;
        if (!got.equals(want)) { failed++; System.out.println("FOUT " + in + "\n  verwacht " + want + "\n  kreeg    " + got); }
        else System.out.println("ok   " + in);
    }

    static String field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return String.valueOf(f.get(o));
    }

    public static void main(String[] args) throws Exception {
        String b = String.valueOf((char) 92); // een backslash
        expect("WIFI:T:WPA;S:EventVOX-Ingang;P:geheim123;H:false;;", "EventVOX-Ingang", "geheim123", "WPA", false);
        expect("WIFI:S:Mijn" + b + ";Net;T:WPA2;P:a:b" + b + ";c" + b + b + "d-langer;;", "Mijn;Net", "a:b;c" + b + "d-langer", "WPA", false);
        expect("wifi:T:nopass;S:Gast;;", "Gast", "", "OPEN", false);
        expect("WIFI:T:WEP;S:Oud;P:12345;H:true;;", "Oud", "12345", "WEP", true);
        expect("WIFI:T:SAE;S:Nieuw;P:wachtwoord;;", "Nieuw", "wachtwoord", "WPA", false);
        expect("WIFI:T:WPA;S:Kort;P:1234;;", null, null, null, false);
        expect("WIFI:T:WPA;P:geenNaam1;;", null, null, null, false);
        expect("ANNA234567", null, null, null, false);
        expect("EVXADMIN:1900000000." + "c".repeat(64), null, null, null, false);
        if (failed > 0) { System.out.println(failed + " mislukt"); System.exit(1); }
        System.out.println("alles geslaagd");
    }
}

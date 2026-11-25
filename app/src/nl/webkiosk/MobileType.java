package nl.webkiosk;

/**
 * De soort mobiel netwerk in woorden ("5G", "4G+", "4G", "3G", "2G"), zonder Android: zo is het ook buiten een toestel
 * te testen (tests/MobileTypeTest.java). De getallen zijn die van TelephonyManager.NETWORK_TYPE_* en
 * TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*.
 */
final class MobileType {
    private MobileType() {}

    /** subtype: NetworkInfo.getSubtype() of TelephonyManager.getNetworkType(); override: TelephonyDisplayInfo (Android 11+), anders 0 */
    static String name(int subtype, int override) {
        // Android 11+: 5G naast 4G (NSA) of 4G+ meldt Android alleen zo; het subtype blijft dan LTE
        switch (override) {
            case 3: case 4: case 5: return "5G";   // NR_NSA, NR_NSA_MMWAVE (later NR_ADVANCED), NR_ADVANCED
            case 1: case 2: return "4G+";          // LTE_CA, LTE_ADVANCED_PRO
            default: break;
        }
        switch (subtype) {
            case 20: return "5G";                  // NR (5G zonder 4G, SA)
            case 19: return "4G+";                 // LTE_CA
            case 13: return "4G";                  // LTE
            case 3: case 5: case 6: case 8: case 9: case 10: case 12: case 14: case 15: case 17:
                return "3G";                       // UMTS, EVDO, HSDPA, HSUPA, HSPA, EHRPD, HSPA+, TD-SCDMA
            case 1: case 2: case 4: case 7: case 11: case 16:
                return "2G";                       // GPRS, EDGE, CDMA, 1xRTT, IDEN, GSM
            default: return null;                  // onbekend, of IWLAN (bellen via wifi)
        }
    }
}

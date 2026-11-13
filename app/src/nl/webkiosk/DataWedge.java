package nl.webkiosk;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Zebra DataWedge via zijn intent-API (DataWedge 6.4+; een TC51 heeft 7 of hoger): een eigen profiel voor deze app,
 * met de scanner aan, toetsaanslagen uit en de scan als broadcast naar de app (SCAN_ACTION). De app typt de code dan
 * zelf in de pagina (plus Enter), zodat het niet afhangt van welk profiel er op het toestel staat of welk venster
 * de focus heeft. Op een toestel zonder DataWedge gebeurt er niets.
 */
public class DataWedge {
    public static final String PROFILE = "EventVOX-Webkiosk";
    public static final String SCAN_ACTION = "nl.webkiosk.SCAN";
    public static final String RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION";
    public static final String EXTRA_DATA = "com.symbol.datawedge.data_string";
    public static final String EXTRA_TYPE = "com.symbol.datawedge.label_type";
    private static final String API = "com.symbol.datawedge.api.ACTION";
    private static final String PKG = "com.symbol.datawedge";

    /** True als DataWedge op dit toestel staat */
    public static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Exception e) { return false; }
    }

    /** Profiel aanmaken (als het er niet is) en instellen; de uitslag komt als RESULT_ACTION met COMMAND_IDENTIFIER. */
    public static void configure(Context c) {
        // 1. Profiel + koppeling aan deze app (alle activities)
        Bundle profile = base("CREATE_IF_NOT_EXIST");
        Bundle app = new Bundle();
        app.putString("PACKAGE_NAME", c.getPackageName());
        app.putStringArray("ACTIVITY_LIST", new String[]{"*"});
        profile.putParcelableArray("APP_LIST", new Bundle[]{app});
        send(c, profile, "webkiosk-profile");

        // 2. Scanner aan
        Bundle barcode = plugin("BARCODE");
        barcode.getBundle("PARAM_LIST").putString("scanner_selection", "auto");
        barcode.getBundle("PARAM_LIST").putString("scanner_input_enabled", "true");
        send(c, withPlugin(barcode), "webkiosk-barcode");

        // 3. Scan als broadcast naar de app
        Bundle intent = plugin("INTENT");
        intent.getBundle("PARAM_LIST").putString("intent_output_enabled", "true");
        intent.getBundle("PARAM_LIST").putString("intent_action", SCAN_ACTION);
        intent.getBundle("PARAM_LIST").putString("intent_delivery", "2"); // 2 = broadcast
        send(c, withPlugin(intent), "webkiosk-intent");

        // 4. Toetsaanslagen uit: anders komt elke scan twee keer binnen
        Bundle keys = plugin("KEYSTROKE");
        keys.getBundle("PARAM_LIST").putString("keystroke_output_enabled", "false");
        send(c, withPlugin(keys), "webkiosk-keystroke");
    }

    /** Het profiel verwijderen: DataWedge valt terug op zijn eigen profielen (Profile0, toetsaanslagen). */
    public static void remove(Context c) {
        Intent i = new Intent(API);
        i.setPackage(PKG);
        i.putExtra("com.symbol.datawedge.api.DELETE_PROFILE", PROFILE);
        c.sendBroadcast(i);
    }

    private static Bundle base(String mode) {
        Bundle b = new Bundle();
        b.putString("PROFILE_NAME", PROFILE);
        b.putString("PROFILE_ENABLED", "true");
        b.putString("CONFIG_MODE", mode);
        return b;
    }

    private static Bundle plugin(String name) {
        Bundle p = new Bundle();
        p.putString("PLUGIN_NAME", name);
        p.putString("RESET_CONFIG", "true");
        p.putBundle("PARAM_LIST", new Bundle());
        return p;
    }

    private static Bundle withPlugin(Bundle plugin) {
        Bundle b = base("UPDATE");
        b.putBundle("PLUGIN_CONFIG", plugin);
        return b;
    }

    private static void send(Context c, Bundle config, String id) {
        Intent i = new Intent(API);
        i.setPackage(PKG);
        i.putExtra("com.symbol.datawedge.api.SET_CONFIG", config);
        i.putExtra("SEND_RESULT", "LAST_RESULT");
        i.putExtra("COMMAND_IDENTIFIER", id);
        c.sendBroadcast(i);
    }
}

package nl.webkiosk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import org.json.JSONObject;

/**
 * Device-admin van de app. Na inrichten met een QR-code (fabrieksreset, 6× tikken op het welkomstscherm, Android 7+)
 * komen de instellingen uit de QR-code (PROVISIONING_ADMIN_EXTRAS_BUNDLE: url, pin en instellingen zoals in het
 * beheerscherm) hier binnen. Ze worden bewaard en bij de start van MainActivity één keer toegepast (applyProvisionExtras).
 */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override public void onProfileProvisioningComplete(Context c, Intent i) {
        try {
            PersistableBundle b = i.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
            JSONObject o = new JSONObject();
            if (b != null) for (String k : b.keySet()) {
                Object v = b.get(k);
                if (v instanceof String || v instanceof Number || v instanceof Boolean) o.put(k, String.valueOf(v));
            }
            c.getSharedPreferences("kiosk", Context.MODE_PRIVATE).edit().putString("provision_extras", o.toString()).commit();
        } catch (Exception ignored) {}
        KioskLog.add(c, "Ingericht met een QR-code");
        c.startActivity(new Intent(c, MainActivity.class).putExtra("provisioned", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}

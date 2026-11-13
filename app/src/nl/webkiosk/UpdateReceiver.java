package nl.webkiosk;

import android.content.*;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Uitslag van een stille installatie, en herstart na een update (MY_PACKAGE_REPLACED). */
public class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            c.getSharedPreferences("kiosk", Context.MODE_PRIVATE).edit()
                .putString("update_last_error", "Installatie mislukt: " + msg).apply();
            Toast.makeText(c, "Update mislukt: " + msg, Toast.LENGTH_LONG).show();
        }
    }
}

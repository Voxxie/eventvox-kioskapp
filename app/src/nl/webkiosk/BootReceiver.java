package nl.webkiosk;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Intent s = new Intent(c, MainActivity.class);
        s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(s);
    }
}

package nl.webkiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.nfc.*;
import android.nfc.tech.Ndef;
import org.json.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.Properties;

/** De accu: Android-gegevens (ACTION_BATTERY_CHANGED) als JSON voor getBattery()/webkiosk-battery, met de Zebra-accu. */
final class Battery {
    private Battery() {}

    static final String[] HEALTH = {"", "unknown", "good", "overheat", "dead", "over_voltage", "failure", "cold"};

    static JSONObject batteryJson(Intent i) {
        JSONObject o = new JSONObject();
        if (i == null) return o;
        try {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1), plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 1);
            o.put("level", scale > 0 ? Math.round(level * 100f / scale) : level);   // procent 0-100
            o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
            o.put("full", status == BatteryManager.BATTERY_STATUS_FULL);
            o.put("plugged", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "ac" : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "usb"
                    : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : plugged == 0 ? "none" : String.valueOf(plugged));
            o.put("health", health > 0 && health < HEALTH.length ? HEALTH[health] : String.valueOf(health));
            o.put("temperature", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);   // graden C
            o.put("voltage", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0);          // volt
            o.put("technology", i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
            // Alle overige (o.a. Zebra-specifieke) gegevens, zoals serienummer accu, laadcycli, gezondheid %:
            JSONObject raw = new JSONObject();
            Bundle ex = i.getExtras();
            if (ex != null) for (String k : ex.keySet()) {
                Object v = ex.get(k);
                if (v instanceof Number || v instanceof Boolean || v instanceof String) raw.put(k, v);
            }
            o.put("raw", raw);
            JSONObject pack = batteryPack(ex);
            if (pack.length() > 0) o.put("pack", pack);
        } catch (Exception ignored) {}
        return o;
    }

    /**
     * De accu zelf (slijtage), uit de extra gegevens die Zebra aan ACTION_BATTERY_CHANGED toevoegt. Welke er zijn,
     * verschilt per toestel en firmware; alleen wat er is komt erin (leeg object: niets bekend, bv. geen Zebra).
     *   percent: gezondheid 0-100 · cycles: laadcycli · decommission: Zebra raadt vervangen aan ·
     *   serial, part_number, manufactured (datum), rated_capacity (mAh)
     */
    static JSONObject batteryPack(Bundle ex) throws Exception {
        JSONObject p = new JSONObject();
        if (ex == null) return p;
        Integer health = intExtra(ex, "health_percentage", "battery_health_percentage", "health_percent");
        Integer rated = intExtra(ex, "ratedcapacity", "rated_capacity", "design_capacity");
        Integer present = intExtra(ex, "present_capacity", "full_charge_capacity");
        if (health == null && rated != null && present != null && rated > 0) health = Math.round(present * 100f / rated);
        if (health != null && health >= 0 && health <= 100) p.put("percent", health);
        Integer cycles = intExtra(ex, "cycle_count", "battery_cycle_count", "cycles");
        Integer cumulative = intExtra(ex, "total_cumulative_charge");
        if (cycles == null && cumulative != null && rated != null && rated > 0) cycles = cumulative / rated;
        if (cycles != null && cycles >= 0) p.put("cycles", cycles);
        Integer decom = intExtra(ex, "battery_decommission");
        if (decom != null && (decom == 0 || decom == 1)) p.put("decommission", decom == 1);
        String serial = strExtra(ex, "serialnumber", "battery_serial");
        if (serial != null) p.put("serial", serial);
        String part = strExtra(ex, "partnumber", "battery_partnumber");
        if (part != null) p.put("part_number", part);
        String mfd = strExtra(ex, "mfd", "manufacture_date");
        if (mfd != null) p.put("manufactured", mfd);
        if (rated != null && rated > 0) p.put("rated_capacity", rated);
        return p;
    }

    static Integer intExtra(Bundle ex, String... keys) {
        for (String k : keys) {
            Object v = ex.get(k);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) try { return Integer.parseInt(((String) v).trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    static String strExtra(Bundle ex, String... keys) {
        for (String k : keys) {
            Object v = ex.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim();
        }
        return null;
    }

}

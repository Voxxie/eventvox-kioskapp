package nl.webkiosk;

import android.graphics.Color;
import android.view.*;
import android.widget.*;

/**
 * Scherm opfrissen (opdracht refresh_screen uit EventVOX): tegen beeldretentie ("ingebrand" beeld) op een LCD, zoals bij
 * een Zebra CC600 die maandenlang hetzelfde toont. Het hele scherm wisselt op volle helderheid tussen wit, zwart, rood,
 * groen en blauw, hoogstens DURATION_MS lang; een tik, toets of scan stopt het. Het scherm blijft zolang aan.
 */
final class ScreenRefresh {
    static final long DURATION_MS = 3 * 60 * 60 * 1000L;
    static final int[] COLORS = {Color.WHITE, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE};
    final MainActivity a;
    View view;
    long until;
    int step;

    ScreenRefresh(MainActivity a) { this.a = a; }

    final Runnable next = new Runnable() {
        public void run() {
            if (view == null) return;
            if (System.currentTimeMillis() >= until) { stop("klaar"); return; }
            view.setBackgroundColor(COLORS[step++ % COLORS.length]);
            a.handler.postDelayed(this, 1000);
        }
    };

    void start() {
        if (view != null) return;
        a.findMe.stopLocate();
        View v = new View(a);
        v.setBackgroundColor(Color.WHITE);
        v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { stop("gestopt met een tik"); } });
        view = v;
        until = System.currentTimeMillis() + DURATION_MS;
        step = 0;
        a.root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        lp.screenBrightness = 1f;
        a.getWindow().setAttributes(lp);
        a.dimmed = false;
        KioskLog.add(a, "Scherm opfrissen gestart (hoogstens " + (DURATION_MS / 3600000) + " uur; een tik stopt het)");
        a.handler.post(next);
    }

    void stop(String why) {
        if (view == null) return;
        a.handler.removeCallbacks(next);
        a.root.removeView(view);
        view = null;
        if (!a.prefs.getBoolean("keep_screen_on", false)) a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        a.setBrightness(-1f);
        a.markActivity();
        a.focusWeb();
        KioskLog.add(a, "Scherm opfrissen " + why);
    }
}

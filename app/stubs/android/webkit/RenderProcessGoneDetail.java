package android.webkit;

/**
 * Alleen om tegen android-23 te compileren: deze klasse bestaat pas vanaf Android 8 (API 26), waar
 * WebViewClient.onRenderProcessGone() hem meegeeft. Wordt niet in de APK gezet (build.sh: alleen op het classpath).
 */
public abstract class RenderProcessGoneDetail {
    public abstract boolean didCrash();
    public abstract int rendererPriorityAtExit();
}

package nl.webkiosk;

/** Alleen voor de test: MobileType is package-private */
public final class MobileTypeAccess {
    public static String name(int subtype, int override) { return MobileType.name(subtype, override); }
}

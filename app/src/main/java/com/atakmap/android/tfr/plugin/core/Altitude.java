package com.atakmap.android.tfr.plugin.core;

import java.util.Locale;

/**
 * A TFR floor or ceiling exactly as published: a number with its reference.
 * Never converted between references (AGL/MSL/flight level), because that
 * would need terrain or barometric data the plugin doesn't have.
 *
 * <p>FAA XML encoding: {@code codeDistVer*} HEI = above ground (AGL),
 * ALT = above mean sea level (MSL); {@code uomDistVer*} FT = feet,
 * FL = flight level (hundreds of feet, standard pressure).
 */
public final class Altitude {

    public enum Ref {
        AGL, MSL, FL, UNKNOWN
    }

    public final Ref ref;
    /** Feet for AGL/MSL, the flight level number for FL. */
    public final int value;

    public Altitude(Ref ref, int value) {
        this.ref = ref;
        this.value = value;
    }

    /** From the FAA XML fields; UNKNOWN if they can't be read. */
    public static Altitude fromXml(String code, String value, String uom) {
        int v;
        try {
            v = (int) Math.round(Double.parseDouble(value == null ? "" : value.trim()));
        } catch (NumberFormatException e) {
            return new Altitude(Ref.UNKNOWN, 0);
        }
        if (v < 0 || v > 100000)
            return new Altitude(Ref.UNKNOWN, 0);
        String u = uom == null ? "" : uom.trim().toUpperCase(Locale.US);
        String c = code == null ? "" : code.trim().toUpperCase(Locale.US);
        if (u.equals("FL"))
            return new Altitude(Ref.FL, v);
        if (!u.equals("FT"))
            return new Altitude(Ref.UNKNOWN, v);
        if (c.equals("HEI"))
            return new Altitude(Ref.AGL, v);
        if (c.equals("ALT"))
            return new Altitude(Ref.MSL, v);
        return new Altitude(Ref.UNKNOWN, v);
    }

    /** 0 ft AGL, or 0 ft MSL (how some FAA files encode "SFC", e.g. 6/4857). */
    public boolean isSurface() {
        return (ref == Ref.AGL || ref == Ref.MSL) && value == 0;
    }

    /**
     * Height in feet used only to draw the 3D extrusion from the ground.
     * MSL and flight levels are taken as heights above ground, which can only
     * overstate the drawn height. NaN if unknown.
     */
    public double extrusionFeet() {
        switch (ref) {
            case AGL:
            case MSL:
                return value;
            case FL:
                return value * 100.0;
            default:
                return Double.NaN;
        }
    }

    /** "SFC", "4,999 ft AGL", "2,500 ft MSL", "FL430" or "unknown". */
    public String format() {
        switch (ref) {
            case AGL:
                return value == 0 ? "SFC"
                        : String.format(Locale.US, "%,d ft AGL", value);
            case MSL:
                return value == 0 ? "SFC"
                        : String.format(Locale.US, "%,d ft MSL", value);
            case FL:
                return String.format(Locale.US, "FL%03d", value);
            default:
                return "unknown";
        }
    }

    /** "SFC–4,999 ft AGL" style range. */
    public static String range(Altitude floor, Altitude ceiling) {
        return floor.format() + "–" + ceiling.format();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Altitude))
            return false;
        Altitude a = (Altitude) o;
        return a.ref == ref && a.value == value;
    }

    @Override
    public int hashCode() {
        return ref.hashCode() * 31 + value;
    }

    @Override
    public String toString() {
        return format();
    }
}

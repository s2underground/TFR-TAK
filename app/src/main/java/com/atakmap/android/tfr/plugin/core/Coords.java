package com.atakmap.android.tfr.plugin.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the FAA TFR XML coordinate format: decimal degrees with a
 * hemisphere letter, for example {@code 43.31723864N} or
 * {@code 075.40694444W}. Anything else is rejected rather than guessed.
 */
public final class Coords {

    private Coords() {
    }

    private static final Pattern P = Pattern
            .compile("^\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*([NSEW])\\s*$");

    /** Latitude in degrees, or NaN if the text isn't a valid latitude. */
    public static double lat(String s) {
        return parse(s, true);
    }

    /** Longitude in degrees, or NaN if the text isn't a valid longitude. */
    public static double lon(String s) {
        return parse(s, false);
    }

    private static double parse(String s, boolean isLat) {
        if (s == null)
            return Double.NaN;
        Matcher m = P.matcher(s);
        if (!m.matches())
            return Double.NaN;
        char h = m.group(2).charAt(0);
        if (isLat && h != 'N' && h != 'S')
            return Double.NaN;
        if (!isLat && h != 'E' && h != 'W')
            return Double.NaN;
        double v;
        try {
            v = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (v > (isLat ? 90 : 180))
            return Double.NaN;
        return (h == 'S' || h == 'W') ? -v : v;
    }

    /** A point from two coordinate strings, or null if either is invalid. */
    public static LatLon point(String lat, String lon) {
        double a = lat(lat), b = lon(lon);
        if (Double.isNaN(a) || Double.isNaN(b))
            return null;
        return new LatLon(a, b);
    }
}

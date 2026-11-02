package com.atakmap.android.tfr.plugin.ui;

import com.atakmap.android.tfr.plugin.core.Geo;
import com.atakmap.android.tfr.plugin.core.Schedule;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Text formatting for times, durations and distances. Pure Java. */
public final class Format {

    private final TimeZone local;

    public Format(TimeZone local) {
        this.local = local;
    }

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static String fmt(String pattern, TimeZone tz, long t) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(tz);
        return f.format(new Date(t));
    }

    /** "1415Z" */
    public String zulu(long t) {
        return fmt("HHmm", UTC, t) + "Z";
    }

    /** "Nov 2 1415Z" */
    public String zuluWithDate(long t) {
        return fmt("MMM d HHmm", UTC, t) + "Z";
    }

    /** "09:15 EST" in the device's zone. */
    public String local(long t) {
        return fmt("HH:mm z", local, t);
    }

    /** "Nov 2 1415Z (09:15 EST)"; "no end" for FOREVER. */
    public String when(long t) {
        if (t == Schedule.FOREVER)
            return "no published end";
        return zuluWithDate(t) + " (" + local(t) + ")";
    }

    /** "45 min", "3 h 10 min", "2 d 4 h". */
    public static String duration(long ms) {
        long min = Math.max(0, (ms + 59999) / 60000);
        if (min < 60)
            return min + " min";
        long h = min / 60, m = min % 60;
        if (h < 48)
            return m == 0 ? h + " h" : h + " h " + m + " min";
        long d = h / 24;
        h = h % 24;
        return h == 0 ? d + " d" : d + " d " + h + " h";
    }

    /** "inside", "0.4 NM", "12 NM", "1,240 NM". */
    public static String distance(double meters) {
        if (Double.isNaN(meters))
            return "";
        if (meters <= 0)
            return "inside";
        double nm = meters / Geo.METERS_PER_NM;
        if (nm < 10)
            return String.format(Locale.US, "%.1f NM", nm);
        return String.format(Locale.US, "%,d NM", Math.round(nm));
    }

    /** "5 min ago", "2 h 3 min ago", "never". */
    public static String ago(long now, long then) {
        if (then <= 0)
            return "never";
        long d = now - then;
        if (d < 60000)
            return "just now";
        return duration(d - 59999) + " ago";
    }
}

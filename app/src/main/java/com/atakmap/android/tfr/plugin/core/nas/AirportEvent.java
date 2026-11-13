package com.atakmap.android.tfr.plugin.core.nas;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One airport's traffic-management event from the FAA's NAS Status
 * (nasstatus.faa.gov/api/airport-events, the data behind the NAS Status
 * page): ground stop, ground delay program, closure, departure or arrival
 * delays. One airport can have several; each becomes its own event.
 * Pure Java (org.json).
 */
public final class AirportEvent {

    public enum Type {
        GROUND_STOP("GS", "Ground stop", 0), CLOSED("CLSD", "Closed", 1),
        DELAY_PROGRAM("GDP", "Ground delay program", 2),
        DEPARTURE_DELAY("DEP", "Departure delays", 3),
        ARRIVAL_DELAY("ARR", "Arrival delays", 3);

        public final String tag, label;
        /** 0 = most serious. */
        public final int rank;

        Type(String tag, String label, int rank) {
            this.tag = tag;
            this.label = label;
            this.rank = rank;
        }
    }

    public final String airport;
    public final String name;
    public final double lat, lon;
    public final Type type;
    /** One line: "Avg 45 min, max 2 h 46" / "Departures 1 h 01–1 h 15". */
    public final String what;
    /** Reason as given ("wind", "TM INITIATIVES:STOP:NAS GS"). */
    public final String reason;
    /** ms UTC; 0 if not given. */
    public final long startMs, endMs;
    /** Closure NOTAM text, or "" */
    public final String notam;
    /** Command Center advisory link, or "" (fly.faa.gov only). */
    public final String advisoryUrl;
    /**
     * A closure with exceptions ("TO NON SKED TRANSIENT GA ACFT EXC 24HR
     * PPR"): most of the FAA's closure entries. Shown amber, not alerted.
     */
    public final boolean partial;

    AirportEvent(String airport, String name, double lat, double lon,
            Type type, String what, String reason, long startMs, long endMs,
            String notam, String advisoryUrl) {
        this(airport, name, lat, lon, type, what, reason, startMs, endMs,
                notam, advisoryUrl, false);
    }

    AirportEvent(String airport, String name, double lat, double lon,
            Type type, String what, String reason, long startMs, long endMs,
            String notam, String advisoryUrl, boolean partial) {
        this.partial = partial;
        this.airport = airport;
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.type = type;
        this.what = what;
        this.reason = reason;
        this.startMs = startMs;
        this.endMs = endMs;
        this.notam = notam;
        this.advisoryUrl = advisoryUrl;
    }

    public String key() {
        return airport + "/" + type.tag + "/" + startMs;
    }

    public static final int MAX_BYTES = 4 * 1024 * 1024;

    public static final class ParseException extends Exception {
        public ParseException(String m) {
            super(m);
        }
    }

    /** Parses /api/airport-events. A valid empty array = no events. */
    public static List<AirportEvent> parse(byte[] json) throws ParseException {
        if (json == null || json.length > MAX_BYTES)
            throw new ParseException("missing or too large");
        String s = new String(json, StandardCharsets.UTF_8).trim();
        if (s.startsWith("\uFEFF"))
            s = s.substring(1);
        JSONArray arr;
        try {
            arr = new JSONArray(s);
        } catch (JSONException e) {
            throw new ParseException("not a JSON list");
        }
        List<AirportEvent> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && i < 2000; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null)
                continue;
            String id = str(o, "airportId").toUpperCase(Locale.US);
            if (!id.matches("[A-Z0-9]{3,4}"))
                continue;
            String name = str(o, "airportLongName");
            double lat = num(o, "latitude"), lon = num(o, "longitude");
            if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) {
                lat = Double.NaN;
                lon = Double.NaN;
            }
            JSONObject gs = o.optJSONObject("groundStop");
            if (gs != null)
                out.add(new AirportEvent(id, name, lat, lon, Type.GROUND_STOP,
                        "Flights to " + id + " held at departure",
                        str(gs, "impactingCondition"), time(gs, "startTime"),
                        firstTime(gs, "endTime", "programExpirationTime"), "",
                        url(str(gs, "advisoryUrl"))));
            JSONObject gd = o.optJSONObject("groundDelay");
            if (gd != null) {
                double avg = gd.optDouble("avgDelay", Double.NaN);
                double max = gd.optDouble("maxDelay", Double.NaN);
                out.add(new AirportEvent(id, name, lat, lon,
                        Type.DELAY_PROGRAM,
                        "Avg " + minutes(avg) + ", max " + minutes(max),
                        str(gd, "impactingCondition"), time(gd, "startTime"),
                        time(gd, "endTime"), "", url(str(gd, "advisoryUrl"))));
            }
            JSONObject cl = o.optJSONObject("airportClosure");
            if (cl != null)
                out.add(closure(id, name, lat, lon, cl));
            JSONObject ff = o.optJSONObject("freeForm");
            if (ff != null && str(ff, "simpleText").toUpperCase(Locale.US)
                    .contains("CLSD"))
                out.add(closure(id, name, lat, lon, ff));
            JSONObject dd = o.optJSONObject("departureDelay");
            if (dd != null)
                out.add(delay(id, name, lat, lon, Type.DEPARTURE_DELAY, dd));
            JSONObject ad = o.optJSONObject("arrivalDelay");
            if (ad != null)
                out.add(delay(id, name, lat, lon, Type.ARRIVAL_DELAY, ad));
        }
        return Collections.unmodifiableList(out);
    }

    private static AirportEvent closure(String id, String name, double lat,
            double lon, JSONObject c) {
        String text = str(c, "text");
        String u = text.toUpperCase(Locale.US);
        boolean partial = u.startsWith("TO ") || u.contains("EXC")
                || u.contains("PPR");
        return new AirportEvent(id, name, lat, lon, Type.CLOSED,
                text.isEmpty() ? "Closed" : "Closed " + text, "",
                time(c, "startTime"), time(c, "endTime"),
                str(c, "simpleText"), "", partial);
    }

    private static AirportEvent delay(String id, String name, double lat,
            double lon, Type t, JSONObject d) {
        JSONObject ad = d.optJSONObject("arrivalDeparture");
        String min = ad == null ? "" : str(ad, "min");
        String max = ad == null ? "" : str(ad, "max");
        String trend = ad == null ? "" : str(ad, "trend");
        String what = (t == Type.DEPARTURE_DELAY ? "Departures " : "Arrivals ")
                + (min.isEmpty() ? "" : min + (max.isEmpty() ? "" : " to "))
                + max + (trend.isEmpty() ? "" : ", " + trend.toLowerCase(Locale.US));
        return new AirportEvent(id, name, lat, lon, t, what.trim(),
                str(d, "reason"), time(d, "updateTime"), 0, "", "");
    }

    static String minutes(double m) {
        if (Double.isNaN(m) || m < 0)
            return "?";
        long t = Math.round(m);
        return t < 60 ? t + " min" : (t / 60) + " h " + String.format(Locale.US, "%02d", t % 60);
    }

    /** Only Command Center advisory links are kept (shown as text). */
    static String url(String u) {
        return u.startsWith("https://www.fly.faa.gov/") && u.length() < 600 ? u
                : "";
    }

    private static String str(JSONObject o, String k) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL)
            return "";
        String s = String.valueOf(v).trim();
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private static double num(JSONObject o, String k) {
        String s = str(o, k);
        try {
            return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static long firstTime(JSONObject o, String a, String b) {
        long t = time(o, a);
        return t > 0 ? t : time(o, b);
    }

    /** ISO 8601 UTC ("2026-11-13T21:54:00Z", optional .sss, +00:00). */
    static long time(JSONObject o, String k) {
        String s = str(o, k);
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]00:?00)?"))
            return 0;
        java.util.Calendar c = java.util.Calendar
                .getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(Integer.parseInt(s.substring(0, 4)),
                Integer.parseInt(s.substring(5, 7)) - 1,
                Integer.parseInt(s.substring(8, 10)),
                Integer.parseInt(s.substring(11, 13)),
                Integer.parseInt(s.substring(14, 16)),
                s.length() >= 19 && s.charAt(16) == ':'
                        ? Integer.parseInt(s.substring(17, 19))
                        : 0);
        return c.getTimeInMillis();
    }
}

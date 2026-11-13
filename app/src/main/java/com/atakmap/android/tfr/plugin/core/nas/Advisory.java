package com.atakmap.android.tfr.plugin.core.nas;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An FAA Command Center (ATCSCC) advisory, from the public advisories
 * database at www.fly.faa.gov/adv. National events (the Nov 13, 2026
 * nationwide ground stop, ADVZY 111 and 120) appear ONLY here, not in the
 * per-airport NAS Status feed.
 *
 * <p>Pure Java, no Android. All times UTC.
 */
public final class Advisory {

    /** What a national advisory is about; one active advisory per kind. */
    public enum Kind {
        GROUND_STOP("GROUND STOP", true), HOLDING("AIRBORNE HOLDING", false),
        DIVERSION("DIVERSION RECOVERY", false), ATC_ZERO("ATC ZERO", true),
        OTHER("NATIONAL", false);

        public final String label;
        /** Shown in red (traffic stopped), else amber. */
        public final boolean severe;

        Kind(String label, boolean severe) {
            this.label = label;
            this.severe = severe;
        }
    }

    /** Advisory number of the day (1..999). */
    public final int number;
    /** UTC date it belongs to, as yyyyMMdd (e.g. 20261113). */
    public final int date;
    /** Control element / facility column ("DCC", "ALL/DCC", "ORD/ZAU"). */
    public final String facility;
    /** Brief title ("NATIONWIDE GROUND STOP"). */
    public final String title;
    /** Send time, ms UTC; 0 if unreadable. */
    public final long sentMs;

    Advisory(int number, int date, String facility, String title,
            long sentMs) {
        this.number = number;
        this.date = date;
        this.facility = facility;
        this.title = title;
        this.sentMs = sentMs;
    }

    /** Stable key: date and number. */
    public String key() {
        return date + "-" + number;
    }

    /** True for a cancellation ("… CNX", "… CANCELLATION"). */
    public boolean isCancellation() {
        String t = title.toUpperCase(Locale.US);
        return t.contains("CNX") || t.contains("CANCEL");
    }

    /**
     * Nation-wide event from the Command Center, or null. Requires the
     * Command Center (DCC) or ALL as the control element, and a title that
     * says national / nation-wide / all airports / ATC zero. Airport ground
     * stops ("CDM GROUND STOP" at ORD/ZAU) are not national.
     */
    public Kind nationalKind() {
        String f = facility.toUpperCase(Locale.US);
        if (!(f.contains("DCC") || f.startsWith("ALL")))
            return null;
        String t = title.toUpperCase(Locale.US).replace('\u00A0', ' ');
        boolean national = t.contains("NATIONAL") || t.contains("NATIONWIDE")
                || t.contains("NATION WIDE") || t.contains("ALL AIRPORTS")
                || t.contains("ATC ZERO");
        if (!national)
            return null;
        if (t.contains("GROUND STOP") || t.contains(" GS "))
            return Kind.GROUND_STOP;
        if (t.contains("ATC ZERO"))
            return Kind.ATC_ZERO;
        if (t.contains("HOLDING"))
            return Kind.HOLDING;
        if (t.contains("DIVERSION"))
            return Kind.DIVERSION;
        return Kind.OTHER;
    }

    // ---- list page ---------------------------------------------------

    private static final Pattern ROW = Pattern.compile(
            "advn=(\\d{1,4})\"\\s*>\\s*\\d{1,4}\\s*</a>\\s*</td>\\s*<td>(.*?)</td>"
                    + "\\s*<td>(.*?)</td>\\s*<td>(.*?)</td>\\s*<td>(.*?)</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SENT = Pattern
            .compile("(\\d{2})/(\\d{2})/(\\d{2})\\s+(\\d{2}):(\\d{2})");
    /** The server's answer when it had nothing (or failed): see parseList. */
    static final String NO_MATCH = "NO ADVISORIES MATCH";

    /** Largest list page we parse (a busy day is ~45 kB). */
    public static final int MAX_LIST_BYTES = 2 * 1024 * 1024;

    /**
     * Rows of an advisories list page for one UTC date (yyyyMMdd).
     * Returns null when the page has no rows: the FAA server answers "NO
     * ADVISORIES MATCH" to most requests at random (Nov 13, 2026: 4 of 5),
     * so an empty page never means "no advisories".
     */
    public static List<Advisory> parseList(byte[] html, int date) {
        if (html == null || html.length > MAX_LIST_BYTES)
            return null;
        String s = new String(html, StandardCharsets.ISO_8859_1);
        Matcher m = ROW.matcher(s);
        List<Advisory> out = new ArrayList<>();
        while (m.find()) {
            int n;
            try {
                n = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String fac = clean(m.group(2));
            String title = clean(m.group(4));
            // "ATCSCC ADVZY 101 DCC 11/13/26 FCA RQD | NAME: …": keep the
            // part after the date as the title.
            title = title.replaceFirst(
                    "^ATCSCC\\s+ADVZY\\s+\\d+\\s+\\S+\\s+\\d{2}/\\d{2}/\\d{2,4}\\s+",
                    "");
            out.add(new Advisory(n, date, fac, title,
                    sentMs(clean(m.group(5)))));
        }
        return out.isEmpty() ? null : Collections.unmodifiableList(out);
    }

    static long sentMs(String s) {
        Matcher m = SENT.matcher(s);
        if (!m.find())
            return 0;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"),
                Locale.US);
        c.clear();
        c.set(2000 + Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(1)) - 1, Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        return c.getTimeInMillis();
    }

    /** Text of an HTML fragment: tags out, entities decoded, spaces folded. */
    static String clean(String html) {
        String t = html.replaceAll("(?i)<br\\s*/?>", " | ")
                .replaceAll("<[^>]*>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace('\u00A0', ' ');
        return t.replaceAll("\\s+", " ").trim();
    }

    // ---- advisory text --------------------------------------------------

    /** The body of one advisory page. */
    public static final class Text {
        /** "ATCSCC ADVZY 120 DCC 11/13/2026 NATIONWIDE GROUND STOP". */
        public final String header;
        /** Everything after the header line, line breaks kept. */
        public final String body;
        /** EVENT TIME window, ms UTC; 0 if absent. */
        public final long eventStartMs, eventEndMs;

        Text(String header, String body, long start, long end) {
            this.header = header;
            this.body = body;
            this.eventStartMs = start;
            this.eventEndMs = end;
        }
    }

    public static final int MAX_TEXT_BYTES = 512 * 1024;
    private static final Pattern EVENT = Pattern.compile(
            "EVENT TIME:\\s*(\\d{2})/(\\d{4})\\s*-\\s*(\\d{2})/(\\d{4})");

    /**
     * Parses one advisory page; null when the server answered "NO
     * Advisory." (it does so at random: retry) or the page is unreadable.
     * {@code date} = the advisory's UTC date (yyyyMMdd), to place the
     * day/hhmm event times.
     */
    public static Text parseText(byte[] html, int date) {
        if (html == null || html.length > MAX_TEXT_BYTES)
            return null;
        String s = new String(html, StandardCharsets.ISO_8859_1);
        s = s.replaceAll("(?is)<script.*?</script>|<style.*?</style>", "");
        String t = s.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]*>", "\n").replace("&nbsp;", " ")
                .replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'");
        List<String> lines = new ArrayList<>();
        for (String l : t.split("\n")) {
            String x = l.trim();
            if (!x.isEmpty())
                lines.add(x);
        }
        int start = -1;
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).contains("ADVZY")) {
                start = i;
                break;
            }
        if (start < 0)
            return null;
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++)
            if (lines.get(i).equals("FAA.gov Home")) {
                end = i;
                break;
            }
        StringBuilder b = new StringBuilder();
        for (int i = start + 1; i < end && b.length() < 8000; i++) {
            if (b.length() > 0)
                b.append('\n');
            b.append(lines.get(i));
        }
        String body = b.toString();
        long es = 0, ee = 0;
        Matcher m = EVENT.matcher(body);
        if (m.find()) {
            es = dayTime(date, Integer.parseInt(m.group(1)), m.group(2));
            ee = dayTime(date, Integer.parseInt(m.group(3)), m.group(4));
            if (ee > 0 && es > 0 && ee < es)
                ee = 0; // unreadable
        }
        return new Text(lines.get(start), body, es, ee);
    }

    /**
     * Day-of-month + hhmm near an advisory's date: the same month, or the
     * next/previous month when the day wraps (31/2300 - 01/0400).
     */
    static long dayTime(int date, int day, String hhmm) {
        int y = date / 10000, mo = date / 100 % 100, d = date % 100;
        int hh = Integer.parseInt(hhmm.substring(0, 2));
        int mm = Integer.parseInt(hhmm.substring(2, 4));
        if (day < 1 || day > 31 || hh > 24 || mm > 59)
            return 0;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"),
                Locale.US);
        c.clear();
        c.set(y, mo - 1, 1, hh, mm);
        if (day < d - 15)
            c.add(Calendar.MONTH, 1);
        else if (day > d + 15)
            c.add(Calendar.MONTH, -1);
        if (day > c.getActualMaximum(Calendar.DAY_OF_MONTH))
            return 0;
        c.set(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    /** yyyyMMdd of a UTC instant. */
    public static int dateOf(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"),
                Locale.US);
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }
}

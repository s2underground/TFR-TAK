package com.atakmap.android.tfr.plugin.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Parses one FAA TFR detail file ({@code tfr.faa.gov/download/detail_N_NNNN.xml},
 * root {@code XNOTAM-Update}). Structure learned from the 54 TFRs active on
 * Nov 2, 2026 (test fixtures).
 *
 * <p>Rules: the file must be the NOTAM that was asked for; anything that
 * can't be read with certainty becomes a warning and a conservative choice
 * (schedule treated as active, FAA outline used, or no outline with "read
 * the NOTAM text"), never a silent drop.
 */
public final class TfrXmlParser {

    private TfrXmlParser() {
    }

    /** The file isn't a usable TFR detail. */
    public static final class ParseException extends Exception {
        public ParseException(String msg) {
            super(msg);
        }
    }

    /** Max accepted file size (largest seen: 115 kB). */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    public static Tfr parse(byte[] xml, String expectedId)
            throws ParseException {
        if (xml == null || xml.length == 0)
            throw new ParseException("empty file");
        if (xml.length > MAX_BYTES)
            throw new ParseException("file too large");
        if (!NotamId.isValid(expectedId))
            throw new ParseException("invalid NOTAM id");
        // No DTDs or entities in FAA files: refuse any, before parsing.
        String head = new String(xml, 0, Math.min(xml.length, 4096),
                StandardCharsets.ISO_8859_1).toUpperCase(Locale.US);
        if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY"))
            throw new ParseException("unexpected DOCTYPE");

        Document doc = parseDom(xml);
        Element root = doc.getDocumentElement();
        if (root == null || !"XNOTAM-Update".equals(root.getTagName()))
            throw new ParseException("not an FAA TFR file");
        Element not = first(root, "Not");
        if (not == null)
            throw new ParseException("no NOTAM in file");

        List<String> warnings = new ArrayList<>();

        String id = idOf(not);
        if (id == null || !NotamId.same(id, expectedId))
            throw new ParseException("file is for NOTAM " + id
                    + ", expected " + expectedId);

        long issued = time(text(not, "NotUid", "dateIssued"));
        // Some long-running TFRs carry no start time (6/5945, in effect since
        // Sep 2026 per its text): no start = already in effect.
        String effS = text(not, "dateEffective");
        long eff = effS == null ? 0 : time(effS);
        String expS = text(not, "dateExpire");
        long exp = expS == null ? Schedule.FOREVER : time(expS);
        if (eff < 0) {
            warnings.add("Effective time unreadable; treated as in effect now.");
            eff = 0;
        }
        if (expS != null && exp < 0) {
            warnings.add("Expiry time unreadable; treated as no expiry.");
            exp = Schedule.FOREVER;
        }

        String txt = text(not, "txtDescrUSNS");
        if (txt == null || txt.trim().isEmpty())
            txt = text(not, "txtDescrTraditional");
        if (txt == null || txt.trim().isEmpty()) {
            txt = "";
            warnings.add("The FAA file has no NOTAM text.");
        }

        Element tfrNot = first(not, "TfrNot");
        String cfr = tfrNot == null ? null : text(tfrNot, "codeType");
        String city = text(not, "AffLocGroup", "txtNameCity");
        String state = text(not, "AffLocGroup", "txtNameUSState");
        String facility = text(not, "codeFacility");
        String localName = text(not, "NotUid", "txtLocalName");

        List<TfrArea> areas = tfrNot == null ? new ArrayList<TfrArea>()
                : areas(tfrNot, eff, exp, warnings);
        if (areas.isEmpty()) {
            // Keep the TFR (its text is authoritative) with no outline.
            areas.add(new TfrArea("", new Altitude(Altitude.Ref.UNKNOWN, 0),
                    new Altitude(Altitude.Ref.UNKNOWN, 0),
                    new Schedule(listOf(new Schedule.Period(eff, exp)), false),
                    null, TfrArea.Source.NONE,
                    "No areas in the FAA data. Read the NOTAM text."));
        }

        return new Tfr(id, cfr, Category.of(cfr, null), facility, city,
                state, localName, issued, eff, exp, txt.trim(), areas, warnings);
    }

    private static Document parseDom(byte[] xml) throws ParseException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            f.setExpandEntityReferences(false);
            trySetFeature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(f, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(f, "http://xml.org/sax/features/external-parameter-entities", false);
            trySetFeature(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new InputSource(new ByteArrayInputStream(xml)));
        } catch (Exception e) {
            throw new ParseException("malformed XML");
        }
    }

    private static void trySetFeature(DocumentBuilderFactory f, String name,
            boolean v) {
        try {
            f.setFeature(name, v);
        } catch (Exception ignored) {
            // Not every platform parser knows every feature; DOCTYPEs are
            // refused before parsing anyway.
        }
    }

    // ---- id -----------------------------------------------------------

    static String idOf(Element not) {
        String year = text(not, "NotUid", "dateIndexYear");
        String seq = text(not, "NotUid", "noSeqNo");
        if (year == null || seq == null)
            return null;
        year = year.trim();
        seq = seq.trim();
        if (!year.matches("\\d{4}") || !seq.matches("\\d{1,5}"))
            return null;
        return year.substring(3) + "/" + NotamId.padSeq(seq);
    }

    // ---- areas --------------------------------------------------------

    private static List<TfrArea> areas(Element tfrNot, long eff, long exp,
            List<String> warnings) {
        // Shapes and merged outlines are linked to areas by id; collect them
        // across the whole TfrNot so the exact nesting doesn't matter.
        Map<String, Element> shapes = new HashMap<>();
        for (Element s : all(tfrNot, "aseShapes")) {
            String sid = text(s, "AseUid", "codeId");
            if (sid != null)
                shapes.put(sid.trim(), s);
        }
        Map<String, Element> merged = new HashMap<>();
        for (Element m : all(tfrNot, "abdMergedArea")) {
            String aid = text(m, "AbdUid", "AseUid", "codeId");
            if (aid != null)
                merged.put(aid.trim(), m);
        }
        Map<String, List<String[]>> links = new LinkedHashMap<>();
        for (Element aac : all(tfrNot, "Aac")) {
            String chi = text(aac, "AacUid", "AseUidChi", "codeId");
            String par = text(aac, "AacUid", "AseUidPar", "codeId");
            if (chi == null || par == null)
                continue;
            List<String[]> l = links.get(chi.trim());
            if (l == null)
                links.put(chi.trim(), l = new ArrayList<>());
            l.add(new String[] { par.trim(), up(text(aac, "codeType")),
                    up(text(aac, "codeOpr")) });
        }

        List<TfrArea> out = new ArrayList<>();
        for (Element a : all(tfrNot, "aseTFRArea")) {
            String aid = text(a, "AseUid", "codeId");
            aid = aid == null ? "" : aid.trim();
            String name = text(a, "txtName");
            Altitude ceiling = Altitude.fromXml(text(a, "codeDistVerUpper"),
                    text(a, "valDistVerUpper"), text(a, "uomDistVerUpper"));
            Altitude floor = Altitude.fromXml(text(a, "codeDistVerLower"),
                    text(a, "valDistVerLower"), text(a, "uomDistVerLower"));
            String label = name == null || name.trim().isEmpty() ? "area"
                    : name.trim();
            if (ceiling.ref == Altitude.Ref.UNKNOWN
                    || floor.ref == Altitude.Ref.UNKNOWN)
                warnings.add("Altitudes of " + label
                        + " unreadable; see the NOTAM text.");
            Schedule sched = schedule(a, eff, exp, label, warnings);
            out.add(geometry(name, floor, ceiling, sched, links.get(aid),
                    shapes, merged.get(aid), label, warnings));
        }
        return out;
    }

    private static TfrArea geometry(String name, Altitude floor,
            Altitude ceiling, Schedule sched, List<String[]> link,
            Map<String, Element> shapes, Element merged, String label,
            List<String> warnings) {
        List<LatLon> faa = null;
        String faaProblem = null;
        if (merged != null) {
            try {
                faa = faaOutline(merged);
            } catch (Boundary.GeometryException e) {
                faaProblem = e.getMessage();
            }
        }

        List<LatLon> def = null;
        String defProblem = null;
        if (link != null && !link.isEmpty()) {
            if (link.size() != 1 || !"BOM".equals(link.get(0)[1])
                    || !"BASE".equals(link.get(0)[2])) {
                defProblem = "combined shapes";
            } else {
                Element shape = shapes.get(link.get(0)[0]);
                if (shape == null) {
                    defProblem = "shape missing";
                } else {
                    try {
                        def = Boundary.build(vertices(shape));
                    } catch (Boundary.GeometryException e) {
                        defProblem = e.getMessage();
                    }
                }
            }
        } else if (merged != null) {
            defProblem = "no published definition";
        }

        if (def != null && faa != null) {
            double dev = Boundary.maxDeviation(def, faa);
            double tol = Boundary.tolerance(def);
            if (dev <= tol)
                return new TfrArea(name, floor, ceiling, sched, def,
                        TfrArea.Source.DEFINITION_CHECKED, "");
            String note = String.format(Locale.US,
                    "Published definition and FAA outline differ by %.0f m; showing the FAA outline.",
                    dev);
            warnings.add(label + ": " + note);
            return new TfrArea(name, floor, ceiling, sched, faa,
                    TfrArea.Source.FAA_OUTLINE, note);
        }
        if (def != null) {
            String note = faaProblem == null ? ""
                    : "FAA outline unreadable (" + faaProblem + ").";
            return new TfrArea(name, floor, ceiling, sched, def,
                    TfrArea.Source.DEFINITION, note);
        }
        if (faa != null) {
            String note = defProblem == null ? ""
                    : "Published definition not usable (" + defProblem
                            + "); showing the FAA outline.";
            if (defProblem != null && !"no published definition".equals(defProblem))
                warnings.add(label + ": " + note);
            return new TfrArea(name, floor, ceiling, sched, faa,
                    TfrArea.Source.FAA_OUTLINE, note);
        }
        String note;
        if (link == null && merged == null)
            note = "No map graphic in the FAA data. Read the NOTAM text for the area.";
        else {
            note = "Outline could not be read ("
                    + (defProblem != null ? defProblem : faaProblem)
                    + "). Read the NOTAM text for the area.";
            warnings.add(label + ": " + note);
        }
        return new TfrArea(name, floor, ceiling, sched, null,
                TfrArea.Source.NONE, note);
    }

    static List<Boundary.Vertex> vertices(Element shape)
            throws Boundary.GeometryException {
        Element abd = first(shape, "Abd");
        if (abd == null)
            throw new Boundary.GeometryException("shape has no boundary");
        List<Boundary.Vertex> out = new ArrayList<>();
        for (Element v : children(abd, "Avx")) {
            String type = up(text(v, "codeType"));
            if (type.isEmpty())
                throw new Boundary.GeometryException("vertex without type");
            LatLon p = Coords.point(text(v, "geoLat"), text(v, "geoLong"));
            LatLon c = Coords.point(text(v, "geoLatArc"), text(v, "geoLongArc"));
            double r = radiusM(text(v, "valRadiusArc"), text(v, "uomRadiusArc"));
            out.add(new Boundary.Vertex(type, p, c, r));
        }
        return out;
    }

    private static double radiusM(String val, String uom) {
        if (val == null)
            return Double.NaN;
        double v;
        try {
            v = Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        String u = up(uom);
        if (u.equals("NM"))
            return v * Geo.METERS_PER_NM;
        if (u.equals("KM"))
            return v * 1000;
        if (u.equals("M"))
            return v;
        if (u.equals("FT"))
            return v * Geo.METERS_PER_FOOT;
        if (u.equals("MI"))
            return v * 1609.344;
        return Double.NaN;
    }

    static List<LatLon> faaOutline(Element merged)
            throws Boundary.GeometryException {
        List<LatLon> pts = new ArrayList<>();
        for (Element v : children(merged, "Avx")) {
            String type = up(text(v, "codeType"));
            if (!type.equals("GRC"))
                throw new Boundary.GeometryException(
                        "FAA outline vertex type " + type);
            LatLon p = Coords.point(text(v, "geoLat"), text(v, "geoLong"));
            if (p == null)
                throw new Boundary.GeometryException("invalid FAA outline point");
            if (pts.isEmpty() || !pts.get(pts.size() - 1).equals(p))
                pts.add(p);
        }
        List<LatLon> ring = Boundary.open(pts);
        if (ring.size() < 3)
            throw new Boundary.GeometryException("FAA outline has under 3 points");
        return Boundary.densifyEdges(ring);
    }

    // ---- schedule -----------------------------------------------------

    static Schedule schedule(Element area, long eff, long exp, String label,
            List<String> warnings) {
        boolean uncertain = false;
        Set<Schedule.Day> days = EnumSet.noneOf(Schedule.Day.class);
        for (Element d : children(area, "dayCode")) {
            String s = d.getTextContent() == null ? "" : d.getTextContent().trim();
            if (s.equalsIgnoreCase("Daily"))
                continue;
            Schedule.Day day = Schedule.Day.parse(s);
            if (day == null)
                uncertain = true;
            else
                days.add(day);
        }

        List<Schedule.Period> periods = new ArrayList<>();
        for (Element g : children(area, "ScheduleGroup")) {
            // Missing start/end: the NOTAM's overall times (see parse()).
            String sS = text(g, "dateEffective");
            long s = sS == null ? eff : time(sS);
            String eS = text(g, "dateExpire");
            long e = eS == null ? exp : time(eS);
            if (s < 0 || e < 0 || e <= s) {
                uncertain = true;
                continue;
            }
            boolean separate = "TRUE".equals(up(text(g, "isTimeSeparate")));
            if (!separate) {
                periods.add(new Schedule.Period(s, e));
                continue;
            }
            int ws = minuteOfDay(text(g, "startTime"));
            int we = minuteOfDay(text(g, "endTime"));
            if (ws < 0 || we < 0) {
                uncertain = true;
                periods.add(new Schedule.Period(s, e));
                continue;
            }
            if (ws == we) // 24 h window: simply continuous
                periods.add(new Schedule.Period(s, e));
            else
                periods.add(new Schedule.Period(s, e, true, ws, we, days));
        }
        if (periods.isEmpty()) {
            // No (usable) per-area schedule: the NOTAM's overall times.
            periods.add(new Schedule.Period(eff, exp));
        }
        if (uncertain)
            warnings.add("Schedule of " + label
                    + " partly unreadable; shown as active for its whole period. "
                    + "Check the NOTAM text.");
        return new Schedule(periods, uncertain);
    }

    private static final Pattern TIME = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?Z?$");

    /** "2026-11-02T14:15:00" (UTC) to epoch ms; -1 if unreadable. */
    public static long time(String s) {
        if (s == null)
            return -1;
        Matcher m = TIME.matcher(s.trim());
        if (!m.matches())
            return -1;
        int y = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)),
                d = Integer.parseInt(m.group(3)), h = Integer.parseInt(m.group(4)),
                mi = Integer.parseInt(m.group(5));
        int sec = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
        if (y < 1990 || y > 2200 || mo < 1 || mo > 12 || d < 1
                || d > daysInMonth(y, mo) || h > 23 || mi > 59 || sec > 59)
            return -1;
        return ((daysFromCivil(y, mo, d) * 24 + h) * 60 + mi) * 60000L + sec * 1000L;
    }

    /** Minute of day from a date-time's time part; -1 if unreadable. */
    static int minuteOfDay(String s) {
        if (s == null)
            return -1;
        Matcher m = TIME.matcher(s.trim());
        if (!m.matches())
            return -1;
        int h = Integer.parseInt(m.group(4)), mi = Integer.parseInt(m.group(5));
        if (h > 23 || mi > 59)
            return -1;
        return h * 60 + mi;
    }

    private static int daysInMonth(int y, int m) {
        switch (m) {
            case 2:
                return (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    /** Days since 1970-01-01 (H. Hinnant's algorithm). */
    static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    // ---- DOM helpers --------------------------------------------------

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US);
    }

    private static List<Schedule.Period> listOf(Schedule.Period p) {
        List<Schedule.Period> l = new ArrayList<>();
        l.add(p);
        return l;
    }

    /** Direct children with a tag name. */
    static List<Element> children(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && tag.equals(((Element) n).getTagName()))
                out.add((Element) n);
        return out;
    }

    static Element child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && tag.equals(((Element) n).getTagName()))
                return (Element) n;
        return null;
    }

    /** Text of a direct-child path, or null. */
    static String text(Element e, String... path) {
        Element cur = e;
        for (String p : path) {
            cur = child(cur, p);
            if (cur == null)
                return null;
        }
        String t = cur.getTextContent();
        return t == null ? null : t;
    }

    /** All descendants with a tag name, in document order. */
    static List<Element> all(Element e, String tag) {
        NodeList nl = e.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++)
            out.add((Element) nl.item(i));
        return out;
    }

    static Element first(Element e, String tag) {
        NodeList nl = e.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }
}

package com.atakmap.android.tfr.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class CoreUnitTest {

    // ---- coordinates ----

    @Test
    public void coords() {
        assertEquals(43.31723864, Coords.lat("43.31723864N"), 0);
        assertEquals(-75.40694444, Coords.lon("075.40694444W"), 0);
        assertEquals(-13.5, Coords.lat("13.5S"), 0);
        assertEquals(145.1, Coords.lon("145.1E"), 0);
        assertTrue(Double.isNaN(Coords.lat("91N")));
        assertTrue(Double.isNaN(Coords.lat("43.3W")));
        assertTrue(Double.isNaN(Coords.lon("181E")));
        assertTrue(Double.isNaN(Coords.lon("")));
        assertTrue(Double.isNaN(Coords.lon(null)));
        assertTrue(Double.isNaN(Coords.lon("-75.4W")));
        assertTrue(Double.isNaN(Coords.lat("431402N")));
        assertNull(Coords.point("43N", "bad"));
    }

    // ---- altitudes ----

    @Test
    public void altitudes() {
        assertEquals("SFC", Altitude.fromXml("HEI", "0", "FT").format());
        assertEquals("4,999 ft AGL", Altitude.fromXml("HEI", "4999", "FT").format());
        assertEquals("2,500 ft MSL", Altitude.fromXml("ALT", "2500", "FT").format());
        assertEquals("FL430", Altitude.fromXml("ALT", "430", "FL").format());
        assertEquals("FL180", Altitude.fromXml("HEI", "180", "FL").format());
        assertEquals("FL050", Altitude.fromXml("ALT", "50", "FL").format());
        assertEquals(Altitude.Ref.UNKNOWN, Altitude.fromXml("XYZ", "100", "FT").ref);
        assertEquals(Altitude.Ref.UNKNOWN, Altitude.fromXml("ALT", "x", "FT").ref);
        assertEquals(Altitude.Ref.UNKNOWN, Altitude.fromXml("ALT", "100", "M").ref);
        assertEquals(43000, Altitude.fromXml("ALT", "430", "FL").extrusionFeet(), 0);
    }

    // ---- times ----

    @Test
    public void times() {
        assertEquals(-1, TfrXmlParser.time("1970-01-01T00:00:00")); // before 1990: rejected
        // 2026-11-02T14:15:00Z = 1793628900000 (checked with `date -u -d`)
        assertEquals(1793628900000L, TfrXmlParser.time("2026-11-02T14:15:00"));
        assertEquals(1793628900000L, TfrXmlParser.time("2026-11-02T14:15:00Z"));
        assertEquals(1793628900000L, TfrXmlParser.time("2026-11-02T14:15"));
        assertEquals(-1, TfrXmlParser.time("2026-02-30T00:00:00"));
        assertEquals(-1, TfrXmlParser.time("2026-11-02 14:15:00"));
        assertEquals(-1, TfrXmlParser.time("garbage"));
        assertEquals(855, TfrXmlParser.minuteOfDay("2026-08-29T14:15:00"));
    }

    @Test
    public void weekdays() {
        // 2026-11-03 is a Tuesday.
        long day = TfrXmlParser.daysFromCivil(2026, 11, 3);
        assertEquals(Schedule.Day.TUESDAY, Schedule.Day.ofEpochDay(day));
        assertEquals(Schedule.Day.THURSDAY, Schedule.Day.ofEpochDay(0));
        assertEquals(Schedule.Day.TUESDAY, Schedule.Day.parse("Tuesday"));
        assertEquals(Schedule.Day.SATURDAY, Schedule.Day.parse("SAT"));
        assertNull(Schedule.Day.parse("Daily"));
        assertNull(Schedule.Day.parse("Tu"));
    }

    // ---- schedules ----

    private static long t(String s) {
        return Fixtures.utc(s);
    }

    @Test
    public void dailyWindowSameDay() {
        Schedule s = new Schedule(list(new Schedule.Period(t("2026-11-06T17:30"),
                t("2026-11-08T22:00"), true, 17 * 60 + 30, 22 * 60,
                EnumSet.noneOf(Schedule.Day.class))), false);
        assertEquals(Schedule.State.PENDING, s.stateAt(t("2026-11-06T17:29")));
        assertEquals(Schedule.State.ACTIVE, s.stateAt(t("2026-11-06T17:30")));
        assertEquals(Schedule.State.BETWEEN, s.stateAt(t("2026-11-06T22:00")));
        assertEquals(t("2026-11-07T17:30"), s.nextChange(t("2026-11-07T03:00")));
        assertEquals(t("2026-11-07T22:00"), s.nextChange(t("2026-11-07T18:00")));
        assertEquals(Schedule.State.EXPIRED, s.stateAt(t("2026-11-08T22:00")));
        assertEquals(-1, s.nextChange(t("2026-11-08T22:00")));
    }

    @Test
    public void uncertainScheduleIsActiveWholePeriod() {
        Schedule s = new Schedule(list(new Schedule.Period(t("2026-11-06T17:30"),
                t("2026-11-08T22:00"), true, 17 * 60 + 30, 22 * 60,
                EnumSet.noneOf(Schedule.Day.class))), true);
        assertEquals(Schedule.State.ACTIVE, s.stateAt(t("2026-11-07T03:00")));
        assertEquals(Schedule.State.PENDING, s.stateAt(t("2026-11-06T00:00")));
    }

    @Test
    public void noExpiry() {
        Schedule s = new Schedule(list(new Schedule.Period(t("2020-01-15T00:01"),
                Schedule.FOREVER)), false);
        assertEquals(Schedule.State.ACTIVE, s.stateAt(t("2026-11-02T00:00")));
        assertEquals(-1, s.nextChange(t("2026-11-02T00:00")));
    }

    @Test
    public void backToBackPeriodsChain() {
        Schedule s = new Schedule(list(
                new Schedule.Period(t("2026-11-02T10:00"), t("2026-11-02T12:00")),
                new Schedule.Period(t("2026-11-02T12:00"), t("2026-11-02T14:00"))), false);
        assertEquals(t("2026-11-02T14:00"), s.nextChange(t("2026-11-02T11:00")));
    }

    private static List<Schedule.Period> list(Schedule.Period... ps) {
        List<Schedule.Period> l = new ArrayList<>();
        for (Schedule.Period p : ps)
            l.add(p);
        return l;
    }

    // ---- geometry ----

    @Test
    public void circleIsTrueToRadius() throws Exception {
        LatLon c = new LatLon(38.9, -77.0);
        List<Boundary.Vertex> def = new ArrayList<>();
        def.add(new Boundary.Vertex("CIR", c, null, 30 * Geo.METERS_PER_NM));
        List<LatLon> ring = Boundary.build(def);
        for (LatLon p : ring)
            assertEquals(30 * Geo.METERS_PER_NM, Geo.distance(c, p), 0.01);
        // Chord sag stays under the limit: midpoints of edges.
        for (int i = 0; i < ring.size(); i++) {
            LatLon a = ring.get(i), b = ring.get((i + 1) % ring.size());
            LatLon mid = Geo.destination(a, Geo.azimuth(a, b), Geo.distance(a, b) / 2);
            assertTrue(30 * Geo.METERS_PER_NM - Geo.distance(c, mid) < Boundary.MAX_SAG_M + 0.1);
        }
    }

    @Test
    public void arcDirection() throws Exception {
        // Square-ish shape with an arc bulging east (clockwise from north to south).
        LatLon c = new LatLon(30, -81);
        double r = 5 * Geo.METERS_PER_NM;
        LatLon north = Geo.destination(c, 0, r), south = Geo.destination(c, 180, r);
        LatLon west = Geo.destination(c, 270, r);
        List<Boundary.Vertex> cw = new ArrayList<>();
        cw.add(new Boundary.Vertex("GRC", north, null, Double.NaN));
        cw.add(new Boundary.Vertex("CWA", null, c, r));
        cw.add(new Boundary.Vertex("GRC", south, null, Double.NaN));
        cw.add(new Boundary.Vertex("GRC", west, null, Double.NaN));
        List<LatLon> ring = Boundary.build(cw);
        assertTrue(Boundary.contains(ring, Geo.destination(c, 90, r * 0.9)));
        // Counter-clockwise from north to south bulges west instead.
        List<Boundary.Vertex> ccw = new ArrayList<>();
        ccw.add(new Boundary.Vertex("GRC", north, null, Double.NaN));
        ccw.add(new Boundary.Vertex("CCA", null, c, r));
        ccw.add(new Boundary.Vertex("GRC", south, null, Double.NaN));
        List<LatLon> ring2 = Boundary.build(ccw);
        assertFalse(Boundary.contains(ring2, Geo.destination(c, 90, r * 0.9)));
        assertTrue(Boundary.contains(ring2, Geo.destination(c, 270, r * 0.9)));
    }

    @Test
    public void badDefinitionsRejected() {
        expectFail(new ArrayList<Boundary.Vertex>());
        List<Boundary.Vertex> d = new ArrayList<>();
        d.add(new Boundary.Vertex("XYZ", new LatLon(1, 1), null, 1));
        expectFail(d);
        List<Boundary.Vertex> d2 = new ArrayList<>();
        d2.add(new Boundary.Vertex("GRC", new LatLon(1, 1), null, Double.NaN));
        d2.add(new Boundary.Vertex("GRC", new LatLon(1, 1), null, Double.NaN));
        d2.add(new Boundary.Vertex("GRC", new LatLon(1.1, 1), null, Double.NaN));
        expectFail(d2); // only two distinct points
        List<Boundary.Vertex> d3 = new ArrayList<>();
        d3.add(new Boundary.Vertex("CIR", new LatLon(1, 1), null, -5));
        expectFail(d3);
        List<Boundary.Vertex> d4 = new ArrayList<>();
        d4.add(new Boundary.Vertex("GRC", new LatLon(1, 1), null, Double.NaN));
        d4.add(new Boundary.Vertex("CWA", null, new LatLon(1, 1.1), 1000));
        d4.add(new Boundary.Vertex("CWA", null, new LatLon(1, 1.1), 1000));
        expectFail(d4); // arc not followed by a point
    }

    private static void expectFail(List<Boundary.Vertex> def) {
        try {
            Boundary.build(def);
            fail("should have failed");
        } catch (Boundary.GeometryException expected) {
        }
    }

    @Test
    public void longEdgesDensified() {
        List<LatLon> ring = new ArrayList<>();
        ring.add(new LatLon(32, -117));
        ring.add(new LatLon(32, -114));
        ring.add(new LatLon(33, -114));
        List<LatLon> d = Boundary.densifyEdges(ring);
        for (int i = 0; i < d.size(); i++)
            assertTrue(Geo.distance(d.get(i), d.get((i + 1) % d.size())) <= Boundary.MAX_EDGE_M + 1);
    }

    @Test
    public void notamIds() {
        assertTrue(NotamId.isValid("6/4199"));
        assertTrue(NotamId.isValid("0/0367"));
        assertFalse(NotamId.isValid("6/4199/../x"));
        assertFalse(NotamId.isValid("../6"));
        assertFalse(NotamId.isValid("6-4199"));
        assertFalse(NotamId.isValid(null));
        assertTrue(NotamId.same("0/367", "0/0367"));
        assertEquals("6_4199", NotamId.fileKey("6/4199"));
    }

    // ---- list ----

    @Test
    public void listRejectsNonLists() {
        for (String bad : new String[] { "", "{}", "<html>error</html>", "[{",
                "[1,2,3]" }) {
            try {
                TfrListParser.parse(bad.getBytes());
                fail("accepted: " + bad);
            } catch (TfrListParser.FeedException expected) {
            }
        }
    }

    @Test
    public void listEmptyIsValid() throws Exception {
        assertEquals(0, TfrListParser.parse("[]".getBytes()).entries.size());
    }

    @Test
    public void listSkipsBadRowsAndDuplicates() throws Exception {
        String s = "[{\"notam_id\":\"6/1\",\"type\":\"VIP\"},{\"notam_id\":\"6/1\"},"
                + "{\"notam_id\":\"6/2\"},{\"notam_id\":\"../etc\"}]";
        TfrListParser.Result r = TfrListParser.parse(s.getBytes());
        assertEquals(2, r.entries.size());
        assertEquals(1, r.rejected);
        assertEquals("VIP", r.entries.get(0).type);
    }
}

package com.atakmap.android.tfr.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Every TFR active on Nov 2, 2026, parsed from the real FAA files, with
 * values checked by hand against the NOTAM text.
 */
public class FixtureGoldenTest {

    @Test
    public void allFixturesParse() throws Exception {
        TfrListParser.Result list = TfrListParser.parse(Fixtures.list());
        assertEquals(54, list.entries.size());
        assertEquals(0, list.rejected);
        int areas = 0, drawn = 0, checked = 0, none = 0;
        Map<Category, Integer> cats = new HashMap<>();
        for (TfrListParser.Entry e : list.entries) {
            Tfr t = TfrXmlParser.parse(Fixtures.detail(e.id), e.id);
            assertTrue(e.id, NotamId.same(e.id, t.id));
            assertFalse(e.id + " has text", t.text.isEmpty());
            assertTrue(e.id + " warnings " + t.warnings, t.warnings.isEmpty());
            Integer c = cats.get(t.category);
            cats.put(t.category, c == null ? 1 : c + 1);
            // The list's type word and the cited regulation agree.
            assertEquals(e.id, Category.of(null, e.type), t.category);
            for (TfrArea a : t.areas) {
                areas++;
                if (a.hasOutline()) {
                    drawn++;
                    assertTrue(a.outline.size() >= 3);
                    for (LatLon p : a.outline)
                        assertTrue(p.isValid());
                }
                if (a.source == TfrArea.Source.DEFINITION_CHECKED)
                    checked++;
                if (a.source == TfrArea.Source.NONE) {
                    none++;
                    assertTrue(a.geometryNote.contains("Read the NOTAM text"));
                }
            }
        }
        assertEquals(65, areas);
        assertEquals(50, drawn);
        // Every drawn area: our outline confirmed against the FAA's.
        assertEquals(50, checked);
        assertEquals(15, none);
        assertEquals(Integer.valueOf(36), cats.get(Category.SECURITY));
        assertEquals(Integer.valueOf(3), cats.get(Category.VIP));
        assertEquals(Integer.valueOf(6), cats.get(Category.HAZARD));
        assertEquals(Integer.valueOf(5), cats.get(Category.SPORTS));
        assertEquals(Integer.valueOf(3), cats.get(Category.UAS));
        assertEquals(Integer.valueOf(1), cats.get(Category.SPECIAL));
    }

    /** VIP, Rome NY: 5 NM circle SFC-4999 AGL, 0915-1315 EST = 1415-1815Z. */
    @Test
    public void vipRomeNy() {
        Tfr t = Fixtures.tfr("6/4199");
        assertEquals("6/4199", t.id);
        assertEquals(Category.VIP, t.category);
        assertEquals("91.141", t.cfr);
        assertEquals("ZBW", t.facility);
        assertEquals("Rome, NEW YORK", t.place());
        assertEquals(Fixtures.utc("2026-11-02T14:15"), t.effectiveMs);
        assertEquals(Fixtures.utc("2026-11-02T18:15"), t.expireMs);
        assertEquals(1, t.areas.size());
        TfrArea a = t.areas.get(0);
        assertEquals("SFC–4,999 ft AGL", Altitude.range(a.floor, a.ceiling));
        assertEquals(TfrArea.Source.DEFINITION_CHECKED, a.source);
        // Circle: every vertex 5 NM from the centre within 1 m.
        // centre per text: 431402N0752425W
        LatLon centre = new LatLon(43 + 14 / 60.0 + 2 / 3600.0,
                -(75 + 24 / 60.0 + 25 / 3600.0));
        for (LatLon p : a.outline)
            assertEquals(5 * Geo.METERS_PER_NM, Geo.distance(centre, p), 25);
        assertEquals(Schedule.State.PENDING,
                t.stateAt(Fixtures.utc("2026-11-02T14:14")));
        assertEquals(Schedule.State.ACTIVE,
                t.stateAt(Fixtures.utc("2026-11-02T14:15")));
        assertEquals(Schedule.State.ACTIVE,
                t.stateAt(Fixtures.utc("2026-11-02T18:14")));
        assertEquals(Schedule.State.EXPIRED,
                t.stateAt(Fixtures.utc("2026-11-02T18:15")));
        assertTrue(Boundary.contains(a.outline, centre));
        assertFalse(Boundary.contains(a.outline,
                Geo.destination(centre, 45, 5.1 * Geo.METERS_PER_NM)));
    }

    /** Mayport FL security: 4 areas, DLY 1200-0600Z, Nov 2 1200Z to Nov 7 0600Z. */
    @Test
    public void mayportDailyWindowAcrossMidnight() {
        Tfr t = Fixtures.tfr("6/4313");
        assertEquals(Category.SECURITY, t.category);
        assertEquals(4, t.areas.size());
        assertEquals("SFC–2,500 ft MSL",
                Altitude.range(t.areas.get(0).floor, t.areas.get(0).ceiling));
        assertEquals("1,700 ft MSL–14,000 ft MSL",
                Altitude.range(t.areas.get(1).floor, t.areas.get(1).ceiling));
        assertEquals(Schedule.State.PENDING, t.stateAt(Fixtures.utc("2026-11-02T11:59")));
        assertEquals(Schedule.State.ACTIVE, t.stateAt(Fixtures.utc("2026-11-02T12:00")));
        assertEquals(Schedule.State.ACTIVE, t.stateAt(Fixtures.utc("2026-11-03T05:59")));
        assertEquals(Schedule.State.BETWEEN, t.stateAt(Fixtures.utc("2026-11-03T06:00")));
        assertEquals(Fixtures.utc("2026-11-03T12:00"),
                t.nextChange(Fixtures.utc("2026-11-03T06:00")));
        assertEquals(Schedule.State.ACTIVE, t.stateAt(Fixtures.utc("2026-11-06T23:00")));
        assertEquals(Schedule.State.EXPIRED, t.stateAt(Fixtures.utc("2026-11-07T06:00")));
        assertEquals(Fixtures.utc("2026-11-03T06:00"),
                t.nextChange(Fixtures.utc("2026-11-02T20:00")));
    }

    /** Alaska (6/2481) Area E: TUE, THU, SAT 1100-1300Z only. */
    @Test
    public void dayOfWeekWindows() {
        Tfr t = Fixtures.tfr("6/2481");
        TfrArea e = null;
        for (TfrArea a : t.areas)
            if (a.name.equals("Area E"))
                e = a;
        assertTrue(e != null);
        assertEquals("1,000 ft AGL–2,600 ft AGL",
                Altitude.range(e.floor, e.ceiling));
        assertFalse(e.schedule.uncertain);
        // Nov 3, 2026 is a Tuesday; Nov 4 a Wednesday.
        assertEquals(Schedule.State.ACTIVE, e.schedule.stateAt(Fixtures.utc("2026-11-03T11:00")));
        assertEquals(Schedule.State.BETWEEN, e.schedule.stateAt(Fixtures.utc("2026-11-03T13:00")));
        assertEquals(Schedule.State.BETWEEN, e.schedule.stateAt(Fixtures.utc("2026-11-04T12:00")));
        assertEquals(Fixtures.utc("2026-11-05T11:00"),
                e.schedule.nextChange(Fixtures.utc("2026-11-04T12:00")));
        // Area D FL320-FL430, continuous.
        TfrArea d = null;
        for (TfrArea a : t.areas)
            if (a.name.equals("D FL320"))
                d = a;
        assertEquals("FL320–FL430", Altitude.range(d.floor, d.ceiling));
        assertEquals(Schedule.State.ACTIVE, d.schedule.stateAt(Fixtures.utc("2026-11-04T12:00")));
    }

    /** Pensacola air show: three separate periods. */
    @Test
    public void multiplePeriods() {
        Tfr t = Fixtures.tfr("6/4028");
        assertEquals(Category.SPORTS, t.category);
        TfrArea a = t.areas.get(0);
        assertEquals("SFC–15,000 ft MSL", Altitude.range(a.floor, a.ceiling));
        assertEquals(3, a.schedule.periods.size());
        assertEquals(Schedule.State.ACTIVE, t.stateAt(Fixtures.utc("2026-11-05T15:00")));
        assertEquals(Schedule.State.BETWEEN, t.stateAt(Fixtures.utc("2026-11-05T22:00")));
        assertEquals(Fixtures.utc("2026-11-06T14:30"), t.nextChange(Fixtures.utc("2026-11-05T22:00")));
    }

    /** Nationwide SSI with no graphic: kept, text-only, still in effect. */
    @Test
    public void textOnlySecurityNotice() {
        Tfr t = Fixtures.tfr("0/0367");
        assertEquals(Category.SECURITY, t.category);
        assertFalse(t.hasOutline());
        assertEquals(Schedule.FOREVER, t.expireMs);
        assertEquals(Schedule.State.ACTIVE, t.stateAt(Fixtures.utc("2026-11-02T12:00")));
        assertTrue(t.text.startsWith("!FDC 0/0367"));
    }

    /** Miami security TFR whose XML has no start time (in effect since Sep 28 per text). */
    @Test
    public void missingStartTimeMeansInEffect() {
        Tfr t = Fixtures.tfr("6/5945");
        assertTrue(t.warnings.isEmpty());
        assertEquals(Fixtures.utc("2027-04-01T03:59"), t.expireMs);
        assertEquals(Schedule.State.ACTIVE, t.stateAt(Fixtures.utc("2026-11-02T12:00")));
        assertEquals(Schedule.State.EXPIRED, t.stateAt(Fixtures.utc("2027-04-01T03:59")));
        assertFalse(t.areas.get(0).schedule.uncertain);
    }

    @Test(expected = TfrXmlParser.ParseException.class)
    public void wrongFileForId() throws Exception {
        TfrXmlParser.parse(Fixtures.detail("6/4199"), "6/4313");
    }

    @Test(expected = TfrXmlParser.ParseException.class)
    public void doctypeRefused() throws Exception {
        byte[] x = ("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<XNOTAM-Update>&e;</XNOTAM-Update>").getBytes("UTF-8");
        TfrXmlParser.parse(x, "6/4199");
    }

    @Test(expected = TfrXmlParser.ParseException.class)
    public void htmlErrorPageRefused() throws Exception {
        TfrXmlParser.parse("<html><body>Not Found</body></html>".getBytes("UTF-8"), "6/4199");
    }

    @Test(expected = TfrXmlParser.ParseException.class)
    public void truncatedFileRefused() throws Exception {
        byte[] full = Fixtures.detail("6/4199");
        byte[] half = java.util.Arrays.copyOf(full, full.length / 2);
        TfrXmlParser.parse(half, "6/4199");
    }
}

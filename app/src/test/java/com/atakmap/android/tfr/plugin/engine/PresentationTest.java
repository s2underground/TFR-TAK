package com.atakmap.android.tfr.plugin.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.tfr.plugin.core.Category;
import com.atakmap.android.tfr.plugin.core.Fixtures;
import com.atakmap.android.tfr.plugin.core.LatLon;
import com.atakmap.android.tfr.plugin.core.Tfr;
import com.atakmap.android.tfr.plugin.core.TfrListParser;
import com.atakmap.android.tfr.plugin.ui.Format;
import com.atakmap.android.tfr.plugin.ui.MapPlan;
import com.atakmap.android.tfr.plugin.ui.Rows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** Map plan and pane rows built from the real fixture set. */
public class PresentationTest {

    static final TimeZone NY = TimeZone.getTimeZone("America/New_York");
    static final Format F = new Format(NY);

    static Snapshot snapshot(String at, String... cancelled) throws Exception {
        List<String> gone = new ArrayList<>();
        Collections.addAll(gone, cancelled);
        List<Snapshot.Item> items = new ArrayList<>();
        for (TfrListParser.Entry e : TfrListParser.parse(Fixtures.list()).entries)
            items.add(new Snapshot.Item(e, Fixtures.tfr(e.id), null,
                    gone.contains(e.id), 0));
        long now = Fixtures.utc(at);
        return new Snapshot(items, now, now - 5 * 60000, now - 5 * 60000,
                null, false, new ArrayList<String>());
    }

    static MapPlan.Shape shape(List<MapPlan.Shape> plan, String id) {
        for (MapPlan.Shape s : plan)
            if (s.tfrId.equals(id))
                return s;
        return null;
    }

    @Test
    public void mapPlanStyles() throws Exception {
        List<MapPlan.Shape> before = MapPlan.build(snapshot("2026-11-02T12:30"), false);
        MapPlan.Shape vip = shape(before, "6/4199");
        assertNotNull(vip);
        assertEquals(MapPlan.Line.DASHED, vip.line);
        assertEquals(0x1A, vip.fill >>> 24);
        assertTrue(vip.label.contains("not yet active"));
        assertEquals(4999 * 0.3048, vip.heightM, 0.01);
        assertEquals(Category.VIP.color, vip.stroke);

        List<MapPlan.Shape> during = MapPlan.build(snapshot("2026-11-02T15:00"), false);
        vip = shape(during, "6/4199");
        assertEquals(MapPlan.Line.SOLID, vip.line);
        assertEquals(0x50, vip.fill >>> 24);
        assertTrue(vip.label, vip.label.equals("VIP · SFC–4,999 ft AGL · ACTIVE"));

        List<MapPlan.Shape> after = MapPlan.build(snapshot("2026-11-02T18:15"), false);
        assertNull(shape(after, "6/4199")); // ended: not drawn

        for (MapPlan.Shape s : before) {
            assertEquals(s.tfrId, MapPlan.idOf(s.uid));
            assertTrue(s.outline.size() >= 3);
        }
        assertNull(MapPlan.idOf("someone-else"));
        assertNull(MapPlan.idOf("tfrtak-../x-1"));
    }

    /** Device test 0.3: labels must show at zooms where the TFR is visible. */
    @Test
    public void labelThresholdScalesWithAreaSize() throws Exception {
        List<MapPlan.Shape> plan = MapPlan.build(snapshot("2026-11-02T15:00"), false);
        MapPlan.Shape vip = shape(plan, "6/4199"); // 5 NM circle, ~13 km bbox diagonal
        double ext = com.atakmap.android.tfr.plugin.core.Boundary.extent(vip.outline);
        assertEquals(ext / MapPlan.LABEL_MIN_PIXELS, vip.labelMaxResM, 1e-6);
        assertTrue(vip.labelMaxResM > 50); // far coarser than ATAK's 10 m/px default
        for (MapPlan.Shape s : plan)
            assertTrue(s.uid, s.labelMaxResM >= 10.0);
        // Part of the signature, so a change reaches the map.
        assertTrue(vip.signature().endsWith("|" + vip.labelMaxResM));
    }

    @Test
    public void mapPlanFiltersAndCancelled() throws Exception {
        Snapshot s = snapshot("2026-11-02T15:00", "6/4199");
        List<MapPlan.Shape> all = MapPlan.build(s, false);
        List<MapPlan.Shape> sec = MapPlan.build(s, true);
        assertTrue(sec.size() < all.size());
        for (MapPlan.Shape x : sec) {
            Tfr t = s.find(x.tfrId).tfr;
            assertTrue(t.category.isSecurity());
        }
        MapPlan.Shape vip = shape(all, "6/4199");
        assertEquals(MapPlan.Line.DOTTED, vip.line);
        assertTrue(vip.label.contains("possibly cancelled"));
    }

    @Test
    public void rowsSortedActiveFirstThenByDistance() throws Exception {
        Snapshot s = snapshot("2026-11-02T15:00");
        LatLon rome = new LatLon(43.21, -75.45); // inside the Rome VIP TFR
        List<Rows.Row> rows = Rows.build(s, new Rows.Filter(false, false), rome, F);
        Rows.Row first = rows.get(0);
        assertEquals("6/4199", first.id);
        assertEquals("inside", first.distance);
        assertEquals("ACTIVE until Nov 2 1815Z (13:15 EST)", first.status);
        assertEquals("VIP · Rome, NEW YORK", first.title);
        assertEquals("SFC–4,999 ft AGL · FDC 6/4199", first.subtitle);
        // Active rows come before pending ones.
        boolean seenNonActive = false;
        for (Rows.Row r : rows) {
            boolean active = r.status.startsWith("ACTIVE");
            if (!active)
                seenNonActive = true;
            else
                assertFalse("active after non-active: " + r.id, seenNonActive);
        }
    }

    @Test
    public void pendingRowWording() throws Exception {
        Snapshot s = snapshot("2026-11-02T12:30");
        List<Rows.Row> rows = Rows.build(s, new Rows.Filter(true, false), null, F);
        Rows.Row vip = null;
        for (Rows.Row r : rows)
            if (r.id.equals("6/4199"))
                vip = r;
        assertEquals("Starts Nov 2 1415Z (09:15 EST), in 1 h 45 min", vip.status);
        assertEquals("", vip.distance);
        for (Rows.Row r : rows)
            assertTrue(r.category.isSecurity());
    }

    @Test
    public void textOnlyAndEndedRows() throws Exception {
        Snapshot s = snapshot("2026-11-02T19:00");
        List<Rows.Row> rows = Rows.build(s, new Rows.Filter(false, false), null, F);
        Rows.Row nat = null;
        for (Rows.Row r : rows) {
            assertFalse(r.id.equals("6/4199")); // ended, hidden by default
            if (r.id.equals("0/0367"))
                nat = r;
        }
        assertFalse(nat.hasOutline);
        assertTrue(nat.notes.get(0).contains("No map graphic"));
        List<Rows.Row> withEnded = Rows.build(s, new Rows.Filter(false, true), null, F);
        Rows.Row ended = null;
        for (Rows.Row r : withEnded)
            if (r.id.equals("6/4199"))
                ended = r;
        assertEquals("Ended (per published times)", ended.status);
        // Ended rows sort after everything else.
        boolean seenEnded = false;
        for (Rows.Row r : withEnded) {
            boolean isEnded = r.status.startsWith("Ended");
            if (isEnded)
                seenEnded = true;
            else
                assertFalse("row after an ended row: " + r.id, seenEnded);
        }
    }

    @Test
    public void sectionsSplitActiveUpcomingTextOnly() throws Exception {
        // 12:30Z: the Rome VIP (1415Z) and Mayport (1200Z, already on) etc.
        Snapshot s = snapshot("2026-11-02T12:30");
        List<Rows.Row> rows = Rows.build(s, new Rows.Filter(false, false), null, F);
        List<Rows.Section> secs = Rows.sections(rows);
        assertEquals(Rows.Box.ACTIVE, secs.get(0).box);
        assertEquals(Rows.Box.UPCOMING, secs.get(1).box);
        assertEquals(Rows.Box.TEXT_ONLY, secs.get(2).box);
        assertEquals(3, secs.size()); // no Ended box when ended are hidden
        int total = 0;
        for (Rows.Section sec : secs) {
            total += sec.rows.size();
            for (Rows.Row r : sec.rows) {
                if (sec.box == Rows.Box.TEXT_ONLY)
                    assertFalse(r.id, r.hasOutline);
                else
                    assertTrue(r.id, r.hasOutline);
                if (sec.box == Rows.Box.ACTIVE)
                    assertTrue(r.id + " " + r.status, r.status.startsWith("ACTIVE"));
                if (sec.box == Rows.Box.UPCOMING)
                    assertFalse(r.id + " " + r.status, r.status.startsWith("ACTIVE"));
            }
        }
        assertEquals(rows.size(), total);
        assertEquals(15, secs.get(2).rows.size());
        assertTrue(Rows.Box.TEXT_ONLY.collapsedByDefault);
        assertFalse(Rows.Box.UPCOMING.collapsedByDefault);

        // Upcoming: soonest start first.
        List<Rows.Row> up = secs.get(1).rows;
        assertTrue(up.size() > 1);
        for (int i = 1; i < up.size(); i++)
            assertTrue(up.get(i - 1).nextMsForTest() <= up.get(i).nextMsForTest());
        boolean vipUpcoming = false;
        for (Rows.Row r : up)
            if (r.id.equals("6/4199"))
                vipUpcoming = true;
        assertTrue(vipUpcoming);

        // With "Show ended", an Ended box appears last.
        List<Rows.Section> withEnded = Rows.sections(Rows.build(
                snapshot("2026-11-02T19:00"), new Rows.Filter(false, true), null, F));
        assertEquals(Rows.Box.ENDED, withEnded.get(withEnded.size() - 1).box);
    }

    @Test
    public void detailTextIncludesNotamAndAreas() throws Exception {
        Snapshot s = snapshot("2026-11-02T13:00");
        String d = Rows.detail(s.find("6/4313"), s.nowMs, F);
        assertTrue(d.contains("FDC 6/4313 · Security"));
        assertTrue(d.contains("Area B: 1,700 ft MSL–14,000 ft MSL"));
        assertTrue(d.contains("ACTIVE now, until Nov 3 0600Z (01:00 EST)"));
        assertTrue(d.contains("matches the FAA graphic"));
        assertTrue(d.contains("NOTAM TEXT"));
        assertTrue(d.contains("!FDC 6/4313"));
    }

    @Test
    public void statusLines() throws Exception {
        Snapshot s = snapshot("2026-11-02T15:00");
        assertEquals("FAA data as of 1455Z (5 min ago)", Rows.statusLine(s, F));
        assertFalse(Rows.statusIsWarning(s));
        assertNull(Rows.warningLine(s));
        Snapshot stale = new Snapshot(s.items, s.nowMs, s.nowMs - 3600000,
                s.nowMs, "Can't reach tfr.faa.gov (timed out).", false,
                new ArrayList<String>());
        assertTrue(Rows.statusIsWarning(stale));
        assertTrue(Rows.warningLine(stale).startsWith("STALE"));
        assertTrue(Rows.warningLine(stale).contains("Can't reach"));
    }

    @Test
    public void formats() {
        assertEquals("45 min", Format.duration(45 * 60000));
        assertEquals("1 h 45 min", Format.duration(105 * 60000));
        assertEquals("3 d 2 h", Format.duration((74 * 60) * 60000L));
        assertEquals("inside", Format.distance(0));
        assertEquals("2.7 NM", Format.distance(5000));
        assertEquals("1,240 NM", Format.distance(1240 * 1852.0));
        assertEquals("never", Format.ago(1000, 0));
        assertEquals("just now", Format.ago(100000, 90000));
    }
}

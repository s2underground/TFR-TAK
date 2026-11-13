package com.atakmap.android.tfr.plugin.core.nas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.tfr.plugin.core.Fixtures;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FAA national airspace data captured during the Nov 13, 2026 nationwide
 * ground stop (fixtures/nas/2026-11-13).
 */
public class NasParseTest {

    static final String D = "/fixtures/nas/2026-11-13/";
    static final int DATE = 20261113;

    static List<Advisory> list(String f) {
        return Advisory.parseList(Fixtures.read(D + f), DATE);
    }

    static Advisory find(List<Advisory> l, int n) {
        for (Advisory a : l)
            if (a.number == n)
                return a;
        return null;
    }

    static Advisory.Text text(int n) {
        return Advisory.parseText(Fixtures.read(D + "adv/adv_" + n + ".html"),
                DATE);
    }

    @Test
    public void advisoryListRows() {
        List<Advisory> l = list("adv_list.html");
        assertEquals(117, l.size());
        Advisory gs = find(l, 111);
        assertEquals("DCC", gs.facility);
        assertEquals("NATIONAL GROUND STOP", gs.title);
        assertEquals(Fixtures.utc("2026-11-13T20:54"), gs.sentMs);
        // A title with line breaks loses the "ATCSCC ADVZY nnn …" prefix.
        assertTrue(find(l, 101).title, find(l, 101).title.startsWith("FCA RQD"));
        assertEquals(120, list("adv_list_2300Z.html").size());
    }

    @Test
    public void emptyAnswerIsUnknownNotNone() {
        // The server answered "NO ADVISORIES MATCH" though 120 existed.
        assertNull(list("adv_list_empty_answer.html"));
        assertNull(Advisory.parseText(Fixtures.read(D + "no_advisory.html"),
                DATE));
    }

    @Test
    public void nationalClassification() {
        List<Advisory> l = list("adv_list_2300Z.html");
        assertEquals(Advisory.Kind.GROUND_STOP, find(l, 111).nationalKind());
        assertEquals(Advisory.Kind.GROUND_STOP, find(l, 120).nationalKind());
        assertEquals(Advisory.Kind.HOLDING, find(l, 116).nationalKind());
        assertEquals(Advisory.Kind.DIVERSION, find(l, 108).nationalKind());
        // Airport ground stops, routes, volcano bulletins, ops plans: no.
        assertNull(find(l, 109).nationalKind()); // MDW CDM GROUND STOP
        assertNull(find(l, 107).nationalKind()); // ORD
        assertNull(find(l, 112).nationalKind()); // OPERATIONS PLAN
        assertNull(find(l, 118).nationalKind()); // VOLCANIC
        assertNull(find(l, 117).nationalKind()); // ALL CENTERS FP DROP TIMES
        assertNull(find(l, 113).nationalKind()); // REROUTE CANCELLATION
        int n = 0;
        for (Advisory a : l)
            if (a.nationalKind() != null)
                n++;
        assertEquals(4, n); // 108, 111, 116, 120
    }

    @Test
    public void advisoryText() {
        Advisory.Text t = text(111);
        assertNotNull(t);
        assertTrue(t.header, t.header.contains("ADVZY 111 DCC"));
        assertTrue(t.body.contains("ALL AIRPORTS ARE GROUND STOPPED"));
        assertEquals(Fixtures.utc("2026-11-13T20:53"), t.eventStartMs);
        assertEquals(Fixtures.utc("2026-11-14T04:00"), t.eventEndMs);
        Advisory.Text t120 = text(120);
        assertTrue(t120.body.contains("REASON: COMMUNICATIONS FAILURE"));
        assertEquals(Fixtures.utc("2026-11-13T22:45"), t120.eventStartMs);
        assertFalse(t120.body.contains("FAA.gov Home"));
    }

    @Test
    public void eventTimesAcrossMonthEnd() {
        assertEquals(Fixtures.utc("2026-12-01T04:00"),
                Advisory.dayTime(20261130, 1, "0400"));
        assertEquals(Fixtures.utc("2026-10-31T23:00"),
                Advisory.dayTime(20261101, 31, "2300"));
        assertEquals(0, Advisory.dayTime(20261113, 32, "0400"));
    }

    @Test
    public void nationalEventsInForce() {
        final Map<String, Advisory.Text> texts = new HashMap<>();
        List<Advisory> l = list("adv_list_2300Z.html");
        for (int n : new int[] { 108, 111, 116, 120 })
            texts.put(find(l, n).key(), text(n));
        National.Texts tx = new National.Texts() {
            @Override
            public Advisory.Text get(Advisory a) {
                return texts.get(a.key());
            }
        };
        List<National.Event> e = National.active(l, tx,
                Fixtures.utc("2026-11-13T23:00"));
        assertEquals(3, e.size());
        // Ground stop first; the newest (120) speaks for it, 111 counted.
        assertEquals(Advisory.Kind.GROUND_STOP, e.get(0).kind);
        assertEquals(120, e.get(0).advisory.number);
        assertEquals(1, e.get(0).earlier);
        assertEquals(Fixtures.utc("2026-11-14T04:00"), e.get(0).endMs);
        // After the planned ends: nothing.
        assertTrue(National.active(l, tx, Fixtures.utc("2026-11-14T05:31"))
                .isEmpty());
        // Without texts: in force 12 h after sending.
        National.Texts none = new National.Texts() {
            @Override
            public Advisory.Text get(Advisory a) {
                return null;
            }
        };
        assertEquals(3, National.active(l, none,
                Fixtures.utc("2026-11-14T08:00")).size());
        assertTrue(National.active(l, none,
                Fixtures.utc("2026-11-14T11:00")).isEmpty());
    }

    @Test
    public void cancellationEndsTheKind() {
        List<Advisory> l = new ArrayList<>(list("adv_list_2300Z.html"));
        l.add(new Advisory(121, DATE, "DCC", "NATIONWIDE GROUND STOP CNX",
                Fixtures.utc("2026-11-13T23:30")));
        National.Texts none = new National.Texts() {
            @Override
            public Advisory.Text get(Advisory a) {
                return null;
            }
        };
        List<National.Event> e = National.active(l, none,
                Fixtures.utc("2026-11-13T23:40"));
        National.Event gs = null;
        for (National.Event x : e)
            if (x.kind == Advisory.Kind.GROUND_STOP)
                gs = x;
        assertNotNull(gs);
        assertTrue(gs.cancelled);
        assertEquals(e.get(e.size() - 1), gs); // listed last
        // Gone two hours after the cancellation.
        for (National.Event x : National.active(l, none,
                Fixtures.utc("2026-11-14T01:31")))
            assertFalse(x.kind == Advisory.Kind.GROUND_STOP);
    }

    @Test
    public void airportEvents() throws Exception {
        List<AirportEvent> l = AirportEvent
                .parse(Fixtures.read(D + "airport-events.json"));
        Map<String, AirportEvent> by = new HashMap<>();
        for (AirportEvent e : l)
            by.put(e.airport + "/" + e.type.tag, e);
        AirportEvent atl = by.get("ATL/DEP");
        assertEquals("OTHER: NATIONAL STOP", atl.reason);
        assertEquals(33.6407, atl.lat, 1e-6);
        assertTrue(atl.what, atl.what.startsWith("Departures 46 minutes to 1 hour"));
        AirportEvent bos = by.get("BOS/GDP");
        assertEquals("airport volume", bos.reason);
        assertEquals("Avg 26 min, max 1 h 43", bos.what);
        assertEquals(Fixtures.utc("2026-11-14T02:59"), bos.endMs);
        assertTrue(bos.advisoryUrl.startsWith("https://www.fly.faa.gov/adv/"));
        AirportEvent bdl = by.get("BDL/CLSD");
        assertTrue(bdl.notam, bdl.notam.contains("CLSD TO NON SKED INTL"));
        assertEquals(Fixtures.utc("2026-11-14T05:00"), bdl.endMs);
        assertTrue(bdl.partial); // "TO NON SKED INTL AIR CARRIERS EXC …"
        assertFalse(by.get("SNA/CLSD").partial); // "DLY 0730-1415"
        // Ground stop records (Canadian airports later that evening).
        List<AirportEvent> later = AirportEvent
                .parse(Fixtures.read(D + "airport-events_2249Z.json"));
        int gs = 0;
        for (AirportEvent e : later)
            if (e.type == AirportEvent.Type.GROUND_STOP) {
                gs++;
                assertTrue(e.endMs > e.startMs);
            }
        assertEquals(2, gs);
    }

    @Test(expected = AirportEvent.ParseException.class)
    public void airportEventsRejectsHtml() throws Exception {
        AirportEvent.parse("<html>maintenance</html>".getBytes());
    }

    @Test
    public void advisoryUrlKeptOnlyForCommandCenter() {
        assertEquals("", AirportEvent.url("https://evil.example/adv"));
        assertEquals("", AirportEvent.url("http://www.fly.faa.gov/adv/x"));
    }
}

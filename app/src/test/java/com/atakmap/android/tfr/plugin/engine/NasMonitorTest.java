package com.atakmap.android.tfr.plugin.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.tfr.plugin.core.Fixtures;
import com.atakmap.android.tfr.plugin.core.nas.Advisory;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The NAS monitor against the flaky FAA advisories server. */
public class NasMonitorTest {

    static final String D = "/fixtures/nas/2026-11-13/";

    /** Answers like the real server: mostly "no advisories". */
    static final class FlakySource implements NasMonitor.Source {
        int listCalls, textCalls;
        int goodEvery = 4; // 1 in 4 list answers is real
        String listFile = "adv_list.html";
        boolean airportsDown;

        @Override
        public byte[] airportEvents() throws IOException {
            if (airportsDown)
                throw new IOException("HTTP 503");
            return Fixtures.read(D + "airport-events.json");
        }

        @Override
        public byte[] advisoryList(int date) throws IOException {
            listCalls++;
            if (date != 20261113 || listCalls % goodEvery != 0)
                return Fixtures.read(D + "adv_list_empty_answer.html");
            return Fixtures.read(D + listFile);
        }

        @Override
        public byte[] advisoryText(int date, int number) throws IOException {
            textCalls++;
            if (textCalls % 2 == 1)
                return Fixtures.read(D + "no_advisory.html");
            try {
                return Fixtures.read(D + "adv/adv_" + number + ".html");
            } catch (IllegalStateException e) {
                return Fixtures.read(D + "no_advisory.html");
            }
        }
    }

    static NasMonitor monitor(TfrEngineTest.ManualScheduler s, FlakySource src,
            final List<NasSnapshot> out) {
        NasMonitor m = new NasMonitor(s, src);
        m.setListener(new NasMonitor.Listener() {
            @Override
            public void onNas(NasSnapshot snap) {
                out.add(snap);
            }
        });
        return m;
    }

    @Test
    public void retriesThroughEmptyAnswersAndFindsTheGroundStop() {
        TfrEngineTest.ManualScheduler s = new TfrEngineTest.ManualScheduler();
        s.now = Fixtures.utc("2026-11-13T21:30");
        FlakySource src = new FlakySource();
        List<NasSnapshot> out = new ArrayList<>();
        NasMonitor m = monitor(s, src, out);
        m.start();
        s.advance(1000);
        NasSnapshot snap = out.get(out.size() - 1);
        assertTrue(snap.advisoriesOkMs > 0);
        assertNull(snap.advisoriesError);
        assertTrue(snap.severeNational());
        assertEquals(111, snap.national.get(0).advisory.number);
        assertNotNull(snap.national.get(0).text); // fetched despite flakiness
        assertTrue(snap.newNational.isEmpty()); // first load: no alert
        assertFalse(snap.airports.isEmpty());
    }

    @Test
    public void newNationalAdvisoryIsAlertedOnceAndListOnlyGrows() {
        TfrEngineTest.ManualScheduler s = new TfrEngineTest.ManualScheduler();
        s.now = Fixtures.utc("2026-11-13T22:40");
        FlakySource src = new FlakySource();
        List<NasSnapshot> out = new ArrayList<>();
        NasMonitor m = monitor(s, src, out);
        m.start();
        s.advance(1000);
        // ADVZY 120 (reissued ground stop) appears in the next list.
        src.listFile = "adv_list_2300Z.html";
        s.advance(NasMonitor.POLL_MS + 60_000);
        NasSnapshot snap = out.get(out.size() - 1);
        assertEquals(120, snap.national.get(0).advisory.number);
        boolean alerted = false;
        for (NasSnapshot x : out)
            for (com.atakmap.android.tfr.plugin.core.nas.National.Event e : x.newNational)
                if (e.advisory.number == 120)
                    alerted = true;
        assertTrue(alerted);
        // A shorter (older) answer later doesn't shrink the day's list.
        src.listFile = "adv_list.html";
        s.advance(NasMonitor.POLL_MS + 60_000);
        snap = out.get(out.size() - 1);
        assertEquals(120, snap.national.get(0).advisory.number);
        assertTrue(snap.newNational.isEmpty());
    }

    @Test
    public void serverAlwaysEmptyIsReportedNotAllClear() {
        TfrEngineTest.ManualScheduler s = new TfrEngineTest.ManualScheduler();
        s.now = Fixtures.utc("2026-11-13T21:30");
        FlakySource src = new FlakySource();
        src.goodEvery = Integer.MAX_VALUE;
        src.airportsDown = true;
        List<NasSnapshot> out = new ArrayList<>();
        NasMonitor m = monitor(s, src, out);
        m.start();
        s.advance(1000);
        NasSnapshot snap = out.get(out.size() - 1);
        assertEquals(0, snap.advisoriesOkMs);
        assertNotNull(snap.advisoriesError);
        assertNotNull(snap.airportsError);
        assertTrue(snap.stale());
        assertEquals(NasMonitor.LIST_TRIES * 2, src.listCalls); // today + yesterday
    }

    @Test
    public void stopHaltsPolling() {
        TfrEngineTest.ManualScheduler s = new TfrEngineTest.ManualScheduler();
        s.now = Fixtures.utc("2026-11-13T21:30");
        FlakySource src = new FlakySource();
        List<NasSnapshot> out = new ArrayList<>();
        NasMonitor m = monitor(s, src, out);
        m.start();
        s.advance(1000);
        int n = out.size();
        m.stop();
        s.advance(3 * NasMonitor.POLL_MS);
        assertEquals(n, out.size());
        assertEquals(20261113, Advisory.dateOf(s.now));
    }

    @Test
    public void manualRefreshDoesNotDoublePolling() {
        TfrEngineTest.ManualScheduler s = new TfrEngineTest.ManualScheduler();
        s.now = Fixtures.utc("2026-11-13T21:30");
        FlakySource src = new FlakySource();
        src.goodEvery = 1;
        List<NasSnapshot> out = new ArrayList<>();
        NasMonitor m = monitor(s, src, out);
        m.start();
        s.advance(1000);
        m.refreshNow(); // within 30 s: ignored
        s.advance(1000);
        int afterStart = out.size();
        s.advance(60_000);
        m.refreshNow(); // starts a new chain; the old one dies
        s.advance(1000);
        int afterRefresh = out.size();
        assertEquals(afterStart + 1, afterRefresh);
        s.advance(3 * NasMonitor.POLL_MS + 1000);
        // One chain: about one poll per POLL_MS, not two.
        assertTrue(out.size() - afterRefresh <= 3);
    }
}

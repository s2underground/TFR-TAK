package com.atakmap.android.tfr.plugin.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.tfr.plugin.core.Fixtures;
import com.atakmap.android.tfr.plugin.net.FaaClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

public class TfrEngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Virtual-time single-thread scheduler. */
    static final class ManualScheduler implements Scheduler {
        long now = Fixtures.utc("2026-11-02T12:30");
        long seq;
        boolean shut;

        final class Task implements Comparable<Task> {
            final long at, order;
            final Runnable r;

            Task(long at, Runnable r) {
                this.at = at;
                this.order = seq++;
                this.r = r;
            }

            @Override
            public int compareTo(Task o) {
                return at != o.at ? Long.compare(at, o.at) : Long.compare(order, o.order);
            }
        }

        final PriorityQueue<Task> q = new PriorityQueue<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public void execute(Runnable r) {
            schedule(r, 0);
        }

        @Override
        public void schedule(Runnable r, long delayMs) {
            if (!shut)
                q.add(new Task(now + Math.max(0, delayMs), r));
        }

        @Override
        public void pause(long ms) {
            now += ms;
        }

        @Override
        public void shutdown() {
            shut = true;
            q.clear();
        }

        /** Run everything due up to now + ms. */
        void advance(long ms) {
            long end = now + ms;
            while (!q.isEmpty() && q.peek().at <= end) {
                Task t = q.poll();
                now = Math.max(now, t.at);
                t.r.run();
            }
            now = Math.max(now, end);
        }
    }

    /** Serves the fixture set; can drop ids, fail, or serve garbage. */
    static class FakeFaa implements FaaClient {
        byte[] list = Fixtures.list();
        boolean down;
        int listCalls, detailCalls;
        final Set<String> brokenDetails = new HashSet<>();

        @Override
        public byte[] fetchList() throws IOException {
            listCalls++;
            if (down)
                throw new java.net.UnknownHostException("tfr.faa.gov");
            return list;
        }

        @Override
        public byte[] fetchDetail(String id) throws IOException {
            detailCalls++;
            if (down)
                throw new java.net.SocketTimeoutException();
            if (brokenDetails.contains(id))
                return "<html>maintenance</html>".getBytes(StandardCharsets.UTF_8);
            return Fixtures.detail(id);
        }

        /** The fixture list without some ids. */
        void without(String... ids) throws Exception {
            Set<String> drop = new HashSet<>();
            for (String i : ids)
                drop.add(i);
            JSONArray in = new JSONArray(new String(Fixtures.list(), StandardCharsets.UTF_8));
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++)
                if (!drop.contains(in.getJSONObject(i).getString("notam_id")))
                    out.put(in.getJSONObject(i));
            list = out.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** The first n rows of the fixture list. */
        void firstRows(int n) throws Exception {
            JSONArray in = new JSONArray(new String(Fixtures.list(), StandardCharsets.UTF_8));
            JSONArray out = new JSONArray();
            for (int i = 0; i < n; i++)
                out.put(in.getJSONObject(i));
            list = out.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    ManualScheduler sched;
    FakeFaa faa;
    File dir;
    final List<Snapshot> snaps = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        sched = new ManualScheduler();
        faa = new FakeFaa();
        dir = tmp.newFolder("cache");
    }

    TfrEngine engine() {
        TfrEngine e = new TfrEngine(sched, faa, dir);
        e.setListener(new TfrEngine.Listener() {
            @Override
            public void onSnapshot(Snapshot s) {
                snaps.add(s);
            }
        });
        return e;
    }

    Snapshot last() {
        return snaps.get(snaps.size() - 1);
    }

    static final long MIN = 60000L;

    int withDetail(Snapshot s) {
        int n = 0;
        for (Snapshot.Item i : s.items)
            if (i.tfr != null)
                n++;
        return n;
    }

    @Test
    public void firstLoadReadsEverything() {
        TfrEngine e = engine();
        e.start();
        sched.advance(1000 * 60);
        Snapshot s = last();
        assertEquals(54, s.items.size());
        assertEquals(54, withDetail(s));
        assertNull(s.problem);
        assertFalse(s.refreshing);
        assertFalse(s.isStale());
        assertTrue(s.newIds.isEmpty()); // no "new" alerts on the first load
        assertEquals(1, faa.listCalls);
        assertEquals(54, faa.detailCalls);
        // Next poll after 10 min reads only the list.
        sched.advance(10 * MIN);
        assertEquals(2, faa.listCalls);
        assertEquals(54, faa.detailCalls);
    }

    @Test
    public void newTfrIsAnnouncedOnce() throws Exception {
        faa.without("6/4333");
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        assertEquals(53, last().items.size());
        faa.list = Fixtures.list();
        e.refreshNow();
        sched.advance(MIN);
        boolean announced = false;
        for (Snapshot s : snaps)
            if (s.newIds.contains("6/4333"))
                announced = true;
        assertTrue(announced);
        assertTrue(last().newIds.isEmpty());
        assertNotNull(last().find("6/4333").tfr);
    }

    @Test
    public void cancelledTfrNeedsTwoPollsToDisappear() throws Exception {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        faa.without("6/4199");
        sched.advance(10 * MIN);
        Snapshot.Item i = last().find("6/4199");
        assertNotNull(i);
        assertTrue(i.possiblyCancelled);
        sched.advance(10 * MIN);
        assertNull(last().find("6/4199"));
        assertFalse(new File(dir, "details/detail_6_4199.xml").exists());
    }

    @Test
    public void returningTfrClearsTheFlag() throws Exception {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        faa.without("6/4199");
        sched.advance(10 * MIN);
        faa.list = Fixtures.list();
        sched.advance(10 * MIN);
        assertFalse(last().find("6/4199").possiblyCancelled);
    }

    @Test
    public void emptyListNeverWipesTheMap() {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        faa.list = "[]".getBytes(StandardCharsets.UTF_8);
        sched.advance(6 * 60 * MIN); // six hours of empty answers
        Snapshot s = last();
        assertEquals(54, s.items.size());
        for (Snapshot.Item i : s.items) {
            assertFalse(i.possiblyCancelled);
            assertNotNull(i.tfr);
        }
        assertTrue(s.problem.contains("came back empty"));
        assertTrue(s.isStale());
        assertEquals(54, new File(dir, "details").list().length);
        // When the real list returns, everything is normal again.
        faa.list = Fixtures.list();
        sched.advance(10 * MIN);
        assertNull(last().problem);
        assertFalse(last().isStale());
    }

    @Test
    public void emptyListAcceptedOnAFreshInstall() {
        faa.list = "[]".getBytes(StandardCharsets.UTF_8);
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        assertEquals(0, last().items.size());
        assertNull(last().problem);
        assertFalse(last().isStale());
    }

    @Test
    public void bigDropHeldForThirtyMinutes() throws Exception {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        faa.firstRows(10);
        sched.advance(10 * MIN);
        assertTrue(last().problem.contains("dropped from 54 to 10"));
        sched.advance(25 * MIN); // still within 30 min: held
        assertEquals(54, last().items.size());
        for (Snapshot.Item i : last().items)
            assertFalse(i.possiblyCancelled);
        sched.advance(10 * MIN); // confirmed
        int flagged = 0;
        for (Snapshot.Item i : last().items)
            if (i.possiblyCancelled)
                flagged++;
        assertEquals(44, flagged);
        assertNull(last().problem);
        sched.advance(10 * MIN);
        assertEquals(10, last().items.size());
    }

    @Test
    public void shortDropBlipIsIgnored() throws Exception {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        faa.firstRows(10);
        sched.advance(10 * MIN);
        faa.list = Fixtures.list();
        sched.advance(10 * MIN);
        assertNull(last().problem);
        for (Snapshot.Item i : last().items)
            assertFalse(i.possiblyCancelled);
        assertEquals(54, last().items.size());
    }

    @Test
    public void newTfrAlertSurvivesDetailFailure() throws Exception {
        final int[] fails = { 1 };
        faa = new FakeFaa() {
            @Override
            public byte[] fetchDetail(String id) throws IOException {
                if (id.equals("6/4199") && fails[0]-- > 0)
                    throw new FaaClient.HttpStatusException(503);
                return super.fetchDetail(id);
            }
        };
        faa.without("6/4199");
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        faa.list = Fixtures.list();
        sched.advance(10 * MIN); // listed; detail fails (503)
        Snapshot.Item i = last().find("6/4199");
        assertNull(i.tfr);
        // Retried after 2 min, not 10, and alerted with its details.
        sched.advance(2 * MIN);
        boolean alerted = false;
        for (Snapshot s : snaps)
            if (s.newIds.contains("6/4199")) {
                assertNotNull(s.find("6/4199").tfr);
                alerted = true;
            }
        assertTrue(alerted);
        // Only once.
        int count = 0;
        snaps.clear();
        sched.advance(30 * MIN);
        for (Snapshot s : snaps)
            if (s.newIds.contains("6/4199"))
                count++;
        assertEquals(0, count);
    }

    @Test
    public void pendingAlertSurvivesRestart() throws Exception {
        faa.without("6/4199");
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        final boolean[] failDetail = { true };
        FakeFaa failing = new FakeFaa() {
            @Override
            public byte[] fetchDetail(String id) throws IOException {
                if (failDetail[0] && id.equals("6/4199"))
                    throw new FaaClient.HttpStatusException(500);
                return super.fetchDetail(id);
            }
        };
        e.stop();
        long t = sched.now;
        sched = new ManualScheduler();
        sched.now = t;
        faa = failing;
        TfrEngine e2 = engine();
        e2.start();
        sched.advance(11 * MIN); // lists 6/4199 as new; detail fails
        assertNull(last().find("6/4199").tfr);
        e2.stop();
        t = sched.now;
        sched = new ManualScheduler();
        sched.now = t + 5 * MIN;
        failDetail[0] = false;
        snaps.clear();
        TfrEngine e3 = engine();
        e3.start();
        sched.advance(MIN);
        boolean alerted = false;
        for (Snapshot s : snaps)
            if (s.newIds.contains("6/4199"))
                alerted = true;
        assertTrue(alerted);
    }

    @Test
    public void removedTfrLeavesNoFiles() throws Exception {
        TfrEngine e = engine();
        e.start();
        sched.advance(3 * 60 * MIN); // hourly refreshes create .bak files
        faa.without("6/4199");
        sched.advance(25 * MIN);
        assertNull(last().find("6/4199"));
        for (String n : new File(dir, "details").list())
            assertFalse(n, n.contains("6_4199"));
    }

    @Test
    public void stopDuringPollStopsEverything() throws Exception {
        final TfrEngine[] ref = new TfrEngine[1];
        faa = new FakeFaa() {
            @Override
            public byte[] fetchDetail(String id) throws IOException {
                if (detailCalls == 5)
                    ref[0].stop();
                return super.fetchDetail(id);
            }
        };
        TfrEngine e = engine();
        ref[0] = e;
        e.start();
        sched.advance(MIN);
        assertEquals(6, faa.detailCalls); // the one in flight, then nothing
        String[] files = new File(dir, "details").list();
        assertTrue(files == null || files.length <= 5);
        int snapsAtStop = snaps.size();
        sched.advance(60 * MIN);
        assertEquals(6, faa.detailCalls);
        assertEquals(snapsAtStop, snaps.size());
    }

    @Test
    public void startupSweepsOrphanFiles() throws Exception {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        e.stop();
        File d = new File(dir, "details");
        for (String n : new String[] { "detail_1_0001.xml", "detail_1_0001.xml.bak",
                "detail_6_4199.xml.tmp" })
            new FileOutputStream(new File(d, n)).close();
        for (int i = 0; i < 8; i++)
            new FileOutputStream(new File(d, "detail_x.xml.corrupt-" + (1000 + i))).close();
        sched = new ManualScheduler();
        faa = new FakeFaa();
        faa.down = true;
        TfrEngine e2 = engine();
        e2.start();
        sched.advance(1000);
        List<String> left = e2.cacheFiles();
        assertFalse(left.toString(), left.contains("details/detail_1_0001.xml"));
        assertFalse(left.contains("details/detail_1_0001.xml.bak"));
        assertFalse(left.contains("details/detail_6_4199.xml.tmp"));
        assertTrue(left.contains("details/detail_6_4199.xml"));
        int corrupt = 0;
        for (String n : left)
            if (n.contains(".corrupt-"))
                corrupt++;
        assertEquals(5, corrupt);
        assertFalse(left.contains("details/detail_x.xml.corrupt-1000"));
        assertTrue(left.contains("details/detail_x.xml.corrupt-1007"));
    }

    @Test
    public void garbageListChangesNothing() {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        faa.list = "<html><body>Service Unavailable</body></html>".getBytes(StandardCharsets.UTF_8);
        sched.advance(10 * MIN);
        assertEquals(54, last().items.size());
        assertTrue(last().problem.contains("could not be read"));
        for (Snapshot.Item i : last().items)
            assertFalse(i.possiblyCancelled);
    }

    @Test
    public void outageKeepsDataAndRetries() {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        faa.down = true;
        sched.advance(10 * MIN);
        assertEquals(54, last().items.size());
        assertTrue(last().problem.contains("Can't reach"));
        int calls = faa.listCalls;
        sched.advance(MIN); // first retry after 1 min
        assertEquals(calls + 1, faa.listCalls);
        sched.advance(40 * MIN);
        assertTrue(last().isStale());
        assertEquals(54, last().items.size());
        faa.down = false;
        sched.advance(10 * MIN);
        assertNull(last().problem);
        assertFalse(last().isStale());
    }

    @Test
    public void restartWithoutNetworkRestoresFromCache() {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        e.stop();
        sched = new ManualScheduler();
        sched.now = Fixtures.utc("2026-11-02T18:00");
        faa = new FakeFaa();
        faa.down = true;
        snaps.clear();
        TfrEngine e2 = engine();
        e2.start();
        sched.advance(1000);
        Snapshot s = last();
        assertEquals(54, s.items.size());
        assertEquals(54, withDetail(s));
        assertTrue(s.isStale());
        assertTrue(s.problem.contains("Can't reach"));
    }

    @Test
    public void restartWhileFreshDoesNotPollImmediately() {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        e.stop();
        long t = sched.now;
        sched = new ManualScheduler();
        sched.now = t + 30000;
        faa = new FakeFaa();
        TfrEngine e2 = engine();
        e2.start();
        sched.advance(1000);
        assertEquals(0, faa.listCalls);
        sched.advance(10 * MIN);
        assertEquals(1, faa.listCalls);
    }

    @Test
    public void brokenDetailKeepsLastGoodCopy() throws Exception {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        // The list row changes (amendment), but the new detail is unreadable.
        JSONArray in = new JSONArray(new String(Fixtures.list(), StandardCharsets.UTF_8));
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.getJSONObject(i);
            if (o.getString("notam_id").equals("6/4199"))
                o.put("description", "Rome, NY, amended");
        }
        faa.list = in.toString().getBytes(StandardCharsets.UTF_8);
        faa.brokenDetails.add("6/4199");
        sched.advance(10 * MIN);
        Snapshot.Item i = last().find("6/4199");
        assertNotNull(i.tfr); // last good copy still shown
        assertNotNull(i.detailError);
        // When the FAA fixes it, the error clears.
        faa.brokenDetails.clear();
        sched.advance(10 * MIN);
        assertNull(last().find("6/4199").detailError);
    }

    @Test
    public void newTfrWithUnreadableDetailIsStillListed() throws Exception {
        faa.brokenDetails.add("6/4313");
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        Snapshot.Item i = last().find("6/4313");
        assertNotNull(i);
        assertNull(i.tfr);
        assertTrue(i.detailError.contains("could not be read"));
    }

    @Test
    public void corruptStateIsQuarantined() throws Exception {
        try (FileOutputStream o = new FileOutputStream(new File(dir, "state.json"))) {
            o.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        assertEquals(54, last().items.size());
        boolean quarantined = false;
        for (String n : dir.list())
            if (n.startsWith("state.json.corrupt-"))
                quarantined = true;
        assertTrue(quarantined);
    }

    @Test
    public void detailsRefreshedHourlyInSmallBatches() {
        TfrEngine e = engine();
        e.start();
        sched.advance(MIN);
        int before = faa.detailCalls;
        sched.advance(61 * MIN); // several polls; details now over an hour old
        assertTrue(faa.detailCalls > before);
        // Never more than the batch limit per poll.
        assertTrue(faa.detailCalls - before <= Tuning.MAX_REFRESHES_PER_POLL * 2);
    }
}

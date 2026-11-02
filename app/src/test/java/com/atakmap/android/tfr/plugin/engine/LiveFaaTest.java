package com.atakmap.android.tfr.plugin.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.atakmap.android.tfr.plugin.core.TfrArea;
import com.atakmap.android.tfr.plugin.net.HttpFaaClient;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End to end against the real tfr.faa.gov. Skipped unless run with
 * {@code -Dtfr.live=true} (gradle: {@code -Ptfr.live=true}); network tests
 * must never decide a normal build.
 */
public class LiveFaaTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void liveFeedParsesCompletely() throws Exception {
        assumeTrue(Boolean.getBoolean("tfr.live"));
        File dir = tmp.newFolder("live");
        final AtomicReference<Snapshot> last = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        ExecutorScheduler sched = new ExecutorScheduler();
        TfrEngine e = new TfrEngine(sched, new HttpFaaClient("test"), dir);
        e.setListener(new TfrEngine.Listener() {
            @Override
            public void onSnapshot(Snapshot s) {
                last.set(s);
                if (!s.refreshing && s.lastListOkMs > 0)
                    done.countDown();
            }
        });
        e.start();
        assertTrue("timed out", done.await(5, TimeUnit.MINUTES));
        e.stop();
        Snapshot s = last.get();
        assertNull(s.problem, s.problem);
        assertTrue(s.items.size() > 0);
        int areas = 0, checked = 0, faaOnly = 0, none = 0, warn = 0;
        for (Snapshot.Item i : s.items) {
            assertTrue(i.id() + " " + i.detailError, i.tfr != null);
            if (!i.tfr.warnings.isEmpty()) {
                warn++;
                System.out.println("WARN " + i.id() + " " + i.tfr.warnings);
            }
            for (TfrArea a : i.tfr.areas) {
                areas++;
                if (a.source == TfrArea.Source.DEFINITION_CHECKED)
                    checked++;
                else if (a.source == TfrArea.Source.NONE)
                    none++;
                else {
                    faaOnly++;
                    System.out.println("NOTE " + i.id() + " " + a.source + " " + a.geometryNote);
                }
            }
        }
        System.out.println("LIVE: " + s.items.size() + " TFRs, " + areas
                + " areas: " + checked + " checked, " + faaOnly
                + " other outline, " + none + " text-only; " + warn
                + " with warnings");
        assertEquals(0, faaOnly);
    }
}

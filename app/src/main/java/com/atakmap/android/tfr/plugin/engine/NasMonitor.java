package com.atakmap.android.tfr.plugin.engine;

import com.atakmap.android.tfr.plugin.core.nas.Advisory;
import com.atakmap.android.tfr.plugin.core.nas.AirportEvent;
import com.atakmap.android.tfr.plugin.core.nas.National;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Polls the FAA's national airspace status on its own thread: NAS Status
 * airport events and the Command Center advisories (today's and
 * yesterday's, UTC). Publishes immutable {@link NasSnapshot}s.
 *
 * <p>Hardening:
 * <ul>
 * <li>The advisories server answers "no advisories" / "NO Advisory." to
 * most requests at random: lists and texts are retried, an empty answer
 * never replaces a non-empty one, and a day's list only ever grows.</li>
 * <li>A failed or unreadable airport feed keeps the last good one (flagged
 * by its age).</li>
 * <li>{@code stop()} is honoured between requests.</li>
 * </ul>
 */
public final class NasMonitor {

    public interface Source {
        byte[] airportEvents() throws IOException;

        byte[] advisoryList(int date) throws IOException;

        byte[] advisoryText(int date, int number) throws IOException;
    }

    public interface Listener {
        void onNas(NasSnapshot s);
    }

    public static final long POLL_MS = 5 * 60_000L;
    public static final long RETRY_MS = 2 * 60_000L;
    static final int LIST_TRIES = 8;
    static final int TEXT_TRIES = 6;
    static final long TRY_GAP_MS = 1200;

    private final Scheduler sched;
    private final Source src;
    private volatile Listener listener;
    private volatile boolean stopped;

    // engine thread only
    private List<AirportEvent> airports = Collections.emptyList();
    private long airportsOkMs;
    private String airportsError;
    private final Map<Integer, List<Advisory>> lists = new HashMap<>();
    private final Map<String, Advisory.Text> texts = new HashMap<>();
    private final Map<String, Integer> textFails = new HashMap<>();
    private long advOkMs;
    private String advError;
    private final Set<String> seenNational = new HashSet<>();
    private boolean firstDone;
    /** Only the newest poll chain runs (a manual refresh starts a new one). */
    private int gen;
    private long lastPollMs = -1;

    public NasMonitor(Scheduler sched, Source src) {
        this.sched = sched;
        this.src = src;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void start() {
        stopped = false;
        sched.execute(new Runnable() {
            @Override
            public void run() {
                poll(++gen);
            }
        });
    }

    /** Manual refresh; ignored within 30 s of the last poll. */
    public void refreshNow() {
        sched.execute(new Runnable() {
            @Override
            public void run() {
                if (lastPollMs >= 0 && sched.now() - lastPollMs < 30_000L)
                    return;
                poll(++gen);
            }
        });
    }

    public void stop() {
        stopped = true;
    }

    /** One poll; reschedules itself. Engine thread. */
    void poll(final int g) {
        if (stopped || g != gen)
            return;
        lastPollMs = sched.now();
        boolean ok = true;
        try {
            byte[] b = src.airportEvents();
            if (stopped)
                return;
            airports = AirportEvent.parse(b);
            airportsOkMs = sched.now();
            airportsError = null;
        } catch (IOException | AirportEvent.ParseException
                | RuntimeException e) {
            airportsError = "Airport status: " + describe(e);
            ok = false;
        }
        if (stopped)
            return;
        long now = sched.now();
        int today = Advisory.dateOf(now);
        int yesterday = Advisory.dateOf(now - 24 * 3600_000L);
        // Keep only today's and yesterday's advisories.
        lists.keySet().retainAll(java.util.Arrays.asList(today, yesterday));
        for (java.util.Iterator<String> it = texts.keySet().iterator(); it
                .hasNext();) {
            String k = it.next();
            if (!k.startsWith(today + "-") && !k.startsWith(yesterday + "-"))
                it.remove();
        }
        for (java.util.Iterator<String> it = textFails.keySet().iterator(); it
                .hasNext();) {
            String k = it.next();
            if (!k.startsWith(today + "-") && !k.startsWith(yesterday + "-"))
                it.remove();
        }
        boolean gotToday = fetchList(today);
        if (!lists.containsKey(yesterday))
            fetchList(yesterday);
        if (gotToday) {
            advOkMs = sched.now();
            advError = null;
        } else {
            advError = "Command Center advisories: no answer from the FAA "
                    + "server (it often answers empty; retrying)";
            ok = false;
        }
        // Texts of national advisories, newest first, a few per poll.
        List<Advisory> nat = new ArrayList<>();
        for (List<Advisory> l : lists.values())
            for (Advisory a : l)
                if (a.nationalKind() != null && !texts.containsKey(a.key()))
                    nat.add(a);
        int budget = 6;
        for (Advisory a : nat) {
            if (stopped || budget-- <= 0)
                break;
            Integer fails = textFails.get(a.key());
            if (fails != null && fails >= 3)
                continue; // 18 tries over three polls: leave it
            Advisory.Text t = fetchText(a);
            if (t != null)
                texts.put(a.key(), t);
            else
                textFails.put(a.key(), fails == null ? 1 : fails + 1);
        }
        if (stopped)
            return;
        publish();
        sched.schedule(new Runnable() {
            @Override
            public void run() {
                poll(g);
            }
        }, ok ? POLL_MS : RETRY_MS);
    }

    private boolean fetchList(int date) {
        for (int i = 0; i < LIST_TRIES && !stopped; i++) {
            try {
                List<Advisory> l = Advisory.parseList(src.advisoryList(date),
                        date);
                if (l != null) {
                    List<Advisory> old = lists.get(date);
                    // A day's list only grows; a shorter answer is ignored.
                    if (old == null || l.size() >= old.size())
                        lists.put(date, l);
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                // retry
            }
            sched.pause(TRY_GAP_MS);
        }
        return false;
    }

    private Advisory.Text fetchText(Advisory a) {
        for (int i = 0; i < TEXT_TRIES && !stopped; i++) {
            try {
                Advisory.Text t = Advisory.parseText(
                        src.advisoryText(a.date, a.number), a.date);
                if (t != null && t.header.contains(" " + three(a.number)))
                    return t;
                if (t != null && t.header.contains(" " + a.number + " "))
                    return t;
            } catch (IOException | RuntimeException e) {
                // retry
            }
            sched.pause(TRY_GAP_MS);
        }
        return null;
    }

    private static String three(int n) {
        return n < 10 ? "00" + n : n < 100 ? "0" + n : String.valueOf(n);
    }

    private void publish() {
        long now = sched.now();
        List<Advisory> all = new ArrayList<>();
        for (List<Advisory> l : lists.values())
            all.addAll(l);
        List<National.Event> nat = National.active(all,
                new National.Texts() {
                    @Override
                    public Advisory.Text get(Advisory a) {
                        return texts.get(a.key());
                    }
                }, now);
        List<National.Event> fresh = new ArrayList<>();
        for (National.Event e : nat) {
            if (e.cancelled)
                continue;
            if (seenNational.add(e.advisory.key()) && firstDone)
                fresh.add(e);
        }
        firstDone = true;
        NasSnapshot s = new NasSnapshot(now, airports, airportsOkMs,
                airportsError, nat, advOkMs, advError, fresh);
        Listener l = listener;
        if (l != null && !stopped)
            l.onNas(s);
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName()
                + (m == null || m.length() > 80 ? "" : ": " + m);
    }
}

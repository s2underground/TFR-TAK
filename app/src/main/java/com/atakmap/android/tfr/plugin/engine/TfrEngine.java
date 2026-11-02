package com.atakmap.android.tfr.plugin.engine;

import com.atakmap.android.tfr.plugin.core.NotamId;
import com.atakmap.android.tfr.plugin.core.Tfr;
import com.atakmap.android.tfr.plugin.core.TfrListParser;
import com.atakmap.android.tfr.plugin.core.TfrXmlParser;
import com.atakmap.android.tfr.plugin.net.FaaClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns all TFR state. Everything runs on the scheduler's single thread;
 * the outside world sees only immutable {@link Snapshot}s.
 *
 * <p>Hardening rules (see docs/DESIGN.md):
 * <ul>
 * <li>A list that can't be read never changes the picture.
 * <li>A suddenly empty or halved list is held back until confirmed by
 * further polls, so an FAA glitch can't wipe the map.
 * <li>A TFR missing from one poll is flagged "possibly cancelled" and only
 * removed after {@link Tuning#MISSING_POLLS_TO_REMOVE} polls in a row.
 * <li>Details that fail to refresh keep the last good copy, flagged.
 * <li>Raw FAA files are cached on the device, so the last picture survives
 * restarts without signal; states (pending/active/expired) are always
 * computed from the published times, so they stay right offline.
 * </ul>
 */
public final class TfrEngine {

    public interface Listener {
        /** Called on the engine thread. */
        void onSnapshot(Snapshot s);
    }

    private static final int STATE_VERSION = 1;
    private static final int MAX_STATE_BYTES = 8 * 1024 * 1024;

    private static final class Record {
        TfrListParser.Entry entry;
        Tfr tfr;
        String detailError;
        long detailFetchedMs;
        /** List signature at the time the detail was read. */
        String detailSig = "";
        int missingPolls;
        long firstSeenMs;
    }

    private final Scheduler sched;
    private final FaaClient client;
    private final File dir;
    private final File detailDir;
    private final File stateFile;
    private volatile Listener listener;

    private final Map<String, Record> records = new LinkedHashMap<>();
    private long lastListOkMs, lastAttemptMs;
    private String problem;
    private boolean refreshing, stopped, loadedOnce;
    private int failures, suspectStreak;
    private int pollGen;
    private List<String> newIds = new ArrayList<>();

    public TfrEngine(Scheduler sched, FaaClient client, File cacheDir) {
        this.sched = sched;
        this.client = client;
        this.dir = cacheDir;
        this.detailDir = new File(cacheDir, "details");
        this.stateFile = new File(cacheDir, "state.json");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ---- lifecycle -----------------------------------------------------

    public void start() {
        sched.execute(new Runnable() {
            @Override
            public void run() {
                load();
                publish();
                long age = sched.now() - lastListOkMs;
                schedulePoll(lastListOkMs > 0 && age >= 0
                        && age < Tuning.STARTUP_FRESH_MS
                                ? Tuning.POLL_MS - age
                                : 0);
                tick();
            }
        });
    }

    public void stop() {
        sched.execute(new Runnable() {
            @Override
            public void run() {
                stopped = true;
            }
        });
        sched.shutdown();
    }

    /** Poll now (the pane's Refresh button). */
    public void refreshNow() {
        sched.execute(new Runnable() {
            @Override
            public void run() {
                if (stopped || refreshing)
                    return;
                if (sched.now() - lastAttemptMs < Tuning.MANUAL_MIN_GAP_MS
                        && problem == null)
                    return;
                schedulePoll(0);
            }
        });
    }

    /** Republish (e.g. when the pane opens). */
    public void republish() {
        sched.execute(new Runnable() {
            @Override
            public void run() {
                publish();
            }
        });
    }

    private void schedulePoll(long delay) {
        final int gen = ++pollGen;
        sched.schedule(new Runnable() {
            @Override
            public void run() {
                if (gen == pollGen && !stopped)
                    poll();
            }
        }, delay);
    }

    private void tick() {
        if (stopped)
            return;
        sched.schedule(new Runnable() {
            @Override
            public void run() {
                if (stopped)
                    return;
                publish();
                tick();
            }
        }, Tuning.TICK_MS);
    }

    // ---- polling -------------------------------------------------------

    private void poll() {
        refreshing = true;
        newIds = new ArrayList<>();
        lastAttemptMs = sched.now();
        publish();
        try {
            boolean ok = pollList();
            if (ok)
                pollDetails();
        } finally {
            refreshing = false;
            save();
            publish();
            newIds = new ArrayList<>();
            if (!stopped) {
                long delay;
                if (suspectStreak > 0) // confirm a suspicious list soon
                    delay = Tuning.RETRY_MS[1];
                else if (failures > 0)
                    delay = Tuning.RETRY_MS[Math.min(failures,
                            Tuning.RETRY_MS.length) - 1];
                else
                    delay = Tuning.POLL_MS;
                schedulePoll(delay);
            }
        }
    }

    /** True if a list was accepted. */
    private boolean pollList() {
        byte[] raw;
        try {
            raw = client.fetchList();
        } catch (IOException e) {
            failures++;
            problem = "Can't reach tfr.faa.gov (" + describe(e)
                    + "). Showing the last data received.";
            return false;
        }
        TfrListParser.Result result;
        try {
            result = TfrListParser.parse(raw);
        } catch (TfrListParser.FeedException e) {
            failures++;
            problem = "The FAA list could not be read (" + e.getMessage()
                    + "). Showing the last data received.";
            return false;
        }

        int previous = 0;
        for (Record r : records.values())
            if (r.missingPolls == 0)
                previous++;
        int now = result.entries.size();
        if (now == 0 && previous >= Tuning.SUSPECT_EMPTY_MIN_PREVIOUS) {
            if (++suspectStreak < Tuning.SUSPECT_EMPTY_CONFIRMATIONS) {
                problem = "The FAA list came back empty. Keeping the previous "
                        + previous + " TFRs until that is confirmed.";
                return false;
            }
        } else if (previous >= Tuning.SUSPECT_DROP_MIN_PREVIOUS
                && now * 2 < previous) {
            if (++suspectStreak < Tuning.SUSPECT_DROP_CONFIRMATIONS) {
                problem = "The FAA list dropped from " + previous + " to " + now
                        + " TFRs. Keeping the previous picture until that is confirmed.";
                return false;
            }
        }
        suspectStreak = 0;
        failures = 0;
        problem = result.rejected > 0 ? result.rejected
                + " rows of the FAA list could not be read." : null;
        accept(result.entries);
        return true;
    }

    private void accept(List<TfrListParser.Entry> entries) {
        long t = sched.now();
        lastListOkMs = t;
        Set<String> seen = new HashSet<>();
        for (TfrListParser.Entry e : entries) {
            seen.add(e.id);
            Record r = records.get(e.id);
            if (r == null) {
                r = new Record();
                r.firstSeenMs = t;
                records.put(e.id, r);
                if (loadedOnce)
                    newIds.add(e.id);
            }
            r.entry = e;
            r.missingPolls = 0;
        }
        for (Iterator<Map.Entry<String, Record>> it = records.entrySet()
                .iterator(); it.hasNext();) {
            Map.Entry<String, Record> me = it.next();
            if (seen.contains(me.getKey()))
                continue;
            Record r = me.getValue();
            if (++r.missingPolls >= Tuning.MISSING_POLLS_TO_REMOVE) {
                AtomicFiles.delete(detailFile(me.getKey()));
                it.remove();
            }
        }
        loadedOnce = true;
        save();
        publish();
    }

    private void pollDetails() {
        long t = sched.now();
        List<Record> needed = new ArrayList<>();
        List<Record> refresh = new ArrayList<>();
        for (Record r : records.values()) {
            if (r.missingPolls > 0)
                continue;
            if (r.tfr == null || !r.entry.signature().equals(r.detailSig))
                needed.add(r);
            else if (t - r.detailFetchedMs > Tuning.DETAIL_REFRESH_MS)
                refresh.add(r);
        }
        Collections.sort(refresh, new Comparator<Record>() {
            @Override
            public int compare(Record a, Record b) {
                return Long.compare(a.detailFetchedMs, b.detailFetchedMs);
            }
        });
        if (refresh.size() > Tuning.MAX_REFRESHES_PER_POLL)
            refresh = refresh.subList(0, Tuning.MAX_REFRESHES_PER_POLL);
        needed.addAll(refresh);

        int done = 0;
        for (Record r : needed) {
            if (stopped)
                return;
            if (done++ > 0)
                sched.pause(Tuning.DETAIL_GAP_MS);
            String id = r.entry.id;
            byte[] raw;
            try {
                raw = client.fetchDetail(id);
            } catch (FaaClient.HttpStatusException e) {
                r.detailError = "The FAA detail file is not available ("
                        + e.getMessage() + ").";
                continue;
            } catch (IOException e) {
                problem = "Connection lost while reading TFR details ("
                        + describe(e) + "). Will retry.";
                failures++;
                return;
            }
            try {
                Tfr tfr = TfrXmlParser.parse(raw, id);
                r.tfr = tfr;
                r.detailError = null;
                r.detailFetchedMs = sched.now();
                r.detailSig = r.entry.signature();
                try {
                    AtomicFiles.write(detailFile(id), raw);
                } catch (IOException e) {
                    problem = "Could not save to the device cache ("
                            + describe(e) + ").";
                }
            } catch (TfrXmlParser.ParseException e) {
                r.detailError = "The FAA detail file could not be read ("
                        + e.getMessage() + ").";
            }
            if (done % 10 == 0)
                publish();
        }
    }

    private static String describe(IOException e) {
        if (e instanceof FaaClient.HttpStatusException)
            return e.getMessage();
        if (e instanceof java.net.SocketTimeoutException)
            return "timed out";
        if (e instanceof java.net.UnknownHostException)
            return "no network or DNS";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // ---- snapshot ------------------------------------------------------

    private void publish() {
        Listener l = listener;
        if (l == null)
            return;
        List<Snapshot.Item> items = new ArrayList<>(records.size());
        for (Record r : records.values())
            items.add(new Snapshot.Item(r.entry, r.tfr, r.detailError,
                    r.missingPolls > 0, r.firstSeenMs));
        l.onSnapshot(new Snapshot(items, sched.now(), lastListOkMs,
                lastAttemptMs, problem, refreshing, newIds));
    }

    // ---- persistence ---------------------------------------------------

    private File detailFile(String id) {
        return new File(detailDir, "detail_" + NotamId.fileKey(id) + ".xml");
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", STATE_VERSION);
            o.put("lastListOkMs", lastListOkMs);
            o.put("lastAttemptMs", lastAttemptMs);
            JSONArray arr = new JSONArray();
            for (Record r : records.values()) {
                JSONObject j = new JSONObject();
                j.put("id", r.entry.id);
                j.put("type", r.entry.type);
                j.put("facility", r.entry.facility);
                j.put("state", r.entry.state);
                j.put("description", r.entry.description);
                j.put("created", r.entry.created);
                j.put("detailFetchedMs", r.detailFetchedMs);
                j.put("detailSig", r.detailSig);
                j.put("missingPolls", r.missingPolls);
                j.put("firstSeenMs", r.firstSeenMs);
                if (r.detailError != null)
                    j.put("detailError", r.detailError);
                arr.put(j);
            }
            o.put("records", arr);
            AtomicFiles.write(stateFile,
                    o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException | IOException e) {
            problem = "Could not save to the device cache.";
        }
    }

    private void load() {
        records.clear();
        JSONObject o = readState(stateFile);
        if (o == null)
            o = readState(new File(dir, "state.json.bak"));
        if (o == null)
            return;
        lastListOkMs = o.optLong("lastListOkMs", 0);
        lastAttemptMs = o.optLong("lastAttemptMs", 0);
        JSONArray arr = o.optJSONArray("records");
        if (arr == null)
            return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null)
                continue;
            String id = j.optString("id", "");
            if (!NotamId.isValid(id) || records.containsKey(id))
                continue;
            Record r = new Record();
            r.entry = new TfrListParser.Entry(id, j.optString("type", ""),
                    j.optString("facility", ""), j.optString("state", ""),
                    j.optString("description", ""), j.optString("created", ""));
            r.detailFetchedMs = j.optLong("detailFetchedMs", 0);
            r.detailSig = j.optString("detailSig", "");
            r.missingPolls = Math.max(0, j.optInt("missingPolls", 0));
            r.firstSeenMs = j.optLong("firstSeenMs", 0);
            r.detailError = j.has("detailError") ? j.optString("detailError")
                    : null;
            try {
                byte[] raw = AtomicFiles.read(detailFile(id),
                        TfrXmlParser.MAX_BYTES);
                if (raw != null)
                    r.tfr = TfrXmlParser.parse(raw, id);
            } catch (IOException | TfrXmlParser.ParseException e) {
                AtomicFiles.quarantine(detailFile(id), sched.now());
                r.tfr = null;
                r.detailSig = "";
            }
            records.put(id, r);
        }
        loadedOnce = !records.isEmpty() || lastListOkMs > 0;
    }

    private JSONObject readState(File f) {
        try {
            byte[] raw = AtomicFiles.read(f, MAX_STATE_BYTES);
            if (raw == null)
                return null;
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            if (o.optInt("v", -1) != STATE_VERSION)
                return null;
            return o;
        } catch (IOException | JSONException e) {
            AtomicFiles.quarantine(f, sched.now());
            return null;
        }
    }
}

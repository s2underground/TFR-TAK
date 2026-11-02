package com.atakmap.android.tfr.plugin.engine;

import com.atakmap.android.tfr.plugin.core.Tfr;
import com.atakmap.android.tfr.plugin.core.TfrListParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An immutable picture of everything the engine knows, for UI and map. */
public final class Snapshot {

    /** One TFR as listed by the FAA. */
    public static final class Item {
        public final TfrListParser.Entry entry;
        /** Parsed detail; null if not (yet) readable. */
        public final Tfr tfr;
        /** Why the detail isn't available or current; null if fine. */
        public final String detailError;
        /** Missing from the latest FAA list (removed after a second miss). */
        public final boolean possiblyCancelled;
        public final long firstSeenMs;

        public Item(TfrListParser.Entry entry, Tfr tfr, String detailError,
                boolean possiblyCancelled, long firstSeenMs) {
            this.entry = entry;
            this.tfr = tfr;
            this.detailError = detailError;
            this.possiblyCancelled = possiblyCancelled;
            this.firstSeenMs = firstSeenMs;
        }

        public String id() {
            return entry.id;
        }
    }

    public final List<Item> items;
    /** Time of this snapshot (engine clock). */
    public final long nowMs;
    /** Last accepted FAA list; 0 if never. */
    public final long lastListOkMs;
    public final long lastAttemptMs;
    /** Latest problem, for the status line; null if the last poll was clean. */
    public final String problem;
    /** A poll is running. */
    public final boolean refreshing;
    /** Ids that appeared since the previous poll (not on the first load). */
    public final List<String> newIds;

    public Snapshot(List<Item> items, long nowMs, long lastListOkMs,
            long lastAttemptMs, String problem, boolean refreshing,
            List<String> newIds) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.nowMs = nowMs;
        this.lastListOkMs = lastListOkMs;
        this.lastAttemptMs = lastAttemptMs;
        this.problem = problem;
        this.refreshing = refreshing;
        this.newIds = Collections.unmodifiableList(new ArrayList<>(newIds));
    }

    public boolean isStale() {
        return lastListOkMs == 0 || nowMs - lastListOkMs > Tuning.STALE_MS;
    }

    public Item find(String id) {
        for (Item i : items)
            if (i.id().equals(id))
                return i;
        return null;
    }
}
